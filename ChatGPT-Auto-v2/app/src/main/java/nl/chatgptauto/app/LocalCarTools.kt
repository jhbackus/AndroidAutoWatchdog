package nl.chatgptauto.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Local actions that ChatGPT Auto can execute from a recognized Dutch voice command. */
class LocalCarTools(private val context: Context) {

    data class Result(val handled: Boolean, val message: String = "", val endConversation: Boolean = false)

    fun tryHandle(raw: String): Result {
        val text = raw.trim()
        val lower = text.lowercase()

        navigationDestination(text, lower)?.let { destination ->
            return navigateWaze(destination)
        }

        callTarget(text, lower)?.let { name ->
            return callContact(name)
        }

        if (mentionsSpotify(lower)) {
            parseMediaAction(text, lower)?.let { (action, query) ->
                return controlMedia("spotify", action, query)
            }
        }

        if (mentionsRoonArc(lower)) {
            parseMediaAction(text, lower)?.let { (action, query) ->
                return controlMedia("roon_arc", action, query)
            }
        }

        if (lower in setOf("volgende", "volgend nummer", "skip", "skip dit nummer")) {
            return controlActiveMedia("next")
        }
        if (lower in setOf("vorige", "vorig nummer", "vorige nummer")) {
            return controlActiveMedia("previous")
        }
        if (lower.contains("pauzeer de muziek") || lower == "pauzeer muziek") {
            return controlActiveMedia("pause")
        }
        if (lower.contains("hervat de muziek") || lower == "hervat muziek") {
            return controlActiveMedia("play")
        }

        return Result(false)
    }

    private fun navigationDestination(original: String, lower: String): String? {
        val prefixes = listOf("navigeer naar ", "navigeren naar ", "rij naar ", "route naar ", "breng me naar ")
        val prefix = prefixes.firstOrNull { lower.startsWith(it) } ?: return null
        return original.substring(prefix.length).trim().takeIf { it.isNotBlank() }
    }

    private fun callTarget(original: String, lower: String): String? {
        val prefixes = listOf("bel ", "bel naar ", "bel even ", "telefoneer naar ")
        val prefix = prefixes.firstOrNull { lower.startsWith(it) } ?: return null
        return original.substring(prefix.length).trim().takeIf { it.isNotBlank() }
    }

    private fun mentionsSpotify(text: String) = text.contains("spotify")
    private fun mentionsRoonArc(text: String) = text.contains("roon arc") || text.contains("roonarc") || text.contains("arc")

    private fun parseMediaAction(original: String, lower: String): Pair<String, String?>? {
        if (lower.contains("volgend") || lower.contains("skip")) return "next" to null
        if (lower.contains("vorig")) return "previous" to null
        if (lower.contains("pauze")) return "pause" to null
        if (lower.contains("stop")) return "stop" to null
        if (lower.contains("hervat") || lower.contains("ga verder")) return "play" to null
        if (lower.contains("open spotify") || lower.contains("open roon")) return "open" to null

        val playMarkers = listOf("speel ", "zet ")
        val marker = playMarkers.firstOrNull { lower.startsWith(it) }
        if (marker != null) {
            var query = original.substring(marker.length).trim()
            query = query.replace(Regex("(?i)\\s+(op|via|in)\\s+spotify.*$"), "")
            query = query.replace(Regex("(?i)\\s+(op|via|in)\\s+roon\\s*arc.*$"), "")
            query = query.replace(Regex("(?i)^spotify\\s+"), "")
            query = query.replace(Regex("(?i)^roon\\s*arc\\s+"), "")
            return "search_play" to query.trim().takeIf { it.isNotBlank() }
        }
        if (lower.contains("zet spotify aan") || lower.contains("start spotify") ||
            lower.contains("zet roon arc aan") || lower.contains("start roon arc")) {
            return "play" to null
        }
        return null
    }

