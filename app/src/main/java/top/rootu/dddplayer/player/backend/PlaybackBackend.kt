package top.rootu.dddplayer.player.backend

import android.net.Uri
import android.view.SurfaceHolder

interface PlaybackBackend {
    val backendId: PlaybackBackendId
    fun attachSurfaceHolder(holder: SurfaceHolder?)
    fun prepare(item: PlaybackItem, startPositionMs: Long = 0L, playWhenReady: Boolean = true)
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
    fun setListener(listener: PlaybackBackendListener?)
}

enum class PlaybackBackendId { MEDIA3, VLC }
enum class UnifiedPlaybackState { IDLE, OPENING, BUFFERING, READY, PLAYING, PAUSED, SEEKING, ENDED, ERROR }

interface PlaybackBackendListener {
    fun onStateChanged(state: UnifiedPlaybackState)
    fun onPositionDiscontinuity(positionMs: Long)
    fun onEnded()
    fun onError(error: PlaybackBackendError)
    fun onTracksChanged()
}

data class PlaybackBackendError(
    val backendId: PlaybackBackendId,
    val code: Int?,
    val message: String?,
    val causeClass: String?,
    val isVideoDecoderError: Boolean,
    val isAudioDecoderError: Boolean,
    val rendererName: String?,
    val mimeType: String?,
    val decoderName: String?
)

data class SubtitleItem(
    val uri: Uri,
    val mimeType: String? = null,
    val language: String? = null,
    val label: String? = null
)

data class PlaybackItem(
    val uri: Uri,
    val title: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleItem> = emptyList(),
    val playlistIndex: Int = 0,
    val contentKey: String? = null
)

