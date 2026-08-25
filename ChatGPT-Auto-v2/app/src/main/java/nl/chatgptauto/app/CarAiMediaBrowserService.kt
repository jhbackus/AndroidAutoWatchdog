package nl.chatgptauto.app

import android.os.Bundle
import androidx.media.MediaBrowserServiceCompat
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/**
 * Legacy Android Auto media entry point. This is deliberately small: its primary
 * purpose is to make CAR AI discoverable in Android Auto as a regular media app.
 * The existing Car App Library service remains available for the voice/microphone path.
 */
class CarAiMediaBrowserService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()

        mediaSession = MediaSessionCompat(this, "CAR AI").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "CAR AI")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Voice assistant")
                    .build()
            )
            setPlaybackState(basePlaybackState(PlaybackStateCompat.STATE_PAUSED))
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    setPlaybackState(basePlaybackState(PlaybackStateCompat.STATE_PLAYING))
                }

                override fun onPause() {
                    setPlaybackState(basePlaybackState(PlaybackStateCompat.STATE_PAUSED))
                }

                override fun onStop() {
                    setPlaybackState(basePlaybackState(PlaybackStateCompat.STATE_STOPPED))
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    onPlay()
                }
            })
            isActive = true
        }

        sessionToken = mediaSession.sessionToken
    }

    override fun onDestroy() {
        mediaSession.release()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot = BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        if (parentId != ROOT_ID) {
            result.sendResult(mutableListOf())
            return
        }

        val item = MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(VOICE_ITEM_ID)
                .setTitle("CAR AI")
                .setSubtitle("Open CAR AI voice assistant")
                .build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
        result.sendResult(mutableListOf(item))
    }

    private fun basePlaybackState(state: Int): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build()

    companion object {
        private const val ROOT_ID = "car_ai_root"
        private const val VOICE_ITEM_ID = "car_ai_voice"
    }
}