    private fun navigateWaze(destination: String): Result {
        val encoded = URLEncoder.encode(destination, StandardCharsets.UTF_8.toString()).replace("+", "%20")
        val uri = Uri.parse("https://waze.com/ul?q=$encoded&navigate=yes&utm_source=chatgpt_auto")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.waze")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            Result(true, "Navigatie naar $destination gestart in Waze.", true)
        }.getOrElse {
            Result(true, "Waze kon niet worden geopend. Controleer of Waze is geïnstalleerd.")
        }
    }

    private fun callContact(name: String): Result {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return Result(true, "Geef ChatGPT Auto eerst toegang tot je contacten in de telefooninstellingen.")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return Result(true, "Geef ChatGPT Auto eerst toestemming om telefoongesprekken te starten.")
        }

        val columns = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val matches = mutableListOf<Pair<String, String>>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            columns,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { c ->
            val nameIx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext() && matches.size < 8) {
                matches += c.getString(nameIx) to c.getString(numberIx)
            }
        }

        if (matches.isEmpty()) return Result(true, "Ik kon geen contact vinden met de naam $name.")
        val exact = matches.firstOrNull { it.first.equals(name, ignoreCase = true) }
        val chosen = exact ?: if (matches.size == 1) matches.first() else null
        if (chosen == null) {
            val names = matches.map { it.first }.distinct().take(4).joinToString(", ")
            return Result(true, "Ik vond meerdere contacten: $names. Noem de volledige naam.")
        }

        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(chosen.second)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            Result(true, "Ik bel ${chosen.first}.", true)
        }.getOrElse {
            Result(true, "Het telefoongesprek met ${chosen.first} kon niet worden gestart.")
        }
    }

    private fun controlMedia(app: String, action: String, query: String?): Result {
        val packageName = when (app) {
            "spotify" -> "com.spotify.music"
            "roon_arc" -> "com.roon.onthego"
            else -> return Result(true, "Onbekende media-app.")
        }
        val label = if (app == "spotify") "Spotify" else "Roon ARC"
        val controller = mediaControllers().firstOrNull { it.packageName == packageName }

        if (controller == null) {
            if (action == "open" || action == "play" || action == "search_play") {
                val launch = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launch != null) {
                    context.startActivity(launch)
                    return Result(true, "$label geopend. Start daar zo nodig eerst een afspeelsessie.", true)
                }
            }
            return Result(true, "$label heeft nog geen actieve mediasessie. Open de app eerst of geef ChatGPT Auto mediatoegang.")
        }

        val controls = controller.transportControls
        when (action) {
            "play" -> controls.play()
            "pause" -> controls.pause()
            "stop" -> controls.stop()
            "next" -> controls.skipToNext()
            "previous" -> controls.skipToPrevious()
            "search_play" -> {
                if (query.isNullOrBlank()) controls.play() else controls.playFromSearch(query, null)
            }
            "open" -> context.packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
            }
        }
        val what = when (action) {
            "play" -> "afspelen"
            "pause" -> "pauzeren"
            "stop" -> "stoppen"
            "next" -> "volgend nummer"
            "previous" -> "vorig nummer"
            "search_play" -> if (query.isNullOrBlank()) "afspelen" else "‘$query’ afspelen"
            else -> "openen"
        }
        return Result(true, "$label: $what.", endConversation = action in setOf("play", "search_play", "open"))
    }

    private fun controlActiveMedia(action: String): Result {
        val controller = mediaControllers().firstOrNull {
            it.packageName != context.packageName
        } ?: return Result(true, "Ik zie geen actieve Spotify- of Roon ARC-mediasessie.")
        val controls = controller.transportControls
        when (action) {
            "play" -> controls.play()
            "pause" -> controls.pause()
            "next" -> controls.skipToNext()
            "previous" -> controls.skipToPrevious()
        }
        return Result(true, "Uitgevoerd.", endConversation = action == "play")
    }

    private fun mediaControllers(): List<MediaController> {
        return runCatching {
            val manager = context.getSystemService(MediaSessionManager::class.java)
            val listener = ComponentName(context, MediaAccessService::class.java)
            manager?.getActiveSessions(listener).orEmpty()
        }.getOrDefault(emptyList())
    }
}
