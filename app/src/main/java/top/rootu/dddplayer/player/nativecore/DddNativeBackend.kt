package top.rootu.dddplayer.player.nativecore

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import com.google.gson.JsonParser
import top.rootu.dddplayer.player.BackendAudioTrack
import top.rootu.dddplayer.player.PlaybackBackend

class DddNativeBackend : PlaybackBackend {
    private data class PendingMedia(
        val uri: Uri,
        val headers: Map<String, String>,
        val startPositionMs: Long
    )

    private val handler = Handler(Looper.getMainLooper())
    private var listener: PlaybackBackend.Listener? = null
    private var nativeHandle: Long = 0L
    private var attachedSurface: Surface? = null
    private var pendingMedia: PendingMedia? = null
    private var started = false
    private var released = false
    private var lastPlaying = false
    private var lastBuffering = false
    private var errorDispatched = false
    private var endDispatched = false
    private var positionMs = 0L
    private var durationMs = 0L
    private var bufferedPositionMs = 0L
    private var videoWidth = 0
    private var videoHeight = 0
    private var selectedAudioStream = -1

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (released || nativeHandle == 0L || !started) return
            pollSnapshot()
            if (!released && nativeHandle != 0L && started) {
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    override fun attachSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        val newSurface = surfaceHolder?.surface?.takeIf { it.isValid }
        if (newSurface === attachedSurface) return

        if (started) {
            pendingMedia = pendingMedia?.copy(startPositionMs = positionMs)
            DddNativeBridge.stopSessionSafe(nativeHandle)
            started = false
            handler.removeCallbacks(pollRunnable)
        }

        attachedSurface = newSurface
        if (nativeHandle != 0L) {
            DddNativeBridge.setSurfaceSafe(nativeHandle, newSurface)
        }
        if (newSurface != null) startPendingMedia()
    }

    override fun prepare(uri: Uri, headers: Map<String, String>, startPositionMs: Long) {
        released = false
        ensureSession()
        DddNativeBridge.stopSessionSafe(nativeHandle)
        started = false
        handler.removeCallbacks(pollRunnable)
        pendingMedia = PendingMedia(uri, headers, startPositionMs.coerceAtLeast(0L))
        positionMs = startPositionMs.coerceAtLeast(0L)
        durationMs = 0L
        bufferedPositionMs = positionMs
        lastPlaying = false
        lastBuffering = true
        errorDispatched = false
        endDispatched = false
        listener?.onBuffering()
        startPendingMedia()
    }

    override fun play() {
        if (!started) {
            startPendingMedia()
            return
        }
        DddNativeBridge.playSessionSafe(nativeHandle)
    }

    override fun pause() {
        if (started) DddNativeBridge.pauseSessionSafe(nativeHandle)
    }

    override fun seekTo(positionMs: Long) {
        val safe = positionMs.coerceAtLeast(0L)
        this.positionMs = safe
        bufferedPositionMs = safe
        if (started) DddNativeBridge.seekSessionSafe(nativeHandle, safe)
    }

    override fun stop() {
        handler.removeCallbacks(pollRunnable)
        if (nativeHandle != 0L) DddNativeBridge.stopSessionSafe(nativeHandle)
        started = false
        lastPlaying = false
        lastBuffering = false
    }

    override fun release() {
        released = true
        stop()
        if (nativeHandle != 0L) {
            DddNativeBridge.releaseSessionSafe(nativeHandle)
            nativeHandle = 0L
        }
        attachedSurface = null
        pendingMedia = null
    }

    override fun getPositionMs(): Long = positionMs
    override fun getDurationMs(): Long = durationMs
    override fun isPlaying(): Boolean = lastPlaying
    override fun getBufferedPositionMs(): Long = bufferedPositionMs

    override fun getBufferedPercentage(): Int {
        if (durationMs <= 0L) return 0
        return ((bufferedPositionMs * 100L) / durationMs).toInt().coerceIn(0, 100)
    }

    override fun setListener(listener: PlaybackBackend.Listener?) {
        this.listener = listener
    }

    fun getAudioTracks(): List<BackendAudioTrack> {
        val raw = DddNativeBridge.getAudioTracksSafe(nativeHandle) ?: return emptyList()
        return runCatching {
            JsonParser.parseString(raw).asJsonArray.mapNotNull { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val id = item.get("id")?.asInt ?: return@mapNotNull null
                BackendAudioTrack(
                    id = id,
                    label = item.get("label")?.asString?.takeIf { it.isNotBlank() }
                        ?: "Audio $id",
                    selected = item.get("selected")?.asBoolean == true,
                    codec = item.get("codec")?.asString,
                    channels = item.get("channels")?.asInt ?: 0,
                    sampleRate = item.get("sampleRate")?.asInt ?: 0,
                    bitrate = item.get("bitrate")?.asInt ?: 0,
                    language = item.get("language")?.asString,
                    description = listOfNotNull(
                        item.get("codec")?.asString?.takeIf { it.isNotBlank() },
                        item.get("language")?.asString?.takeIf { it.isNotBlank() }
                    ).joinToString(" ").ifBlank { null }
                )
            }
        }.getOrElse {
            Log.w(TAG, "Invalid native audio tracks: $raw", it)
            emptyList()
        }
    }

