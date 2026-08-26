package nl.chatgptauto.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
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

        if (!prefs.contains("broker_url")) {
            prefs.edit().putString("broker_url", BuildConfig.BROKER_URL).apply()
        }
        if (!prefs.contains("broker_token") && BuildConfig.BROKER_TOKEN.isNotBlank()) {
            prefs.edit().putString("broker_token", BuildConfig.BROKER_TOKEN).apply()
        }

        brokerUrl.setText(prefs.getString("broker_url", BuildConfig.BROKER_URL))
        brokerToken.setText(prefs.getString("broker_token", BuildConfig.BROKER_TOKEN))

        val voiceLabels = arrayOf(
            "Marin — vrouwelijk klinkend",
            "Coral — vrouwelijk klinkend",
            "Shimmer — vrouwelijk klinkend",
            "Sage — vrouwelijk klinkend",
            "Verse", "Cedar", "Alloy", "Ash", "Ballad", "Echo"
        )
        val voiceIds = arrayOf("marin", "coral", "shimmer", "sage", "verse", "cedar", "alloy", "ash", "ballad", "echo")
        voice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceLabels)
        val savedVoice = prefs.getString("voice", "marin") ?: "marin"
        voice.setSelection(voiceIds.indexOf(savedVoice).coerceAtLeast(0))

        findViewById<Button>(R.id.save).setOnClickListener {
            val url = brokerUrl.text.toString().trim().trimEnd('/')
            val token = brokerToken.text.toString().trim()
            if (!(url.startsWith("wss://") || url.startsWith("ws://"))) {
                status.text = "Gebruik een ws:// of bij voorkeur wss:// WebSocket-URL."
            } else {
                prefs.edit()
                    .putString("broker_url", url)
                    .putString("broker_token", token)
                    .putString("voice", voiceIds[voice.selectedItemPosition])
                    .apply()
                status.text = "Opgeslagen. Open CAR AI in Android Auto en tik op Start."
            }
        }

        findViewById<Button>(R.id.mediaAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        val needed = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 10)
        }
    }
}
