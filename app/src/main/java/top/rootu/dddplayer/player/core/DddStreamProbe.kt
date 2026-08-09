package top.rootu.dddplayer.player.core

import android.net.Uri
import top.rootu.dddplayer.utils.MediaFormatHelper

/**
 * Lightweight probe used before a full native FFmpeg probe exists.
 * It keeps the future DDD native engine contract explicit while preserving
 * today's Media3/VLC playback behavior.
 */
object DddStreamProbe {
    fun probeUri(uri: Uri?): DddStreamProbeResult {
        val mime = uri?.let { MediaFormatHelper.getMimeType(it) }
        val extension = uri?.path?.let { MediaFormatHelper.getFileExtension(it) }
        return DddStreamProbeResult(
            uri = uri?.toString().orEmpty(),
            containerHint = mime.orEmpty(),
            extension = extension.orEmpty(),
            isVideo = MediaFormatHelper.isVideoMimeType(mime),
            isAudio = MediaFormatHelper.isAudioMimeType(mime),
            isSubtitle = MediaFormatHelper.isSubtitleMimeType(mime),
            needsNativeProbe = mime.isNullOrBlank() || extension in nativeProbeExtensions
        )
    }

    private val nativeProbeExtensions = setOf(
        "mkv", "mka", "mks", "ts", "m2ts", "mts", "avi", "mov", "wmv", "flv", "vob", "evo"
    )
}

data class DddStreamProbeResult(
    val uri: String,
    val containerHint: String,
    val extension: String,
    val isVideo: Boolean,
    val isAudio: Boolean,
    val isSubtitle: Boolean,
    val needsNativeProbe: Boolean
)
