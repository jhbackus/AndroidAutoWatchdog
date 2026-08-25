package nl.chatgptauto.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken

class MainActivity : AppCompatActivity() {
    private var diagnosticBrowser: MediaBrowser? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val brokerUrl = findViewById<EditText>(R.id.brokerUrl)
        val brokerToken = findViewById<EditText>(R.id.brokerToken)
        val voice = findViewById<Spinner>(R.id.voice)
        val status = findViewById<TextView>(R.id.status)
        val diagnostic = findViewById<TextView>(R.id.diagnostic)
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
            "Verse",
            "Cedar",
            "Alloy",
            "Ash",
            "Ballad",
            "Echo"
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
                status.text = "Opgeslagen. Verbind Android Auto en open CAR AI."
            }
        }

        findViewById<Button>(R.id.mediaAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.runDiagnostic).setOnClickListener {
            runAndroidAutoDiagnostic(diagnostic)
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

    private fun runAndroidAutoDiagnostic(output: TextView) {
        val lines = mutableListOf<String>()
        fun publish(extra: String? = null) {
            if (extra != null) lines += extra
            output.text = lines.joinToString("\n")
        }

        lines += "CAR AI Android Auto diagnose"
        lines += "Package: $packageName"
        lines += "Versie: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        lines += "Broker URL ingebouwd: ${BuildConfig.BROKER_URL.isNotBlank()}"
        lines += "Broker-token ingebouwd: ${BuildConfig.BROKER_TOKEN.isNotBlank()}"

        val component = ComponentName(this, CarAiMediaLibraryService::class.java)
        try {
            val si = packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
            lines += "✓ MediaLibraryService geregistreerd"
            lines += "✓ Service exported: ${si.exported}"
        } catch (t: Throwable) {
            lines += "✗ MediaLibraryService NIET gevonden: ${t.javaClass.simpleName}"
        }

        val browseIntent = Intent("android.media.browse.MediaBrowserService").setPackage(packageName)
        val resolved = packageManager.queryIntentServices(browseIntent, PackageManager.MATCH_ALL)
        if (resolved.any { it.serviceInfo?.name?.contains("CarAiMediaLibraryService") == true }) {
            lines += "✓ MediaBrowserService-action resolveert"
        } else {
            lines += "✗ MediaBrowserService-action resolveert NIET"
        }

        try {
            val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val carDesc = appInfo.metaData?.getInt("com.google.android.gms.car.application", 0) ?: 0
            if (carDesc != 0) lines += "✓ Android Auto automotive_app_desc meta-data aanwezig"
            else lines += "✗ Android Auto automotive_app_desc meta-data ontbreekt"
        } catch (t: Throwable) {
            lines += "✗ App meta-data niet leesbaar: ${t.javaClass.simpleName}"
        }
        publish()

        diagnosticBrowser?.release()
        diagnosticBrowser = null
        try {
            val sessionToken = SessionToken(this, component)
            val browserFuture = MediaBrowser.Builder(this, sessionToken).buildAsync()
            browserFuture.addListener({
                try {
                    val browser = browserFuture.get()
                    diagnosticBrowser = browser
                    publish("✓ Media3 MediaBrowser verbonden")
                    val rootFuture = browser.getLibraryRoot(null)
                    rootFuture.addListener({
                        try {
                            val result = rootFuture.get()
                            val root = result.value
                            if (root != null && root.mediaId.isNotBlank()) {
                                publish("✓ Media-root ontvangen: ${root.mediaId}")
                                val childFuture = browser.getChildren(root.mediaId, 0, 20, null)
                                childFuture.addListener({
                                    try {
                                        val children = childFuture.get().value
                                        if (!children.isNullOrEmpty()) {
                                            publish("✓ Browse-content ontvangen: ${children.size} item(s)")
                                            publish("DIAGNOSE: media discovery werkt lokaal volledig.")
                                        } else {
                                            publish("✗ Root heeft geen browse-content")
                                        }
                                    } catch (t: Throwable) {
                                        publish("✗ Browse-content fout: ${t.javaClass.simpleName}: ${t.message}")
                                    }
                                }, ContextCompat.getMainExecutor(this))
                            } else {
                                publish("✗ Media-root leeg of ongeldig")
                            }
                        } catch (t: Throwable) {
                            publish("✗ Media-root fout: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }, ContextCompat.getMainExecutor(this))
                } catch (t: Throwable) {
                    publish("✗ MediaBrowser verbinding mislukt: ${t.javaClass.simpleName}: ${t.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (t: Throwable) {
            publish("✗ MediaBrowser kon niet starten: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun onDestroy() {
        diagnosticBrowser?.release()
        diagnosticBrowser = null
        super.onDestroy()
    }
}
