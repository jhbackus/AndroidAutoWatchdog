package nl.chatgptauto.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Voice engine used by the Android Auto Media3 surface. */
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
        private var echoCanceler: AcousticEchoCanceler? = null
        private var noiseSuppressor: NoiseSuppressor? = null
        private var audioManager: AudioManager? = null
        private var focusRequest: AudioFocusRequest? = null
        private var previousMode = AudioManager.MODE_NORMAL
        private var commDevice: AudioDeviceInfo? = null
        @Volatile private var endAfterResponse = false
        @Volatile private var assistantSpeaking = false
        @Volatile private var bargeInPending = false
        @Volatile private var firstOutputAtMs = 0L

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
            assistantSpeaking = false
            bargeInPending = false
            runCatching { recorder?.stop() }
            runCatching { ws?.close(1000, "user stopped") }
        }

        private suspend fun connect() = suspendCancellableCoroutine<Unit> { cont ->
            val client = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
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
                        "session.created", "session.updated" -> if (!assistantSpeaking) setState("Ik luister…")

                        "input_audio_buffer.speech_started" -> {
                            if (assistantSpeaking && !bargeInPending &&
                                SystemClock.elapsedRealtime() - firstOutputAtMs > 350L) {
                                // Cancel first, then wait for the transcript. This is important:
                                // some Realtime sessions only finish transcribing the utterance once
                                // output has actually been cancelled.
                                bargeInPending = true
                                endAfterResponse = false
                                webSocket.send(JSONObject().put("type", "response.cancel").toString())
                                clearOutputAudio()
                                setState("Ik luister naar ‘stop’…")
                            } else if (!assistantSpeaking) {
                                setState("Ik luister…")
                            }
                        }

                        "input_audio_buffer.speech_stopped" -> if (!assistantSpeaking) setState("Even denken…")

                        "conversation.item.input_audio_transcription.completed" -> {
                            val tr = j.optString("transcript").trim()
                            if (tr.isBlank()) return
                            if (assistantSpeaking || bargeInPending) {
                                handleBargeInTranscript(webSocket, tr)
                            } else {
                                handleTranscript(webSocket, tr)
                            }
                        }

                        "response.created" -> {
                            assistantSpeaking = true
                            bargeInPending = false
                            firstOutputAtMs = SystemClock.elapsedRealtime()
                            setState("ChatGPT antwoordt… — zeg ‘stop’ om te onderbreken")
                        }

                        "response.output_audio.delta", "response.audio.delta" -> {
                            val d = j.optString("delta")
                            if (d.isNotEmpty() && assistantSpeaking && !bargeInPending) {
                                playPcm(Base64.decode(d, Base64.DEFAULT))
                            }
                        }

                        "response.done" -> {
                            // A response.cancel also emits response.done. While waiting for the
                            // stop transcript we must therefore NOT leave barge-in mode here.
                            if (bargeInPending) return
                            assistantSpeaking = false
                            if (endAfterResponse) stop() else if (isRunning) setState("Ik luister…")
                        }

                        "error" -> {
                            val msg = j.optJSONObject("error")?.optString("message") ?: "Realtime-fout"
                            if (cont.isActive) cont.resumeWith(Result.failure(Exception(msg)))
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (cont.isActive) cont.resume(Unit) {}
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (cont.isActive) cont.resumeWith(Result.failure(t))
                }
            })
            cont.invokeOnCancellation { stop() }
        }

        private fun handleBargeInTranscript(webSocket: WebSocket, transcript: String) {
            val normalized = transcript.lowercase(Locale.ROOT)
                .replace(Regex("[^a-zà-ÿ ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            val words = normalized.split(' ').filter { it.isNotBlank() }
            val stopSignal = words.size <= 4 && words.any { it == "stop" || it == "stoppen" }

            webSocket.send(JSONObject().put("type", "input_audio_buffer.clear").toString())
            clearOutputAudio()
            bargeInPending = false
            assistantSpeaking = false

            if (stopSignal) {
                setState("Gestopt. Ik luister…")
            } else {
                // Non-stop speech is deliberately not treated as a new command while CAR AI
                // was talking. Continue the interrupted answer instead.
                setState("Ga verder…")
                webSocket.send(
                    JSONObject().put("type", "response.create")
                        .put("response", JSONObject()
                            .put("output_modalities", JSONArray().put("audio"))
                            .put("instructions", "Ga kort verder met je vorige antwoord vanaf waar je werd onderbroken. Negeer de tussentijdse spraak van de gebruiker, tenzij die letterlijk 'stop' was."))
                        .toString()
                )
            }
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
                } else {
                    webSocket.send(JSONObject().put("type", "response.create").toString())
                }
            }
        }

        private fun sessionUpdate(): JSONObject {
            val input = JSONObject()
                .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000))
                .put("noise_reduction", JSONObject().put("type", "far_field"))
                .put("transcription", JSONObject()
                    .put("model", "gpt-4o-mini-transcribe")
                    .put("language", "nl")
                    .put("prompt", "Nederlands gesproken in een auto. Herken het losse commando 'stop' of 'stoppen' extra nauwkeurig."))
                .put("turn_detection", JSONObject()
                    .put("type", "server_vad")
                    .put("threshold", 0.45)
                    .put("prefix_padding_ms", 250)
                    .put("silence_duration_ms", 350)
                    .put("create_response", false)
                    .put("interrupt_response", false))
            val out = JSONObject()
                .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000))
                .put("voice", voice)
            return JSONObject().put("type", "session.update").put("session", JSONObject()
                .put("type", "realtime")
                .put("model", "gpt-realtime-2.1")
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
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { if (it == AudioManager.AUDIOFOCUS_LOSS) stop() }
                .build()
            focusRequest = req
            if (am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                throw IllegalStateException("Geen audiofocus")
            }
        }

        private fun recordLoop(webSocket: WebSocket) {
            configureCarAudioRoute()
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, 8192)
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Microfoon kon niet worden geopend")
            recorder = rec

            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = runCatching { AcousticEchoCanceler.create(rec.audioSessionId) }.getOrNull()?.also { it.enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = runCatching { NoiseSuppressor.create(rec.audioSessionId) }.getOrNull()?.also { it.enabled = true }
            }

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
                output = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(24000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setBufferSizeInBytes(maxOf(min, 48000))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                    .also { it.play() }
            }
            output?.write(data, 0, data.size)
        }

        @Synchronized private fun clearOutputAudio() {
            runCatching {
                output?.pause()
                output?.flush()
                output?.play()
            }
        }

        private fun resample16to24(input: ByteArray, bytes: Int): ByteArray {
            val samples = bytes / 2
            if (samples < 2) return input.copyOf(bytes)
            val outSamples = ((samples - 1) * 3) / 2 + 1
            val out = ByteArray(outSamples * 2)
            fun read(idx: Int): Int {
                val i = idx * 2
                return (((input[i + 1].toInt() and 255) shl 8) or (input[i].toInt() and 255)).toShort().toInt()
            }
            for (j in 0 until outSamples) {
                val src = j * (2.0 / 3.0)
                val i0 = src.toInt().coerceAtMost(samples - 1)
                val i1 = (i0 + 1).coerceAtMost(samples - 1)
                val v = (read(i0) + (read(i1) - read(i0)) * (src - i0)).toInt().coerceIn(-32768, 32767)
                out[j * 2] = (v and 255).toByte()
                out[j * 2 + 1] = ((v shr 8) and 255).toByte()
            }
            return out
        }

        private fun setState(s: String) {
            scope.launch(Dispatchers.Main) { listener.onState(s) }
        }

        private fun cleanup() {
            isRunning = false
            assistantSpeaking = false
            bargeInPending = false
            runCatching { echoCanceler?.release() }; echoCanceler = null
            runCatching { noiseSuppressor?.release() }; noiseSuppressor = null
            runCatching { recorder?.stop(); recorder?.release() }; recorder = null
            runCatching { output?.pause(); output?.flush(); output?.stop(); output?.release() }; output = null
            runCatching { ws?.close(1000, "done") }; ws = null
            val am = audioManager
            if (am != null) {
                focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { am.clearCommunicationDevice() }
                else {
                    @Suppress("DEPRECATION")
                    runCatching { am.stopBluetoothSco(); am.isBluetoothScoOn = false }
                }
                runCatching { am.mode = previousMode }
            }
            audioManager = null
            focusRequest = null
            commDevice = null
            bridge = null
            scope.cancel()
        }
    }
}
