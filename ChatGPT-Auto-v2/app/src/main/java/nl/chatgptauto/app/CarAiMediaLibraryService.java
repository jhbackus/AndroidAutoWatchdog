package nl.chatgptauto.app;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Android Auto discovery and playback entry point. The media item is a tiny
 * silent looping WAV; Android Auto therefore gets a valid playable selection,
 * while play/pause controls the actual CAR AI voice engine behind it.
 *
 * The playable item carries the octopus as embedded FRONT_COVER artwork. The
 * browse-root intentionally has no artwork, so Android Auto does not render a
 * small/cropped thumbnail on the left and instead uses the full image in the
 * now-playing album-art area on the right.
 *
 * A forwarding player deliberately reports no meaningful duration/position,
 * so Android Auto does not animate a music-style progress bar for the voice
 * assistant.
 */
public final class CarAiMediaLibraryService extends MediaLibraryService {
    private static final String ROOT_ID = "car_ai_root";
    private static final String VOICE_ITEM_ID = "car_ai_voice";
    private static final String SILENCE_DATA_URI =
            "data:audio/wav;base64,UklGRmQBAABXQVZFZm10IBAAAAABAAEAQB8AAIA+AAACABAAZGF0YUABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==";

    private ExoPlayer player;
    private Player displayPlayer;
    private MediaLibrarySession librarySession;
    private volatile String voiceState = "Tik om CAR AI te starten";
    private volatile String question = "Tik op Play en begin te praten";
    private volatile String answer = "CAR AI staat klaar om te luisteren";
    private boolean previousPlayWhenReady = false;
    private byte[] cachedArtwork;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private byte[] artworkBytes() {
        if (cachedArtwork != null) return cachedArtwork;
        try (InputStream in = getResources().openRawResource(R.drawable.car_ai_logo);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            cachedArtwork = out.toByteArray();
            return cachedArtwork;
        } catch (Exception ignored) {
            return null;
        }
    }

    private MediaItem rootItem() {
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
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .setTitle("CAR AI")
                .setSubtitle(voiceState)
                .setArtist("Jij: " + question + "   •   CAR AI: " + answer)
                .setAlbumTitle("CAR AI: " + answer)
                .setDescription("Jij: " + question + "\nCAR AI: " + answer)
                .setIsBrowsable(false)
                .setIsPlayable(true);

        byte[] artwork = artworkBytes();
        if (artwork != null && artwork.length > 0) {
            metadata.setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER);
        }

        MediaItem.Builder b = new MediaItem.Builder()
                .setMediaId(VOICE_ITEM_ID)
                .setMediaMetadata(metadata.build());
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

        displayPlayer = new ForwardingPlayer(player) {
            @Override public long getCurrentPosition() { return 0L; }
            @Override public long getBufferedPosition() { return 0L; }
            @Override public long getDuration() { return C.TIME_UNSET; }
            @Override public long getContentPosition() { return 0L; }
            @Override public long getContentBufferedPosition() { return 0L; }
            @Override public long getContentDuration() { return C.TIME_UNSET; }
        };

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

        librarySession = new MediaLibrarySession.Builder(this, displayPlayer, callback)
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
            @Override public void onConversation(String spokenQuestion, String spokenAnswer) {
                question = spokenQuestion;
                answer = spokenAnswer;
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
        MediaVoiceController.pause();
        voiceState = "Gepauzeerd — druk op Play om te praten";
        notifyVoiceChanged();
    }

    private void notifyVoiceChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::notifyVoiceChanged);
            return;
        }
        if (librarySession != null) {
            librarySession.notifyChildrenChanged(ROOT_ID, 1, null);
        }
        if (player != null && player.getMediaItemCount() > 0) {
            boolean play = player.getPlayWhenReady();
            player.replaceMediaItem(0, voiceItem(true));
            player.setPlayWhenReady(play);
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
        displayPlayer = null;
        if (player != null) {
            player.release();
            player = null;
        }
        cachedArtwork = null;
        super.onDestroy();
    }
}
