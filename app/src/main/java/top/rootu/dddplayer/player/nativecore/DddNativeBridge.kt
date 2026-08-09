package top.rootu.dddplayer.player.nativecore

import android.util.Log
import android.view.Surface

object DddNativeBridge {
    private const val TAG = "DDDPlayer/Native"
    private var loaded = false
    private var loadError: Throwable? = null

    init {
        try {
            System.loadLibrary("ddd_native_player")
            loaded = true
            Log.i(
                TAG,
                "ddd_native_player loaded version=${nativeGetVersion()} playback=${nativeHasFfmpegProbe()}"
            )
        } catch (error: Throwable) {
            loadError = error
            Log.w(TAG, "ddd_native_player unavailable", error)
        }
    }

    val isLibraryLoaded: Boolean
        get() = loaded

    fun getLoadErrorMessage(): String? = loadError?.message

    fun getVersionSafe(): String? =
        if (loaded) runCatching { nativeGetVersion() }.getOrNull() else null

    fun getCapabilitiesSafe(): String? =
        if (loaded) runCatching { nativeGetCapabilities() }.getOrNull() else null

    fun hasFfmpegProbeSafe(): Boolean =
        loaded && runCatching { nativeHasFfmpegProbe() }.getOrDefault(false)

    fun hasPlaybackSafe(): Boolean = hasFfmpegProbeSafe()

    fun probeUriSafe(uri: String): String? =
        if (loaded) runCatching { nativeProbeUri(uri) }.getOrNull() else null

    fun createSessionSafe(): Long? =
        if (loaded) runCatching { nativeCreateSession().takeIf { it != 0L } }.getOrNull() else null

    fun releaseSessionSafe(handle: Long) {
        if (loaded && handle != 0L) runCatching { nativeReleaseSession(handle) }
    }

    fun setSurfaceSafe(handle: Long, surface: Surface?): Boolean =
        loaded && handle != 0L &&
            runCatching { nativeSetSurface(handle, surface) }.getOrDefault(false)

    fun prepareSessionSafe(handle: Long, uri: String, startPositionMs: Long): String? =
        if (loaded && handle != 0L) {
            runCatching { nativePrepareSession(handle, uri, startPositionMs) }.getOrNull()
        } else {
            null
        }

    fun playSessionSafe(handle: Long) {
        if (loaded && handle != 0L) runCatching { nativePlaySession(handle) }
    }

    fun pauseSessionSafe(handle: Long) {
        if (loaded && handle != 0L) runCatching { nativePauseSession(handle) }
    }

    fun seekSessionSafe(handle: Long, positionMs: Long) {
        if (loaded && handle != 0L) {
            runCatching { nativeSeekSession(handle, positionMs.coerceAtLeast(0L)) }
        }
    }

    fun stopSessionSafe(handle: Long) {
        if (loaded && handle != 0L) runCatching { nativeStopSession(handle) }
    }

    fun getPlaybackSnapshotSafe(handle: Long): String? =
        if (loaded && handle != 0L) {
            runCatching { nativeGetPlaybackSnapshot(handle) }.getOrNull()
        } else {
            null
        }

    fun getAudioTracksSafe(handle: Long): String? =
        if (loaded && handle != 0L) {
            runCatching { nativeGetAudioTracks(handle) }.getOrNull()
        } else {
            null
        }

    fun selectAudioTrackSafe(handle: Long, streamIndex: Int): Boolean =
        loaded && handle != 0L &&
            runCatching { nativeSelectAudioTrack(handle, streamIndex) }.getOrDefault(false)

    private external fun nativeGetVersion(): String
    private external fun nativeGetCapabilities(): String
    private external fun nativeHasFfmpegProbe(): Boolean
    private external fun nativeProbeUri(uri: String): String
    private external fun nativeCreateSession(): Long
    private external fun nativeReleaseSession(handle: Long)
    private external fun nativeSetSurface(handle: Long, surface: Surface?): Boolean
    private external fun nativePrepareSession(handle: Long, uri: String, startPositionMs: Long): String
    private external fun nativePlaySession(handle: Long)
    private external fun nativePauseSession(handle: Long)
    private external fun nativeSeekSession(handle: Long, positionMs: Long)
    private external fun nativeStopSession(handle: Long)
    private external fun nativeGetPlaybackSnapshot(handle: Long): String
    private external fun nativeGetAudioTracks(handle: Long): String
    private external fun nativeSelectAudioTrack(handle: Long, streamIndex: Int): Boolean
}