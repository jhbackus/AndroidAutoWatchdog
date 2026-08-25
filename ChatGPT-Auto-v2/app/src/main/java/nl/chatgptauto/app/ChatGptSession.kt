package nl.chatgptauto.app

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class ChatGptSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = VoiceScreen(carContext)
}
