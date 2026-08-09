package top.rootu.dddplayer.player.core

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.player.nativecore.DddNativeBridge

/**
 * Public shape of the single DDD playback engine.
 *
 * Media3 and VLC are still used as adapters while the native FFmpeg/MediaCodec
 * path is being built, but UI/state code should depend on this profile rather
 * than backend-specific assumptions.
 */
class DddPlaybackCore(
    context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val appContext = context.applicationContext

    fun buildRuntimeProfile(): DddPlaybackRuntimeProfile {
        val codecs = queryHardwareVideoCodecs()
        return DddPlaybackRuntimeProfile(
            apiLevel = Build.VERSION.SDK_INT,
            configuredEngine = settingsRepository.getPlaybackEngine(),
            media3AdapterAvailable = true,
            vlcAdapterAvailable = true,
            nativeBackendAvailable = false,
            nativeBootstrapAvailable = DddNativeBridge.isLibraryLoaded,
            nativeBootstrapVersion = DddNativeBridge.getVersionSafe(),
            ffmpegProbeAvailable = DddNativeBridge.hasFfmpegProbeSafe(),
            hardwareVideoCodecs = codecs,
            hdrWindowModeAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
            p010OutputCandidate = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            softwareToneMappingAvailable = false,
            subtitleRenderer = "Media3 text / VLC native; libass planned"
        )
    }

    fun describeAdapter(activeBackendId: String): DddPlaybackPipeline {
        return when (activeBackendId) {
            "VLC" -> DddPlaybackPipeline(
                engine = DddPlaybackEngine.VLC_ADAPTER,
                demux = "VLC/libavformat",
                videoDecode = "VLC codec pipeline",
                videoRender = "VLC Surface",
                audio = "VLC audio output",
                subtitles = "VLC subtitle pipeline",
                hdr = "Platform/VLC dependent"
            )
            "MEDIA3" -> DddPlaybackPipeline(
                engine = DddPlaybackEngine.MEDIA3_ADAPTER,
                demux = "Media3 Extractors/DataSource",
                videoDecode = "MediaCodec via Media3",
                videoRender = "Android Surface",
                audio = "Media3 AudioSink/AudioTrack",
                subtitles = "Media3 text renderers",
                hdr = "Android Surface color metadata"
            )
            else -> DddPlaybackPipeline(
                engine = DddPlaybackEngine.NONE,
                demux = "idle",
                videoDecode = "idle",
                videoRender = "idle",
                audio = "idle",
                subtitles = "idle",
                hdr = "idle"
            )
        }
    }

    fun logRuntimeProfile(tag: String = "DDDPlayer/Core") {
        val profile = buildRuntimeProfile()
        Log.i(tag, "runtimeProfile=$profile")
    }

    fun logPipeline(activeBackendId: String, reason: String, tag: String = "DDDPlayer/Core") {
        Log.i(tag, "pipeline reason=$reason ${describeAdapter(activeBackendId)}")
    }

    private fun queryHardwareVideoCodecs(): List<String> {
        return runCatching {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            codecList.codecInfos
                .asSequence()
                .filter { !it.isEncoder }
                .filter { info -> info.supportedTypes.any { it.startsWith("video/") } }
                .map { info ->
                    val typeList = info.supportedTypes
                        .filter { it.startsWith("video/") }
                        .joinToString("|")
                    val hardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        info.isHardwareAccelerated
                    } else {
                        !info.name.startsWith("OMX.google", ignoreCase = true)
                    }
                    "${info.name}[$typeList] hw=$hardware"
                }
                .toList()
        }.getOrElse { error ->
            Log.w("DDDPlayer/Core", "codec query failed", error)
            emptyList()
        }
    }
}

enum class DddPlaybackEngine {
    NONE,
    MEDIA3_ADAPTER,
    VLC_ADAPTER,
    DDD_NATIVE
}

data class DddPlaybackRuntimeProfile(
    val apiLevel: Int,
    val configuredEngine: String,
    val media3AdapterAvailable: Boolean,
    val vlcAdapterAvailable: Boolean,
    val nativeBackendAvailable: Boolean,
    val nativeBootstrapAvailable: Boolean,
    val nativeBootstrapVersion: String?,
    val ffmpegProbeAvailable: Boolean,
    val hardwareVideoCodecs: List<String>,
    val hdrWindowModeAvailable: Boolean,
    val p010OutputCandidate: Boolean,
    val softwareToneMappingAvailable: Boolean,
    val subtitleRenderer: String
)

data class DddPlaybackPipeline(
    val engine: DddPlaybackEngine,
    val demux: String,
    val videoDecode: String,
    val videoRender: String,
    val audio: String,
    val subtitles: String,
    val hdr: String
)
