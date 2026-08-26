package nl.chatgptauto.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Voice engine used when Android Auto opens CAR AI through the Media3 route.
 * It prefers the active Bluetooth communication device (BMW HFP/SCO) so the
 * car microphone can be used even though Android Auto rendered the media UI.
 */
object MediaVoiceController {
    interface Listener {
        fun onState(state: String)
        fun onRunningChanged(running: Boolean)
    }

    @Volatile private var bridge: Bridge? = null

    @JvmStatic fun isRunning(): Boolean = bridge?.isRunning == true

    @JvmStatic fun start(context: Context, listener: Listener) {
        if (isRunning()) return
        val prefs = context.getSharedPreferences("chatgpt_auto", Context.MODE_PRIVATE)
        val url = prefs.getString("broker_url", BuildConfig.BROKER_URL)?.trim().orEmpty()
        val token = prefs.getString("broker_token", BuildConfig.BROKER_TOKEN)?.trim()
            ?.takeIf { it.isNotBlank() } ?: BuildConfig.BROKER_TOKEN.takeIf { it.isNotBlank() }
        val voice = prefs.getString("voice", "marin") ?: "marin"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onState("Geef CAR AI eerst microfoontoestemming op de telefoon")
            listener.onRunningChanged(false)
            return
        }
        if (!(url.startsWith("wss://") || url.startsWith("ws://"))) {
            listener.onState("Ongeldige broker-URL")
            listener.onRunningChanged(false)
            return
        }
        bridge = Bridge(context.applicationContext, url, token, voice, listener).also { it.start() }
    }

    @JvmStatic fun stop() {
        bridge?.stop()
        bridge = null
    }

    private class Bridge(
        private val context: Context,
        private val brokerUrl: String,
        private val brokerToken: String?,
        private val voice: String,
        private val listener: Listener
    ) {
        @Volatile var isRunning = false
            private set
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val finishing = AtomicBoolean(false)
        private val tools = LocalCarTools(context)
        private var ws: WebSocket? = null
        private var recorder: AudioRecord? = null
        private var output: AudioTrack? = null
        private var audioManager: AudioManager? = null
        private var focusRequest: AudioFocusRequest? = null
        private var previousMode = AudioManager.MODE_NORMAL
        private var commDevice: AudioDeviceInfo? = null
        @Volatile private var endAfterResponse = false

        fun start() {
            if (isRunning) return
            isRunning = true
            finishing.set(false)
            listener.onRunningChanged(true)
            setState("Verbinden…")
            scope.launch {
                try { connect() }
                catch (t: Throwable) { setState("Fout: ${t.message ?: "onbekend"}") }
                finally {
                    cleanup()
                    listener.onRunningChanged(false)
                }
            }
        }

        fun stop() {
            if (!isRunning || !finishing.compareAndSet(false, true)) return
            isRunning = false
            runCatching { recorder?.stop() }
            runCatching { ws?.close(1000, "user stopped") }
        }

        private suspend fun connect() = suspendCancellableCoroutine<Unit> { cont ->
            val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
            val req = Request.Builder().url(brokerUrl).apply {
                if (!brokerToken.isNullOrBlank()) header("Authorization", "Bearer $brokerToken")
            }.build()
            ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(sessionUpdate().toString())
                    scope.launch {
                        try { recordLoop(webSocket) }
                        catch (t: Throwable) { if (cont.isActive) cont.resumeWith(Result.failure(t)) }
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val j = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (j.optString("type")) {
                        "session.created", "session.updated", "input_audio_buffer.speech_started" -> setState("Ik luister…")
                        "input_audio_buffer.speech_stopped" -> setState("Even denken…")
                        "conversation.item.input_audio_transcription.completed" -> {
                            val tr = j.optString("transcript").trim()
                            if (tr.isNotBlank()) handleTranscript(webSocket, tr)
                        }
                        "response.created" -> setState("ChatGPT antwoordt…")
                        "response.output_audio.delta", "response.audio.delta" -> {
                            val d = j.optString("delta")
                            if (d.isNotEmpty()) playPcm(Base64.decode(d, Base64.DEFAULT))
                        }
                        "response.done" -> if (endAfterResponse) stop() else if (isRunning) setState("Ik luister…")
                        "error" -> {
                            val msg = j.optJSONObject("error")?.optString("message") ?: "Realtime-fout"
                            if (cont.isActive) cont.resumeWith(Result.failure(Exception(msg)))
                        }
                    }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (cont.isActive) cont.resume(Unit) {} }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (cont.isActive) cont.resumeWith(Result.failure(t)) }
            })
            cont.invokeOnCancellation { stop() }
        }

        private fun handleTranscript(webSocket: WebSocket, transcript: String) {
            scope.launch {
                val result = runCatching { tools.tryHandle(transcript) }
                    .getOrElse { LocalCarTools.Result(true, "De opdracht kon niet worden uitgevoerd: ${it.message}") }
                if (result.handled) {
                    setState(result.message)
                    endAfterResponse = result.endConversation
                    webSocket.send(JSONObject().put("type", "response.create").put("response", JSONObject()
                        .put("output_modalities", JSONArray().put("audio"))
                        .put("instructions", "Bevestig deze uitgevoerde auto-opdracht in één korte Nederlandse zin: ${result.message}")).toString())
                } else webSocket.send(JSONObject().put("type", "response.create").toString())
            }
        }

        private fun sessionUpdate(): JSONObject {
            val input = JSONObject().put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000))
                .put("noise_reduction", JSONObject().put("type", "far_field"))
                .put("transcription", JSONObject().put("model", "gpt-4o-mini-transcribe").put("language", "nl"))
                .put("turn_detection", JSONObject().put("type", "server_vad").put("threshold", 0.50)
                    .put("prefix_padding_ms", 300).put("silence_duration_ms", 650)
                    .put("create_response", false).put("interrupt_response", true))
            val out = JSONObject().put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000)).put("voice", voice)
            return JSONObject().put("type", "session.update").put("session", JSONObject()
                .put("type", "realtime").put("model", "gpt-realtime-2.1")
                .put("instructions", "Je bent CAR AI in de auto. Spreek Nederlands, natuurlijk en bondig. Voer lokale navigatie-, bel-, Spotify- en Roon ARC-opdrachten uit via de app.")
                .put("output_modalities", JSONArray().put("audio"))
                .put("audio", JSONObject().put("input", input).put("output", out)))
        }

        private fun configureCarAudioRoute() {
            val am = context.getSystemService(AudioManager::class.java) ?: return
            audioManager = am
            previousMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    val bt = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    if (bt != null && am.setCommunicationDevice(bt)) commDevice = bt
                }
            } else {
                @Suppress("DEPRECATION")
                runCatching { am.startBluetoothSco(); am.isBluetoothScoOn = true }
            }
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attrs).setOnAudioFocusChangeListener { if (it == AudioManager.AUDIOFOCUS_LOSS) stop() }.build()
            focusRequest = req
            if (am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw IllegalStateException("Geen audiofocus")
        }

        private fun recordLoop(webSocket: WebSocket) {
            configureCarAudioRoute()
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Microfoon kon niet worden geopend")
            recorder = rec
            rec.startRecording()
            setState(if (commDevice != null) "Ik luister via de automicrofoon…" else "Ik luister…")
            val buf = ByteArray(4096)
            while (isRunning) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val pcm24 = resample16to24(buf, n)
                if (!webSocket.send(JSONObject().put("type", "input_audio_buffer.append")
                        .put("audio", Base64.encodeToString(pcm24, Base64.NO_WRAP)).toString())) break
            }
        }

        @Synchronized private fun playPcm(data: ByteArray) {
            if (output == null) {
                val min = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                output = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(min, 48000)).setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play() }
            }
            output?.write(data, 0, data.size)
        }

        private fun resample16to24(input: ByteArray, bytes: Int): ByteArray {
            val samples = bytes / 2
            if (samples < 2) return input.copyOf(bytes)
            val outSamples = ((samples - 1) * 3) / 2 + 1
            val out = ByteArray(outSamples * 2)
            fun read(idx: Int): Int { val i=idx*2; return (((input[i+1].toInt() and 255) shl 8) or (input[i].toInt() and 255)).toShort().toInt() }
            for (j in 0 until outSamples) {
                val src=j*(2.0/3.0); val i0=src.toInt().coerceAtMost(samples-1); val i1=(i0+1).coerceAtMost(samples-1)
                val v=(read(i0)+(read(i1)-read(i0))*(src-i0)).toInt().coerceIn(-32768,32767)
                out[j*2]=(v and 255).toByte(); out[j*2+1]=((v shr 8) and 255).toByte()
            }
            return out
        }

        private fun setState(s: String) { scope.launch(Dispatchers.Main) { listener.onState(s) } }

        private fun cleanup() {
            isRunning = false
            runCatching { recorder?.stop(); recorder?.release() }; recorder = null
            runCatching { output?.pause(); output?.flush(); output?.stop(); output?.release() }; output = null
            runCatching { ws?.close(1000, "done") }; ws = null
            val am = audioManager
            if (am != null) {
                focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { am.clearCommunicationDevice() }
                else { @Suppress("DEPRECATION") runCatching { am.stopBluetoothSco(); am.isBluetoothScoOn = false } }
                runCatching { am.mode = previousMode }
            }
            audioManager = null; focusRequest = null; commDevice = null
            bridge = null
            scope.cancel()
        }
    }
}
