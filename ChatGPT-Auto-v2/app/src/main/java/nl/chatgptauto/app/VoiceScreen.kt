package nl.chatgptauto.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.core.graphics.drawable.IconCompat

class VoiceScreen(carContext: CarContext) : Screen(carContext) {
    private var state = "Start CAR AI en praat daarna handsfree."
    private var bridge: VoiceBridge? = null

    override fun onGetTemplate(): Template {
        val running = bridge?.isRunning == true
        val action = Action.Builder()
            .setTitle(if (running) "Stop" else "Start")
            .setIcon(
                CarIcon.Builder(
                    IconCompat.createWithResource(
                        carContext,
                        if (running) android.R.drawable.ic_media_pause else android.R.drawable.ic_btn_speak_now
                    )
                ).build()
            )
            .setOnClickListener { if (running) stopVoice() else startVoice() }
            .build()

        return MessageTemplate.Builder(state)
            .setTitle("CAR AI")
            .setActionStrip(ActionStrip.Builder().addAction(action).build())
            .build()
    }

    private fun startVoice() {
        if (carContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state = "Geef eerst microfoontoestemming in de telefoon-app."
            invalidate()
            return
        }

        val prefs = carContext.getSharedPreferences("chatgpt_auto", Context.MODE_PRIVATE)
        val brokerUrl = prefs.getString("broker_url", null)?.trim()
        val brokerToken = prefs.getString("broker_token", null)?.trim()
        val voice = prefs.getString("voice", "marin") ?: "marin"

        if (brokerUrl.isNullOrBlank() || !(brokerUrl.startsWith("wss://") || brokerUrl.startsWith("ws://"))) {
            state = "Stel eerst de WebSocket broker-URL in op je telefoon."
            invalidate()
            return
        }

        if (bridge?.isRunning == true) return
        state = "Verbinden…"
        invalidate()
        bridge = VoiceBridge(
            carContext = carContext,
            brokerUrl = brokerUrl,
            brokerToken = brokerToken,
            voice = voice,
            onState = { msg -> state = msg; invalidate() },
            onDone = {
                state = "Gesprek gestopt. Tik op Start voor een nieuw gesprek."
                invalidate()
            }
        ).also { it.start() }
    }

    private fun stopVoice() {
        state = "Gesprek stoppen…"
        invalidate()
        bridge?.stop()
    }
}
