package top.rootu.dddplayer.compat;

import android.net.Uri;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.okhttp.OkHttpDataSource;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Collections;
import java.util.Map;

import okhttp3.OkHttpClient;
import top.rootu.dddplayer.player.NativePlaybackBackend;
import top.rootu.dddplayer.player.PlaybackBackend;

/**
 * Media3 Player facade over the preserved DDD software HEVC pipeline.
 *
 * This is deliberately narrow: it exists only after Just+/MediaCodec has failed
 * on video/hevc. The UI remains a Media3/Just+ PlayerView while compressed HEVC
 * is demuxed/decoded by DDD/libavcodec.
 */
@androidx.media3.common.util.UnstableApi
public final class LegacyHevcPlayer extends SimpleBasePlayer {

    private static final Player.Commands COMMANDS = new Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_PREPARE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_BACK)
            .add(Player.COMMAND_SEEK_FORWARD)
            .add(Player.COMMAND_SET_SPEED_AND_PITCH)
            .add(Player.COMMAND_SET_VIDEO_SURFACE)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_RELEASE)
            .build();

    private final NativePlaybackBackend backend;
    private final MediaItem mediaItem;
    private final Uri uri;
    private final Map<String, String> headers;
    private final long initialPositionMs;

    private boolean prepared;
    private boolean playWhenReady;
    private boolean ended;
    private boolean buffering;
    private long positionMs;
    private long durationMs = C.TIME_UNSET;
    private long bufferedPositionMs;
    private VideoSize videoSize = VideoSize.UNKNOWN;
    private PlaybackParameters playbackParameters = PlaybackParameters.DEFAULT;
    private boolean released;

    public LegacyHevcPlayer(
            Looper looper,
            Uri uri,
            @Nullable Map<String, String> headers,
            long initialPositionMs,
            @Nullable String title) {
        super(looper);
        this.uri = uri;
        this.headers = headers == null ? Collections.emptyMap() : headers;
        this.initialPositionMs = Math.max(0L, initialPositionMs);
        this.positionMs = this.initialPositionMs;
        this.mediaItem = new MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(title)
                        .build())
                .build();

        OkHttpDataSource.Factory factory =
                new OkHttpDataSource.Factory(new OkHttpClient.Builder().build());
        backend = new NativePlaybackBackend(factory);
        backend.setListener(new PlaybackBackend.Listener() {
            @Override
            public void onBuffering() {
                buffering = true;
                ended = false;
                invalidateState();
            }

            @Override
            public void onPlaying() {
                buffering = false;
                ended = false;
                playWhenReady = true;
                invalidateState();
            }

            @Override
            public void onPaused() {
                buffering = false;
                playWhenReady = false;
                invalidateState();
            }

            @Override
            public void onEnded() {
                buffering = false;
                ended = true;
                playWhenReady = false;
                invalidateState();
            }

            @Override
            public void onError(Throwable error) {
                buffering = false;
                ended = false;
                playWhenReady = false;
                invalidateState();
            }

            @Override
            public void onPositionChanged(long position, long duration) {
                positionMs = Math.max(0L, position);
                if (duration > 0L) {
                    durationMs = duration;
                }
                bufferedPositionMs = Math.max(positionMs, backend.getBufferedPositionMs());
                invalidateState();
            }

            @Override
            public void onVideoSizeChanged(int width, int height, float pixelWidthHeightRatio) {
                if (width > 0 && height > 0) {
                    videoSize = new VideoSize(width, height, 0, pixelWidthHeightRatio);
                }
                invalidateState();
            }

            @Override
            public void onSubtitleTextChanged(String text) {
                // Text subtitle bridging is intentionally deferred. The fallback
                // is kept narrow until the video path is stable.
            }
        });
    }

    @Override
    protected State getState() {
        long safeDurationMs = durationMs > 0L ? durationMs : C.TIME_UNSET;
        long safeDurationUs =
                safeDurationMs == C.TIME_UNSET ? C.TIME_UNSET : safeDurationMs * 1000L;

        MediaItemData item = new MediaItemData.Builder("legacy-hevc")
                .setMediaItem(mediaItem)
                .setIsSeekable(true)
                .setDurationUs(safeDurationUs)
                .build();

        int playbackState;
        if (ended) {
            playbackState = Player.STATE_ENDED;
        } else if (!prepared) {
            playbackState = Player.STATE_IDLE;
        } else if (buffering) {
            playbackState = Player.STATE_BUFFERING;
        } else {
            playbackState = Player.STATE_READY;
        }

        return new State.Builder()
                .setAvailableCommands(COMMANDS)
                .setPlaylist(ImmutableList.of(item))
                .setCurrentMediaItemIndex(0)
                .setPlayWhenReady(
                        playWhenReady,
                        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(playbackState)
                .setIsLoading(buffering)
                .setContentPositionMs(positionMs)
                .setContentBufferedPositionMs(
                        PositionSupplier.getConstant(Math.max(positionMs, bufferedPositionMs)))
                .setVideoSize(videoSize)
                .setPlaybackParameters(playbackParameters)
                .build();
    }

    @Override
    protected ListenableFuture<?> handlePrepare() {
        if (!prepared) {
            prepared = true;
            buffering = true;
            ended = false;
            backend.prepareSoftware(uri, headers, initialPositionMs);
            if (!playWhenReady) {
                backend.pause();
            }
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean value) {
        playWhenReady = value;
        if (value) {
            backend.play();
        } else {
            backend.pause();
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        backend.stop();
        prepared = false;
        buffering = false;
        ended = false;
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleRelease() {
        if (!released) {
            released = true;
            backend.release();
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSeek(
            int mediaItemIndex, long position, @Player.Command int seekCommand) {
        positionMs = Math.max(0L, position);
        backend.seekTo(positionMs);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetPlaybackParameters(
            PlaybackParameters parameters) {
        playbackParameters = parameters;
        backend.setPlaybackSpeed(parameters.speed);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetVideoOutput(Object videoOutput) {
        if (videoOutput instanceof Surface) {
            backend.attachSurface((Surface) videoOutput);
        } else if (videoOutput instanceof SurfaceHolder) {
            backend.attachSurfaceHolder((SurfaceHolder) videoOutput);
        } else if (videoOutput instanceof SurfaceView) {
            backend.attachSurfaceHolder(((SurfaceView) videoOutput).getHolder());
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleClearVideoOutput(@Nullable Object videoOutput) {
        backend.attachSurface(null);
        return Futures.immediateVoidFuture();
    }
}
