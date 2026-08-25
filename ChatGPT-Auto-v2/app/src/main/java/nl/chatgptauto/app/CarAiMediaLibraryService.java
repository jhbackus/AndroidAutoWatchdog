package nl.chatgptauto.app;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Collections;

/**
 * Android Auto media discovery entry point using Media3.
 * Media3 1.11+ provides the current Android 16/17 browsing compatibility layer.
 */
public final class CarAiMediaLibraryService extends MediaLibraryService {
    private static final String ROOT_ID = "car_ai_root";
    private static final String VOICE_ITEM_ID = "car_ai_voice";

    private ExoPlayer player;
    private MediaLibrarySession librarySession;

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

    private static MediaItem voiceItem() {
        return new MediaItem.Builder()
                .setMediaId(VOICE_ITEM_ID)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle("CAR AI")
                        .setSubtitle("Voice assistant")
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build())
                .build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        player = new ExoPlayer.Builder(this).build();

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
                            LibraryResult.ofItemList(Collections.singletonList(voiceItem()), params));
                }
                return Futures.immediateFuture(
                        LibraryResult.ofItemList(Collections.emptyList(), params));
            }

            @Override
            public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
                    MediaLibrarySession session,
                    MediaSession.ControllerInfo browser,
                    String mediaId) {
                MediaItem item = ROOT_ID.equals(mediaId) ? rootItem() : voiceItem();
                return Futures.immediateFuture(LibraryResult.ofItem(item, null));
            }
        };

        librarySession = new MediaLibrarySession.Builder(this, player, callback)
                .setId("car_ai_media")
                .build();
    }

    @Nullable
    @Override
    public MediaLibrarySession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return librarySession;
    }

    @Override
    public void onDestroy() {
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
