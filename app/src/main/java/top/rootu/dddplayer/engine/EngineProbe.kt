package top.rootu.dddplayer.engine

import top.rootu.dddplayer.player.BackendAudioTrack
import top.rootu.dddplayer.player.BackendSubtitleTrack

/**
 * Всё, что известно об источнике до первого декодированного кадра.
 *
 * Это результат шага 3: контейнер разобран, дорожки перечислены, HDR-метаданные
 * извлечены. Ни одного кадра при этом не декодировано — то есть проверить всю
 * сетевую и контейнерную часть можно раньше, чем появится видео.
 *
 * Заменяет три источника правды, которые сейчас живут в DDD параллельно:
 * `exoPlayer.currentTracks`, `getVlc*Tracks` и `UnifiedMetadataReader`.
 */
data class EngineProbe(
    val format: String? = null,
    val formatLongName: String? = null,

    /** Длительность, мс; 0 для live/неизвестной. */
    val durationMs: Long = 0,

    val bitrate: Long = 0,

    /** Источник перематывается (для TorrServer `/cache` — да, для live-HLS — нет). */
    val seekable: Boolean = false,

    val tracks: EngineTracks = EngineTracks.EMPTY,
    val color: EngineColorInfo = EngineColorInfo.SDR_BT709,
    val videoFormat: EngineVideoFormat = EngineVideoFormat(),
    val stereo: StereoLayout = StereoLayout.MONO,
    val projection: Projection = Projection.FLAT,

    val bestVideoIndex: Int = -1,
    val bestAudioIndex: Int = -1,
    val bestSubtitleIndex: Int = -1,

    /**
     * Поток с конфигурацией Dolby Vision; -1, если её нет.
     *
     * Отдельно от [EngineColorInfo.dolbyProfile], потому что у профиля 7
     * (двухслойный BL+EL) бокс `dvvC` лежит на ВТОРОМ видеопотоке — отдельной
     * дорожке enhancement layer. Понадобится на шаге 11: декодировать нужно две.
     */
    val dolbyStreamIndex: Int = -1
) {
    val hasVideo: Boolean get() = bestVideoIndex >= 0

    override fun toString(): String = buildString {
        append("EngineProbe($format")
        if (durationMs > 0) append(", ${durationMs / 1000}s")
        if (videoFormat.hasSize) append(", ${videoFormat.width}x${videoFormat.height}")
        append(", ${color.label}")
        if (stereo != StereoLayout.MONO) append(", $stereo")
        if (projection != Projection.FLAT) append(", $projection")
        append(", дорожек: ${tracks.video.size}v/${tracks.audio.size}a/${tracks.subtitle.size}s)")
    }
}

/**
 * Собирает [EngineProbe] из колбэков нативного пробинга.
 *
 * Одноразовый: на каждый пробинг нужен свой экземпляр.
 *
 * `id` дорожек — это индекс потока в контейнере, а не порядковый номер в списке.
 * Так `NativeDemuxer.selectStreams` принимает ровно то, что пришло из модели, без
 * таблицы соответствий, которая в DDD сейчас существует отдельно для Exo и для
 * VLC (`TrackLogic`) и умеет рассинхронизироваться.
 */
class ProbeCollector : ProbeSink {

    private var format: String? = null
    private var formatLongName: String? = null
    private var durationUs: Long = 0
    private var bitrate: Long = 0
    private var seekable: Boolean = false

    private val video = mutableListOf<EngineVideoTrack>()
    private val audio = mutableListOf<BackendAudioTrack>()
    private val subtitle = mutableListOf<BackendSubtitleTrack>()

    /** Поля лучшего видеопотока: заполняются в [best], когда индекс уже известен. */
    private val videoRaw = mutableListOf<RawVideo>()

    private var color = EngineColorInfo.SDR_BT709
    private var stereo = StereoLayout.MONO
    private var projection = Projection.FLAT
    private var bestVideo = -1
    private var bestAudio = -1
    private var bestSubtitle = -1

    private class RawVideo(
        val streamIndex: Int,
        val width: Int,
        val height: Int,
        val sarNum: Int,
        val sarDen: Int,
        val frameRate: Float,
        val bitrate: Int,
        val codec: String?,
        val mime: String?,
        val rotation: Int
    )

    override fun container(
        format: String?,
        longName: String?,
        durationUs: Long,
        bitrate: Long,
        seekable: Boolean
    ) {
        this.format = format
        this.formatLongName = longName
        this.durationUs = durationUs
        this.bitrate = bitrate
        this.seekable = seekable
    }

    override fun video(
        streamIndex: Int,
        width: Int,
        height: Int,
        sarNum: Int,
        sarDen: Int,
        frameRate: Float,
        bitrate: Int,
        codec: String?,
        mime: String?,
        profile: Int,
        level: Int,
        rotation: Int,
        bitDepth: Int,
        isDefault: Boolean
    ) {
        videoRaw += RawVideo(
            streamIndex, width, height, sarNum, sarDen, frameRate, bitrate, codec, mime, rotation
        )
        video += EngineVideoTrack(
            id = streamIndex,
            width = width,
            height = height,
            bitrate = bitrate,
            frameRate = frameRate,
            codec = codec
        )
    }

