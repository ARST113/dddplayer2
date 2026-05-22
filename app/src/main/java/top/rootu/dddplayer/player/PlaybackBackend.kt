package top.rootu.dddplayer.player

import android.net.Uri
import android.view.SurfaceHolder

interface PlaybackBackend {
    interface Listener {
        fun onBuffering() {}
        fun onPlaying() {}
        fun onPaused() {}
        fun onEnded() {}
        fun onError(error: Throwable) {}
        fun onPositionChanged(positionMs: Long, durationMs: Long) {}
    }

    fun attachSurfaceHolder(surfaceHolder: SurfaceHolder?)
    fun prepare(uri: Uri, headers: Map<String, String> = emptyMap(), startPositionMs: Long = 0L)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun stop()
    fun release()
    fun getPositionMs(): Long
    fun getDurationMs(): Long
    fun isPlaying(): Boolean
    fun getBufferedPositionMs(): Long
    fun getBufferedPercentage(): Int
    fun setListener(listener: Listener?)
}
