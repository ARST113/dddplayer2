package top.rootu.dddplayer.player

import android.content.Context
import android.net.Uri
import android.view.SurfaceHolder
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.model.SubtitleItem

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
    private var pendingSeekApplyMs: Long = 0L
    private var lastBufferingPercent: Int = 0
    private var isCurrentlyBuffering: Boolean = false
    private var lastKnownTimeMs: Long = 0L
    private var lastKnownDurationMs: Long = 0L
    private var lastSelectedAudioTrackId: Int? = null
    private var lastSelectedSubtitleTrackId: Int? = null

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
        prepare(uri, headers, startPositionMs, emptyList())
    }

    fun prepare(uri: Uri, headers: Map<String, String>, startPositionMs: Long, subtitles: List<SubtitleItem>) {
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
                    MediaPlayer.Event.Buffering -> {
                        val percent = event.buffering.toInt().coerceIn(0, 100)
                        lastBufferingPercent = percent
                        isCurrentlyBuffering = percent < 100
                        android.util.Log.i("DDDPlayer/VLC", "VLC Buffering percent=$percent")
                        if (isCurrentlyBuffering) listener?.onBuffering()
                    }
                    MediaPlayer.Event.TimeChanged -> {
                        lastKnownTimeMs = event.timeChanged
                        android.util.Log.d("DDDPlayer/VLC", "TimeChanged=$lastKnownTimeMs")
                        listener?.onPositionChanged(getPositionMs(), getDurationMs())
                    }
                    MediaPlayer.Event.PositionChanged -> {
                        val len = mediaPlayer?.length ?: lastKnownDurationMs
                        if (len > 0) lastKnownTimeMs = (event.positionChanged * len).toLong()
                        android.util.Log.d("DDDPlayer/VLC", "PositionChanged=${event.positionChanged} time=$lastKnownTimeMs")
                        listener?.onPositionChanged(getPositionMs(), getDurationMs())
                    }
                    MediaPlayer.Event.LengthChanged -> {
                        lastKnownDurationMs = event.lengthChanged
                        if (pendingSeekApplyMs > 0 && lastKnownDurationMs > 0) { mediaPlayer?.time = pendingSeekApplyMs; pendingSeekApplyMs = 0L }
                        android.util.Log.d("DDDPlayer/VLC", "LengthChanged=$lastKnownDurationMs")
                        listener?.onPositionChanged(getPositionMs(), getDurationMs())
                    }
                    MediaPlayer.Event.Playing -> {
                        val toApply = when { pendingSeekApplyMs > 0 -> pendingSeekApplyMs; pendingStartPositionMs > 0 -> pendingStartPositionMs; else -> 0L }
                        if (toApply > 0) {
                            player.time = toApply
                            android.util.Log.i("DDDPlayer/VLC", "Applied start/seek position=$toApply, actual=${player.time}")
                            pendingSeekApplyMs = 0L
                            pendingStartPositionMs = 0L
                        }
                        isCurrentlyBuffering = false
                        lastBufferingPercent = 100
                        android.util.Log.i("DDDPlayer/VLC", "VLC Playing -> buffering=false")
                        lastKnownTimeMs = player.time
                        lastKnownDurationMs = player.length
                        refreshAudioTrackState()
                        refreshSubtitleTrackState()
                        listener?.onPlaying()
                        listener?.onPositionChanged(getPositionMs(), getDurationMs())
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
            subtitles.forEach { subtitle ->
                val slave = IMedia.Slave(IMedia.Slave.Type.Subtitle, 4, subtitle.uri.toString())
                media.addSlave(slave)
                android.util.Log.i("DDDPlayer/VLC", "addSubtitleSlave uri=${subtitle.uri} name=${subtitle.name ?: subtitle.filename}")
            }
            player.media = media
            media.release()
            player.play()
        }
    }

    override fun play() { mediaPlayer?.play() }
    override fun pause() { mediaPlayer?.pause() }
    override fun seekTo(positionMs: Long) {
        mediaPlayer?.time = positionMs
        pendingSeekApplyMs = positionMs
        lastKnownTimeMs = positionMs
        android.util.Log.i("DDDPlayer/VLC", "seekTo target=$positionMs actual=${mediaPlayer?.time}")
    }
    override fun stop() { mediaPlayer?.stop() }
    override fun getPositionMs(): Long = lastKnownTimeMs.takeIf { it > 0 } ?: (mediaPlayer?.time ?: 0L)
    override fun getDurationMs(): Long = lastKnownDurationMs.takeIf { it > 0 } ?: (mediaPlayer?.length ?: 0L)
    override fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true
    override fun getBufferedPositionMs(): Long = getPositionMs()
    override fun getBufferedPercentage(): Int = if (isPlaying()) 100 else if (isCurrentlyBuffering) lastBufferingPercent else lastBufferingPercent
    override fun setListener(listener: PlaybackBackend.Listener?) { this.listener = listener }

    private fun readRawVlcAudioTracks(): List<BackendAudioTrack> {
        val list = mediaPlayer?.audioTracks ?: return emptyList()
        return list.filter { it.id >= 0 }.map { BackendAudioTrack(it.id, it.name ?: "Track ${it.id}") }
    }

    private fun readRawSelectedAudioTrackId(): Int? {
        val id = mediaPlayer?.audioTrack
        return id?.takeIf { it >= 0 } ?: lastSelectedAudioTrackId
    }

    fun getAudioTracks(): List<BackendAudioTrack> {
        val tracks = readRawVlcAudioTracks()
        val selectedId = readRawSelectedAudioTrackId()
        return tracks.map { it.copy(selected = it.id == selectedId) }
    }

    fun getSelectedAudioTrack(): Int? {
        val selectedId = readRawSelectedAudioTrackId()
        val tracks = readRawVlcAudioTracks()
        val valid = selectedId?.takeIf { id -> tracks.any { it.id == id } }
        if (valid != null) {
            lastSelectedAudioTrackId = valid
            return valid
        }
        return lastSelectedAudioTrackId?.takeIf { id -> tracks.any { it.id == id } }
    }

    fun selectAudioTrack(trackId: Int): Boolean {
        mediaPlayer?.audioTrack = trackId
        val ok = mediaPlayer?.audioTrack == trackId
        if (ok) {
            lastSelectedAudioTrackId = trackId
            refreshAudioTrackState()
        }
        android.util.Log.i("DDDPlayer/VLC", "selectAudioTrack id=$trackId ok=$ok")
        return ok
    }

    private fun readRawVlcSubtitleTracks(): List<BackendSubtitleTrack> {
        val list = mediaPlayer?.spuTracks ?: return emptyList()
        val mapped = list.map { track ->
            val label = if (track.id < 0) {
                appContext.getString(R.string.track_off)
            } else {
                track.name ?: "Subtitle ${track.id}"
            }
            BackendSubtitleTrack(track.id, label)
        }
        return if (mapped.any { it.id == -1 }) {
            mapped
        } else {
            listOf(BackendSubtitleTrack(-1, appContext.getString(R.string.track_off))) + mapped
        }
    }

    private fun readRawSelectedSubtitleTrackId(): Int? {
        val id = mediaPlayer?.spuTrack
        return id ?: lastSelectedSubtitleTrackId
    }

    fun getSubtitleTracks(): List<BackendSubtitleTrack> {
        val tracks = readRawVlcSubtitleTracks()
        val selectedId = readRawSelectedSubtitleTrackId() ?: -1
        return tracks.map { it.copy(selected = it.id == selectedId) }
    }

    fun getSelectedSubtitleTrack(): Int? {
        val selectedId = readRawSelectedSubtitleTrackId() ?: -1
        val tracks = readRawVlcSubtitleTracks()
        val valid = selectedId.takeIf { id -> tracks.any { it.id == id } }
        if (valid != null) {
            lastSelectedSubtitleTrackId = valid
            return valid
        }
        return lastSelectedSubtitleTrackId?.takeIf { id -> tracks.any { it.id == id } }
            ?: tracks.firstOrNull { it.id == -1 }?.id
    }

    fun selectSubtitleTrack(trackId: Int): Boolean {
        val ok = mediaPlayer?.setSpuTrack(trackId) == true || mediaPlayer?.spuTrack == trackId
        val selected = mediaPlayer?.spuTrack
        if (ok) {
            lastSelectedSubtitleTrackId = trackId
            refreshSubtitleTrackState()
        }
        android.util.Log.i("DDDPlayer/VLC", "selectSubtitleTrack id=$trackId ok=$ok selected=$selected tracks=${getSubtitleTracks()}")
        return ok
    }

    private fun refreshAudioTrackState() {
        val tracks = mediaPlayer?.audioTracks?.filter { it.id != -1 } ?: return
        val direct = mediaPlayer?.audioTrack
        if (direct != null && direct != -1 && tracks.any { it.id == direct }) {
            lastSelectedAudioTrackId = direct
        }
    }

    private fun refreshSubtitleTrackState() {
        val tracks = mediaPlayer?.spuTracks ?: return
        val direct = mediaPlayer?.spuTrack
        if (direct != null && tracks.any { it.id == direct }) {
            lastSelectedSubtitleTrackId = direct
        }
    }

    override fun release() {
        mediaPlayer?.vlcVout?.detachViews()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        libVlc?.release()
        libVlc = null
        lastSelectedAudioTrackId = null
        lastSelectedSubtitleTrackId = null
        pendingSeekApplyMs = 0L
    }
}
