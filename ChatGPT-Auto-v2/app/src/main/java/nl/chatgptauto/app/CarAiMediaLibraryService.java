package nl.chatgptauto.app;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Android Auto discovery and playback entry point. The media item is a tiny
 * silent looping WAV; Android Auto therefore gets a valid playable selection,
 * while play/pause controls the actual CAR AI voice engine behind it.
 */
public final class CarAiMediaLibraryService extends MediaLibraryService {
    private static final String ROOT_ID = "car_ai_root";
    private static final String VOICE_ITEM_ID = "car_ai_voice";
    private static final String SILENCE_DATA_URI =
            "data:audio/wav;base64,UklGRmQBAABXQVZFZm10IBAAAAABAAEAQB8AAIA+AAACABAAZGF0YUABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==";

    private ExoPlayer player;
    private MediaLibrarySession librarySession;
    private volatile String voiceState = "Tik om CAR AI te starten";
    private boolean previousPlayWhenReady = false;

    private static MediaItem rootItem() {
        return new MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle("CAR AI")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build())
                .build();
    }

    private MediaItem voiceItem(boolean playableUri) {
        MediaItem.Builder b = new MediaItem.Builder()
                .setMediaId(VOICE_ITEM_ID)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle("CAR AI")
                        .setSubtitle(voiceState)
                        .setArtist("Voice assistant")
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build());
        if (playableUri) b.setUri(Uri.parse(SILENCE_DATA_URI));
        return b.build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        player = new ExoPlayer.Builder(this).build();
        player.setVolume(0f);
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                if (playWhenReady == previousPlayWhenReady) return;
                previousPlayWhenReady = playWhenReady;
                if (playWhenReady) startVoice(); else stopVoice();
            }
        });

        MediaLibrarySession.Callback callback = new MediaLibrarySession.Callback() {
            @Override
            public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(
                    MediaLibrarySession session,
                    MediaSession.ControllerInfo browser,
                    @Nullable LibraryParams params) {
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params));
            }

            @Override
            public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(
                    MediaLibrarySession session,
                    MediaSession.ControllerInfo browser,
                    String parentId,
                    int page,
                    int pageSize,
                    @Nullable LibraryParams params) {
                if (ROOT_ID.equals(parentId)) {
                    return Futures.immediateFuture(
                            LibraryResult.ofItemList(Collections.singletonList(voiceItem(false)), params));
                }
                return Futures.immediateFuture(
                        LibraryResult.ofItemList(Collections.emptyList(), params));
            }

            @Override
            public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
                    MediaLibrarySession session,
                    MediaSession.ControllerInfo browser,
                    String mediaId) {
                MediaItem item = ROOT_ID.equals(mediaId) ? rootItem() : voiceItem(false);
                return Futures.immediateFuture(LibraryResult.ofItem(item, null));
            }

            @Override
            public ListenableFuture<List<MediaItem>> onAddMediaItems(
                    MediaSession session,
                    MediaSession.ControllerInfo controller,
                    List<MediaItem> mediaItems) {
                List<MediaItem> resolved = new ArrayList<>();
                for (MediaItem item : mediaItems) {
                    if (VOICE_ITEM_ID.equals(item.mediaId)) resolved.add(voiceItem(true));
                    else resolved.add(item);
                }
                return Futures.immediateFuture(resolved);
            }
        };

        librarySession = new MediaLibrarySession.Builder(this, player, callback)
                .setId("car_ai_media")
                .build();
    }

    private void startVoice() {
        voiceState = "Verbinden…";
        notifyVoiceChanged();
        MediaVoiceController.start(this, new MediaVoiceController.Listener() {
            @Override public void onState(String state) {
                voiceState = state;
                notifyVoiceChanged();
            }
            @Override public void onRunningChanged(boolean running) {
                if (!running) {
                    voiceState = "Gepauzeerd — druk op Play om te praten";
                    notifyVoiceChanged();
                    if (player != null && player.getPlayWhenReady()) player.pause();
                }
            }
        });
    }

    private void stopVoice() {
        MediaVoiceController.stop();
        voiceState = "Gepauzeerd — druk op Play om te praten";
        notifyVoiceChanged();
    }

    private void notifyVoiceChanged() {
        if (librarySession != null) {
            librarySession.notifyChildrenChanged(ROOT_ID, 1, null);
        }
    }

    @Nullable
    @Override
    public MediaLibrarySession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return librarySession;
    }

    @Override
    public void onDestroy() {
        MediaVoiceController.stop();
        if (librarySession != null) {
            librarySession.release();
            librarySession = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
