package top.rootu.dddplayer.player

import android.content.Context
import android.net.Uri
import android.view.SurfaceHolder
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import top.rootu.dddplayer.data.SettingsRepository

class VlcBackend(
    context: Context,
    private val settingsRepository: SettingsRepository
) : PlaybackBackend {
    private val appContext = context.applicationContext
    private var listener: PlaybackBackend.Listener? = null
    private var holder: SurfaceHolder? = null
    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var pendingStartPositionMs: Long = 0L

    override fun attachSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        holder = surfaceHolder
        mediaPlayer?.vlcVout?.apply {
            detachViews()
            if (surfaceHolder != null) {
                setVideoSurface(surfaceHolder.surface, surfaceHolder)
                attachViews()
            }
        }
    }

    override fun prepare(uri: Uri, headers: Map<String, String>, startPositionMs: Long) {
        release()
        val options = mutableListOf(
            "--network-caching=${settingsRepository.getVlcNetworkCachingMs()}",
            "--file-caching=${settingsRepository.getVlcFileCachingMs()}"
        )
        when (settingsRepository.getVlcHardwareAccelerationMode()) {
            SettingsRepository.VLC_HW_DISABLED -> options += "--avcodec-hw=none"
            SettingsRepository.VLC_HW_DECODING -> options += "--avcodec-hw=mediacodec_ndk"
            SettingsRepository.VLC_HW_FULL -> options += "--avcodec-hw=any"
        }
        pendingStartPositionMs = startPositionMs
        libVlc = LibVLC(appContext, options)
        mediaPlayer = MediaPlayer(libVlc).also { player ->
            player.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Buffering -> listener?.onBuffering()
                    MediaPlayer.Event.Playing -> {
                        if (pendingStartPositionMs > 0) {
                            player.time = pendingStartPositionMs
                            android.util.Log.i("DDDPlayer/VLC", "Applied start position=${pendingStartPositionMs}, actual=${player.time}")
                            pendingStartPositionMs = 0L
                        }
                        listener?.onPlaying()
                    }
                    MediaPlayer.Event.Paused -> listener?.onPaused()
                    MediaPlayer.Event.EndReached -> listener?.onEnded()
                    MediaPlayer.Event.EncounteredError -> listener?.onError(IllegalStateException("VLC playback error"))
                }
            }
            if (holder != null) {
                player.vlcVout.setVideoSurface(holder!!.surface, holder)
                player.vlcVout.attachViews()
            }
            val media = Media(libVlc, uri)
            headers.forEach { (k, v) -> media.addOption(":http-header=$k=$v") }
            player.media = media
            media.release()
            player.play()
        }
    }

    override fun play() { mediaPlayer?.play() }
    override fun pause() { mediaPlayer?.pause() }
    override fun seekTo(positionMs: Long) { mediaPlayer?.time = positionMs }
    override fun stop() { mediaPlayer?.stop() }
    override fun getPositionMs(): Long = mediaPlayer?.time ?: 0L
    override fun getDurationMs(): Long = mediaPlayer?.length ?: 0L
    override fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true
    override fun getBufferedPositionMs(): Long = getPositionMs()
    override fun getBufferedPercentage(): Int = 0
    override fun setListener(listener: PlaybackBackend.Listener?) { this.listener = listener }

    override fun release() {
        mediaPlayer?.vlcVout?.detachViews()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        libVlc?.release()
        libVlc = null
    }
}
