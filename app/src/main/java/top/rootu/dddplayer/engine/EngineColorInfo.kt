package top.rootu.dddplayer.engine

/**
 * Цвет и HDR-метаданные текущего видеопотока.
 *
 * Значения [colorStandard], [colorTransfer] и [colorRange] заданы в домене
 * `android.media.MediaFormat` (а не в домене FFmpeg `AVCOL_*`): преобразование
 * `AVCOL_SPC/TRC/RANGE → MediaFormat` делает native-слой, потому что только у него
 * есть заголовки FFmpeg. Константы продублированы здесь численно, чтобы контракт
 * читался без обращения к `MediaFormat` и не тянул `@RequiresApi`.
 *
 * Это ядро HDR-пути: 4XVR получает корректный 4K HDR именно потому, что весь набор
 * ключей доходит от демуксера до `MediaCodec` без потерь. Ключевой из них —
 * [hdrStaticInfo], которого у DDD сейчас нет вообще: HDR читается только для подписи
 * в информационной панели.
 */
class EngineColorInfo(
    /** `MediaFormat.KEY_COLOR_STANDARD`. См. [COLOR_STANDARD_BT709] и далее. */
    val colorStandard: Int = COLOR_STANDARD_UNSPECIFIED,

    /** `MediaFormat.KEY_COLOR_TRANSFER`. См. [COLOR_TRANSFER_ST2084], [COLOR_TRANSFER_HLG]. */
    val colorTransfer: Int = COLOR_TRANSFER_UNSPECIFIED,

    /** `MediaFormat.KEY_COLOR_RANGE`. */
    val colorRange: Int = COLOR_RANGE_UNSPECIFIED,

    /**
     * `MediaFormat.KEY_HDR_STATIC_INFO` — 25 байт в раскладке CTA-861.3:
     * `[0]` тип (0), затем little-endian uint16: primaries R/G/B x,y (6),
     * white point x,y (2), maxDisplayLuminance, minDisplayLuminance, maxCLL, maxFALL.
     *
     * Собирается native-слоем из `AV_FRAME_DATA_MASTERING_DISPLAY_METADATA` и
     * `AV_FRAME_DATA_CONTENT_LIGHT_LEVEL` — аналог `conv_sidedata_to_shatic_hdr_info`
     * из 4XVR.
     */
    val hdrStaticInfo: ByteArray? = null,

    /** Разрядность компонента: 8, 10 или 12. */
    val bitDepth: Int = 8,

    /** Профиль Dolby Vision (5, 7, 8, 9), либо 0 если DoVi нет. */
    val dolbyProfile: Int = 0,

    /** Поток несёт HDR10+ (`AV_FRAME_DATA_DYNAMIC_HDR_PLUS`). */
    val hasHdr10Plus: Boolean = false
) {
    val isHdr: Boolean
        get() = colorTransfer == COLOR_TRANSFER_ST2084 ||
                colorTransfer == COLOR_TRANSFER_HLG ||
                dolbyProfile != 0

    /** Короткая подпись для информационной панели: `HDR10+`, `Dolby Vision`, `HLG`, `HDR10`, `SDR`. */
    val label: String
        get() = when {
            dolbyProfile != 0 -> "Dolby Vision"
            hasHdr10Plus -> "HDR10+"
            colorTransfer == COLOR_TRANSFER_HLG -> "HLG"
            colorTransfer == COLOR_TRANSFER_ST2084 -> "HDR10"
            else -> "SDR"
        }

    /** maxCLL, кд/м², из [hdrStaticInfo]; `null` если метаданных нет. */
    val maxContentLightLevel: Int?
        get() = readU16(HDR_OFFSET_MAX_CLL)

    /** maxFALL, кд/м², из [hdrStaticInfo]. */
    val maxFrameAverageLightLevel: Int?
        get() = readU16(HDR_OFFSET_MAX_FALL)

    /** Максимальная яркость мастеринг-дисплея, кд/м², из [hdrStaticInfo]. */
    val maxDisplayLuminance: Int?
        get() = readU16(HDR_OFFSET_MAX_DISPLAY_LUMINANCE)

    private fun readU16(offset: Int): Int? {
        val info = hdrStaticInfo ?: return null
        if (info.size < offset + 2) return null
        return (info[offset].toInt() and 0xFF) or ((info[offset + 1].toInt() and 0xFF) shl 8)
    }

    // equals/hashCode вручную: в конструкторе есть ByteArray, у data class он
    // сравнивался бы по ссылке.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EngineColorInfo) return false
        return colorStandard == other.colorStandard &&
                colorTransfer == other.colorTransfer &&
                colorRange == other.colorRange &&
                bitDepth == other.bitDepth &&
                dolbyProfile == other.dolbyProfile &&
                hasHdr10Plus == other.hasHdr10Plus &&
                hdrStaticInfo.contentEquals(other.hdrStaticInfo)
    }

    override fun hashCode(): Int {
        var result = colorStandard
        result = 31 * result + colorTransfer
        result = 31 * result + colorRange
        result = 31 * result + bitDepth
        result = 31 * result + dolbyProfile
        result = 31 * result + hasHdr10Plus.hashCode()
        result = 31 * result + (hdrStaticInfo?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "EngineColorInfo(${label}, standard=$colorStandard, transfer=$colorTransfer, " +
                "range=$colorRange, ${bitDepth}bit, staticInfo=${hdrStaticInfo?.size ?: 0}B)"

    companion object {
        const val COLOR_STANDARD_UNSPECIFIED = 0
        const val COLOR_STANDARD_BT709 = 1
        const val COLOR_STANDARD_BT601_PAL = 2
        const val COLOR_STANDARD_BT601_NTSC = 4
        const val COLOR_STANDARD_BT2020 = 6

        const val COLOR_TRANSFER_UNSPECIFIED = 0
        const val COLOR_TRANSFER_LINEAR = 1
        const val COLOR_TRANSFER_SDR_VIDEO = 3
        const val COLOR_TRANSFER_ST2084 = 6
        const val COLOR_TRANSFER_HLG = 7

        const val COLOR_RANGE_UNSPECIFIED = 0
        const val COLOR_RANGE_FULL = 1
        const val COLOR_RANGE_LIMITED = 2

        /** Размер `hdr-static-info` по CTA-861.3. */
        const val HDR_STATIC_INFO_SIZE = 25

        private const val HDR_OFFSET_MAX_DISPLAY_LUMINANCE = 17
        private const val HDR_OFFSET_MAX_CLL = 21
        private const val HDR_OFFSET_MAX_FALL = 23

        val SDR_BT709 = EngineColorInfo(
            colorStandard = COLOR_STANDARD_BT709,
            colorTransfer = COLOR_TRANSFER_SDR_VIDEO,
            colorRange = COLOR_RANGE_LIMITED
        )
    }
}

/** Параметры текущего видеопотока, не связанные с цветом. */
data class EngineVideoFormat(
    val width: Int = 0,
    val height: Int = 0,

    /** Отношение сторон пикселя (`sample_aspect_ratio`), 1.0 для квадратного. */
    val pixelAspectRatio: Float = 1f,

    /** Частота кадров контейнера/потока; 0 если неизвестна. Нужна для AFR. */
    val frameRate: Float = 0f,

    /** Имя кодека FFmpeg: `hevc`, `av1`, `h264`, `vp9`. */
    val codec: String? = null,

    /** MIME для `MediaCodec`: `video/hevc`, `video/av01`. */
    val mimeType: String? = null,

    val bitrate: Int = 0,

    /** Поворот из контейнера, градусы: 0/90/180/270. */
    val rotationDegrees: Int = 0
) {
    val hasSize: Boolean get() = width > 0 && height > 0

    /** Отношение сторон кадра с учётом [pixelAspectRatio]. */
    val displayAspectRatio: Float
        get() = if (hasSize) width * pixelAspectRatio / height else 0f
}
