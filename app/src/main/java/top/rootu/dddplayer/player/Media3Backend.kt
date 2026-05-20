package top.rootu.dddplayer.player

import android.net.Uri
import android.view.SurfaceHolder
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class Media3Backend(private val exoPlayer: ExoPlayer) : PlaybackBackend {
    private var listener: PlaybackBackend.Listener? = null
    private val internalListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> listener?.onBuffering()
                Player.STATE_READY -> if (exoPlayer.isPlaying) listener?.onPlaying() else listener?.onPaused()
                Player.STATE_ENDED -> listener?.onEnded()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) listener?.onPlaying() else listener?.onPaused()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            listener?.onError(error)
        }
    }

    init { exoPlayer.addListener(internalListener) }

    override fun attachSurfaceHolder(surfaceHolder: SurfaceHolder?) = exoPlayer.setVideoSurfaceHolder(surfaceHolder)

    override fun prepare(uri: Uri, headers: Map<String, String>, startPositionMs: Long) {
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        if (startPositionMs > 0) exoPlayer.seekTo(startPositionMs)
        exoPlayer.prepare()
    }

    override fun play() = exoPlayer.play()
    override fun pause() = exoPlayer.pause()
    override fun seekTo(positionMs: Long) = exoPlayer.seekTo(positionMs)
    override fun stop() = exoPlayer.stop()
    override fun release() { exoPlayer.removeListener(internalListener) }
    override fun getPositionMs(): Long = exoPlayer.currentPosition
    override fun getDurationMs(): Long = exoPlayer.duration
    override fun isPlaying(): Boolean = exoPlayer.isPlaying
    override fun getBufferedPositionMs(): Long = exoPlayer.bufferedPosition
    override fun getBufferedPercentage(): Int = exoPlayer.bufferedPercentage
    override fun setListener(listener: PlaybackBackend.Listener?) { this.listener = listener }
}
