package top.rootu.dddplayer.player

import android.net.Uri
import android.view.SurfaceHolder
import androidx.media3.exoplayer.ExoPlayer

class Media3Backend(private val exoPlayer: ExoPlayer) : PlaybackBackend {
    private var listener: PlaybackBackend.Listener? = null
    override fun attachSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        exoPlayer.setVideoSurfaceHolder(surfaceHolder)
    }
    override fun prepare(uri: Uri, headers: Map<String, String>, startPositionMs: Long) { }
    override fun play() = exoPlayer.play()
    override fun pause() = exoPlayer.pause()
    override fun seekTo(positionMs: Long) = exoPlayer.seekTo(positionMs)
    override fun stop() = exoPlayer.stop()
    override fun release() { }
    override fun getPositionMs(): Long = exoPlayer.currentPosition
    override fun getDurationMs(): Long = exoPlayer.duration
    override fun isPlaying(): Boolean = exoPlayer.isPlaying
    override fun getBufferedPositionMs(): Long = exoPlayer.bufferedPosition
    override fun getBufferedPercentage(): Int = exoPlayer.bufferedPercentage
    override fun setListener(listener: PlaybackBackend.Listener?) { this.listener = listener }
}