    fun getSelectedAudioTrack(): Int? =
        selectedAudioStream.takeIf { it >= 0 }
            ?: getAudioTracks().firstOrNull { it.selected }?.id

    fun selectAudioTrack(id: Int): Boolean =
        DddNativeBridge.selectAudioTrackSafe(nativeHandle, id)

    private fun ensureSession() {
        if (nativeHandle != 0L) return
        nativeHandle = DddNativeBridge.createSessionSafe() ?: 0L
        if (nativeHandle == 0L) {
            dispatchError("DDD Native session is unavailable: ${DddNativeBridge.getLoadErrorMessage()}")
            return
        }
        attachedSurface?.let { DddNativeBridge.setSurfaceSafe(nativeHandle, it) }
    }

    private fun startPendingMedia() {
        val media = pendingMedia ?: return
        val surface = attachedSurface?.takeIf { it.isValid } ?: return
        ensureSession()
        if (nativeHandle == 0L) return

        if (!DddNativeBridge.setSurfaceSafe(nativeHandle, surface)) {
            dispatchError("DDD Native could not attach the video surface")
            return
        }

        val result = DddNativeBridge.prepareSessionSafe(
            nativeHandle,
            media.uri.toString(),
            media.startPositionMs
        )
        val ok = runCatching {
            result?.let { JsonParser.parseString(it).asJsonObject.get("ok")?.asBoolean }
        }.getOrNull() == true

        if (!ok) {
            val reason = runCatching {
                result?.let {
                    JsonParser.parseString(it).asJsonObject.get("reason")?.asString
                }
            }.getOrNull()
            dispatchError(reason ?: "DDD Native prepare failed")
            return
        }

        started = true
        lastBuffering = true
        listener?.onBuffering()
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
        Log.i(TAG, "prepare uri=${media.uri} startMs=${media.startPositionMs}")
    }

    private fun pollSnapshot() {
        val raw = DddNativeBridge.getPlaybackSnapshotSafe(nativeHandle) ?: return
        val snapshot = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrElse {
            Log.w(TAG, "Invalid native snapshot: $raw", it)
            return
        }

        val running = snapshot.get("running")?.asBoolean == true
        val playing = snapshot.get("playing")?.asBoolean == true
        val buffering = snapshot.get("buffering")?.asBoolean == true
        val ended = snapshot.get("ended")?.asBoolean == true
        val error = snapshot.get("error")?.asString.orEmpty()
        positionMs = snapshot.get("positionMs")?.asLong?.coerceAtLeast(0L) ?: positionMs
        durationMs = snapshot.get("durationMs")?.asLong?.takeIf { it > 0L } ?: durationMs
        bufferedPositionMs = snapshot.get("bufferedPositionMs")?.asLong
            ?.coerceAtLeast(positionMs) ?: positionMs
        selectedAudioStream = snapshot.get("selectedAudioStream")?.asInt ?: selectedAudioStream

        val width = snapshot.get("width")?.asInt ?: 0
        val height = snapshot.get("height")?.asInt ?: 0
        if (width > 0 && height > 0 && (width != videoWidth || height != videoHeight)) {
            videoWidth = width
            videoHeight = height
            listener?.onVideoSizeChanged(width, height, 1f)
        }

        listener?.onPositionChanged(positionMs, durationMs)

        if (error.isNotBlank()) {
            dispatchError(error)
            return
        }

        if (buffering != lastBuffering) {
            lastBuffering = buffering
            if (buffering) listener?.onBuffering()
        }
        if (playing != lastPlaying) {
            lastPlaying = playing
            if (playing) listener?.onPlaying() else listener?.onPaused()
        }

        if (ended && !running && started && !endDispatched) {
            endDispatched = true
            started = false
            handler.removeCallbacks(pollRunnable)
            listener?.onEnded()
        }
    }

    private fun dispatchError(message: String) {
        if (errorDispatched) return
        errorDispatched = true
        started = false
        handler.removeCallbacks(pollRunnable)
        Log.e(TAG, message)
        listener?.onError(IllegalStateException(message))
    }

    companion object {
        private const val TAG = "DDDPlayer/Native"
        private const val POLL_INTERVAL_MS = 100L

        fun isAvailable(): Boolean = DddNativeBridge.hasPlaybackSafe()
    }
}
