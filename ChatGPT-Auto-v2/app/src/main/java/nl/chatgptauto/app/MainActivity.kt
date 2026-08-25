package nl.chatgptauto.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val brokerUrl = findViewById<EditText>(R.id.brokerUrl)
        val brokerToken = findViewById<EditText>(R.id.brokerToken)
        val voice = findViewById<Spinner>(R.id.voice)
        val status = findViewById<TextView>(R.id.status)
        val prefs = getSharedPreferences("chatgpt_auto", MODE_PRIVATE)

        brokerUrl.setText(prefs.getString("broker_url", ""))
        brokerToken.setText(prefs.getString("broker_token", ""))

        val voices = arrayOf("marin", "cedar", "coral", "sage", "verse", "alloy", "ash", "ballad", "echo", "shimmer")
        voice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voices)
        val savedVoice = prefs.getString("voice", "marin") ?: "marin"
        voice.setSelection(voices.indexOf(savedVoice).coerceAtLeast(0))

        findViewById<Button>(R.id.save).setOnClickListener {
            val url = brokerUrl.text.toString().trim().trimEnd('/')
            val token = brokerToken.text.toString().trim()
            if (!(url.startsWith("wss://") || url.startsWith("ws://"))) {
                status.text = "Gebruik een ws:// of bij voorkeur wss:// WebSocket-URL."
            } else {
                prefs.edit()
                    .putString("broker_url", url)
                    .putString("broker_token", token)
                    .putString("voice", voice.selectedItem.toString())
                    .apply()
                status.text = "Opgeslagen. Verbind Android Auto en open ChatGPT Auto."
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 10)
        }
    }
}
