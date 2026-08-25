package nl.chatgptauto.app

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Long-lived hands-free Realtime session for Android Auto.
 *
 * Audio path:
 * BMW/Android Auto microphone: PCM16 mono 16 kHz (CarAudioRecord)
 * -> linear resample to PCM16 mono 24 kHz
 * -> secure WebSocket broker
 * -> OpenAI Realtime
 * -> PCM16 mono 24 kHz
 * -> AudioTrack / car speakers
 */
class VoiceBridge(
    private val carContext: CarContext,
    private val brokerUrl: String,
    private val brokerToken: String?,
    private val voice: String,
    private val onState: (String) -> Unit,
    private val onDone: () -> Unit
) {
    @Volatile var isRunning = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val finishing = AtomicBoolean(false)
    private var ws: WebSocket? = null
    private var recorder: CarAudioRecord? = null
    private var player: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        finishing.set(false)
        scope.launch {
            try {
                connectAndRun()
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    onState("Fout: ${t.message ?: "onbekend"}")
                }
            } finally {
                cleanup()
                withContext(Dispatchers.Main) { onDone() }
            }
        }
    }

    fun stop() {
        if (!isRunning || !finishing.compareAndSet(false, true)) return
        isRunning = false
        runCatching { recorder?.stopRecording() }
        runCatching { ws?.close(1000, "user stopped") }
    }

    private suspend fun connectAndRun() = suspendCancellableCoroutine<Unit> { cont ->
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        val req = Request.Builder().url(brokerUrl)
        if (!brokerToken.isNullOrBlank()) req.header("Authorization", "Bearer $brokerToken")

        ws = client.newWebSocket(req.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(sessionUpdate().toString())
                scope.launch {
                    try {
                        recordLoop(webSocket)
                    } catch (t: Throwable) {
                        if (cont.isActive) cont.resumeWith(Result.failure(t))
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("type")) {
                    "session.created", "session.updated" -> setState("Ik luister…")
                    "input_audio_buffer.speech_started" -> setState("Ik luister…")
                    "input_audio_buffer.speech_stopped" -> setState("Even denken…")
                    "response.created" -> setState("ChatGPT antwoordt…")
                    "response.output_audio.delta", "response.audio.delta" -> {
                        val delta = json.optString("delta")
                        if (delta.isNotEmpty()) {
                            runCatching { playPcm(Base64.decode(delta, Base64.DEFAULT)) }
                        }
                    }
                    "response.done" -> if (isRunning) setState("Ik luister…")
                    "error" -> {
                        val msg = json.optJSONObject("error")?.optString("message")
                            ?: "OpenAI Realtime-fout"
                        if (cont.isActive) cont.resumeWith(Result.failure(Exception(msg)))
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
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

    private fun sessionUpdate(): JSONObject {
        val input = JSONObject()
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000))
            .put("noise_reduction", JSONObject().put("type", "far_field"))
            .put("transcription", JSONObject()
                .put("model", "gpt-4o-mini-transcribe")
                .put("language", "nl")
                .put("prompt", "Nederlands gesproken in een auto; verwacht Nederlandse eigennamen en plaatsnamen."))
            .put("turn_detection", JSONObject()
                .put("type", "server_vad")
                .put("threshold", 0.50)
                .put("prefix_padding_ms", 300)
                .put("silence_duration_ms", 650)
                .put("create_response", true)
                .put("interrupt_response", true))

        val output = JSONObject()
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000))
            .put("voice", voice)
            .put("speed", 1.0)

        return JSONObject()
            .put("type", "session.update")
            .put("session", JSONObject()
                .put("type", "realtime")
                .put("model", "gpt-realtime-2.1")
                .put("instructions", "Je bent ChatGPT in de auto. Spreek standaard Nederlands. Antwoord natuurlijk, bondig en verkeersveilig: geef eerst het nuttigste antwoord en vermijd lange opsommingen tenzij daarom wordt gevraagd. De gebruiker kan je tijdens het spreken onderbreken. Vraag alleen om verduidelijking wanneer dat echt nodig is.")
                .put("output_modalities", JSONArray().put("audio"))
                .put("audio", JSONObject().put("input", input).put("output", output)))
    }

    private suspend fun recordLoop(webSocket: WebSocket) {
        val car = CarAudioRecord.create(carContext)
        recorder = car
        if (!acquireConversationAudioFocus()) {
            throw IllegalStateException("Android Auto gaf geen audiofocus voor de automicrofoon")
        }

        car.startRecording()
        setState("Ik luister…")
        val buf = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)

        while (isRunning) {
            val n = car.read(buf, 0, buf.size)
            if (n < 0) break
            if (n == 0) continue

            // Android Auto supplies mono PCM16 at 16 kHz; Realtime PCM expects 24 kHz.
            val pcm24 = resample16kTo24kPcm16(buf, n)
            val event = JSONObject()
                .put("type", "input_audio_buffer.append")
                .put("audio", Base64.encodeToString(pcm24, Base64.NO_WRAP))
            if (!webSocket.send(event.toString())) break
        }

        runCatching { car.stopRecording() }
        if (isRunning) {
            throw IllegalStateException("De automicrofoon werd door Android Auto gesloten")
        }
    }

    private fun acquireConversationAudioFocus(): Boolean {
        val manager = carContext.getSystemService(AudioManager::class.java) ?: return false
        audioManager = manager
        val attrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) stop()
            }
            .build()
        focusRequest = request
        return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val manager = audioManager
        val request = focusRequest
        if (manager != null && request != null) runCatching { manager.abandonAudioFocusRequest(request) }
        focusRequest = null
        audioManager = null
    }

    @Synchronized
    private fun playPcm(data: ByteArray) {
        if (player == null) {
            val min = AudioTrack.getMinBufferSize(
                24000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            player = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(24000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(min, 48000))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.play() }
        }
        player?.write(data, 0, data.size)
    }

    /** Linear interpolation, little-endian signed PCM16 mono. 16 kHz -> 24 kHz (3:2). */
    private fun resample16kTo24kPcm16(input: ByteArray, byteCount: Int): ByteArray {
        val samples = byteCount / 2
        if (samples < 2) return input.copyOf(byteCount)
        val outSamples = ((samples - 1) * 3) / 2 + 1
        val out = ByteArray(outSamples * 2)

        for (j in 0 until outSamples) {
            val src = j * (2.0 / 3.0)
            val i0 = src.toInt().coerceAtMost(samples - 1)
            val i1 = (i0 + 1).coerceAtMost(samples - 1)
            val frac = src - i0
            val s0 = readPcm16(input, i0)
            val s1 = readPcm16(input, i1)
            val sample = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767)
            out[j * 2] = (sample and 0xff).toByte()
            out[j * 2 + 1] = ((sample shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun readPcm16(input: ByteArray, sampleIndex: Int): Int {
        val i = sampleIndex * 2
        return (((input[i + 1].toInt() and 0xff) shl 8) or (input[i].toInt() and 0xff)).toShort().toInt()
    }

    private fun setState(value: String) {
        scope.launch(Dispatchers.Main) { onState(value) }
    }

    private fun cleanup() {
        isRunning = false
        runCatching { recorder?.stopRecording() }
        recorder = null
        runCatching { ws?.close(1000, "done") }
        ws = null
        runCatching { player?.pause(); player?.flush(); player?.stop(); player?.release() }
        player = null
        abandonAudioFocus()
        scope.cancel()
    }
}