    override fun audio(
        streamIndex: Int,
        codec: String?,
        profile: String?,
        channels: Int,
        sampleRate: Int,
        bitrate: Int,
        language: String?,
        title: String?,
        isDefault: Boolean,
        isForced: Boolean
    ) {
        audio += BackendAudioTrack(
            id = streamIndex,
            label = audioLabel(codec, profile, channels, language, title),
            codec = codec,
            originalCodec = codec,
            channels = channels,
            sampleRate = sampleRate,
            bitrate = bitrate,
            language = language,
            description = title
        )
    }

    override fun subtitle(
        streamIndex: Int,
        codec: String?,
        language: String?,
        title: String?,
        isDefault: Boolean,
        isForced: Boolean,
        isBitmap: Boolean
    ) {
        val parts = mutableListOf<String>()
        title?.takeIf { it.isNotBlank() }?.let { parts += it }
        language?.takeIf { it.isNotBlank() }?.let { if (parts.isEmpty()) parts += it }
        if (parts.isEmpty()) parts += codec ?: "Субтитры"
        if (isForced) parts += "forced"
        subtitle += BackendSubtitleTrack(
            id = streamIndex,
            label = parts.joinToString(", ")
        )
    }

    override fun color(
        standard: Int,
        transfer: Int,
        range: Int,
        bitDepth: Int,
        dolbyProfile: Int,
        dolbyStreamIndex: Int,
        hasHdr10Plus: Boolean,
        staticInfo: ByteArray?
    ) {
        color = EngineColorInfo(
            colorStandard = standard,
            colorTransfer = transfer,
            colorRange = range,
            hdrStaticInfo = staticInfo,
            bitDepth = bitDepth,
            dolbyProfile = dolbyProfile,
            hasHdr10Plus = hasHdr10Plus
        )
        dolbyStream = dolbyStreamIndex
    }

    /** Поток с конфигурацией Dolby Vision; уходит в [EngineProbe.dolbyStreamIndex]. */
    private var dolbyStream: Int = -1

    override fun geometry(stereo: Int, projection: Int) {
        // Ordinal'ы приходят из native; выход за границы означал бы расхождение
        // enum'ов, и молча превращать это в MONO/FLAT нельзя — потеряется 3D.
        this.stereo = StereoLayout.entries.getOrElse(stereo) {
            error("неизвестный StereoLayout ordinal=$stereo")
        }
        this.projection = Projection.entries.getOrElse(projection) {
            error("неизвестный Projection ordinal=$projection")
        }
    }

    override fun best(video: Int, audio: Int, subtitle: Int) {
        bestVideo = video
        bestAudio = audio
        bestSubtitle = subtitle
    }

    fun build(): EngineProbe {
        val main = videoRaw.firstOrNull { it.streamIndex == bestVideo } ?: videoRaw.firstOrNull()

        return EngineProbe(
            format = format,
            formatLongName = formatLongName,
            durationMs = if (durationUs > 0) durationUs / 1000 else 0,
            bitrate = bitrate,
            seekable = seekable,
            tracks = EngineTracks(
                video = video.map { it.copy(selected = it.id == bestVideo) },
                audio = audio.map { it.copy(selected = it.id == bestAudio) },
                subtitle = subtitle.map { it.copy(selected = it.id == bestSubtitle) },
                // Одна дорожка — выбирать нечего, и пункт `Auto` в меню только мешает.
                videoAuto = video.size > 1
            ),
            color = color,
            videoFormat = main?.let {
                EngineVideoFormat(
                    width = it.width,
                    height = it.height,
                    pixelAspectRatio = if (it.sarNum > 0 && it.sarDen > 0) {
                        it.sarNum.toFloat() / it.sarDen
                    } else 1f,
                    frameRate = it.frameRate,
                    codec = it.codec,
                    mimeType = it.mime,
                    bitrate = it.bitrate,
                    rotationDegrees = it.rotation
                )
            } ?: EngineVideoFormat(),
            stereo = stereo,
            projection = projection,
            bestVideoIndex = bestVideo,
            bestAudioIndex = bestAudio,
            bestSubtitleIndex = bestSubtitle,
            dolbyStreamIndex = dolbyStream
        )
    }

    private fun audioLabel(
        codec: String?,
        profile: String?,
        channels: Int,
        language: String?,
        title: String?
    ): String {
        val parts = mutableListOf<String>()
        title?.takeIf { it.isNotBlank() }?.let { parts += it }
        language?.takeIf { it.isNotBlank() }?.let { parts += it }
        // Профиль важнее кодека: `DTS-HD MA` и `DTS` — это разное качество, а
        // codec_id у них один. Именно этого различия не хватает в текущем меню DDD.
        (profile?.takeIf { it.isNotBlank() } ?: codec)?.let { parts += it }
        channelLabel(channels)?.let { parts += it }
        return if (parts.isEmpty()) "Аудио" else parts.joinToString(", ")
    }

    private fun channelLabel(channels: Int): String? = when {
        channels <= 0 -> null
        channels == 1 -> "Моно"
        channels == 2 -> "Стерео"
        channels == 6 -> "5.1"
        channels == 8 -> "7.1"
        else -> "$channels ch"
    }
}
