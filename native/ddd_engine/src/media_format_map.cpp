/*
 * media_format_map.cpp — таблицы перевода. См. media_format_map.h.
 */
#include "media_format_map.h"

namespace ddd {

int ColorStandardFromFf(AVColorPrimaries primaries, AVColorSpace space) {
    switch (primaries) {
        case AVCOL_PRI_BT709:
            return kColorStandardBt709;
        case AVCOL_PRI_BT470BG:  // BT.601-6 625 / PAL
            return kColorStandardBt601Pal;
        case AVCOL_PRI_SMPTE170M:
        case AVCOL_PRI_SMPTE240M:
            return kColorStandardBt601Ntsc;
        case AVCOL_PRI_BT2020:
            return kColorStandardBt2020;
        default:
            break;
    }

    // Праймериз не указаны — идём по матрице.
    switch (space) {
        case AVCOL_SPC_BT709:
            return kColorStandardBt709;
        case AVCOL_SPC_BT470BG:
            return kColorStandardBt601Pal;
        case AVCOL_SPC_SMPTE170M:
        case AVCOL_SPC_SMPTE240M:
            return kColorStandardBt601Ntsc;
        case AVCOL_SPC_BT2020_NCL:
        case AVCOL_SPC_BT2020_CL:
            return kColorStandardBt2020;
        default:
            return kColorStandardUnspecified;
    }
}

int ColorTransferFromFf(AVColorTransferCharacteristic trc) {
    switch (trc) {
        case AVCOL_TRC_SMPTE2084:
            return kColorTransferSt2084;
        case AVCOL_TRC_ARIB_STD_B67:
            return kColorTransferHlg;
        case AVCOL_TRC_LINEAR:
            return kColorTransferLinear;
        // Всё остальное с гаммой — SDR: у MediaFormat нет отдельных значений для
        // BT.470, sRGB и прочих кривых, и для тонмаппинга разница несущественна.
        case AVCOL_TRC_BT709:
        case AVCOL_TRC_GAMMA22:
        case AVCOL_TRC_GAMMA28:
        case AVCOL_TRC_SMPTE170M:
        case AVCOL_TRC_SMPTE240M:
        case AVCOL_TRC_IEC61966_2_1:
        case AVCOL_TRC_IEC61966_2_4:
        case AVCOL_TRC_BT1361_ECG:
        case AVCOL_TRC_BT2020_10:
        case AVCOL_TRC_BT2020_12:
            return kColorTransferSdrVideo;
        default:
            return kColorTransferUnspecified;
    }
}

int ColorRangeFromFf(AVColorRange range) {
    switch (range) {
        case AVCOL_RANGE_JPEG:
            return kColorRangeFull;
        case AVCOL_RANGE_MPEG:
            return kColorRangeLimited;
        default:
            return kColorRangeUnspecified;
    }
}

const char *MimeFromCodecId(AVCodecID id) {
    switch (id) {
        case AV_CODEC_ID_H264:
            return "video/avc";
        case AV_CODEC_ID_HEVC:
            return "video/hevc";
        case AV_CODEC_ID_AV1:
            return "video/av01";
        case AV_CODEC_ID_VP8:
            return "video/x-vnd.on2.vp8";
        case AV_CODEC_ID_VP9:
            return "video/x-vnd.on2.vp9";
        case AV_CODEC_ID_MPEG4:
            return "video/mp4v-es";
        case AV_CODEC_ID_MPEG2VIDEO:
            return "video/mpeg2";
        case AV_CODEC_ID_MPEG1VIDEO:
            return "video/mpeg2";  // Android не различает MPEG-1 и MPEG-2
        case AV_CODEC_ID_H263:
            return "video/3gpp";
        case AV_CODEC_ID_MJPEG:
            return "video/x-motion-jpeg";
        // AV_CODEC_ID_VVC сознательно без MIME: в AOSP константы для H.266 нет,
        // а HW-декодера VVC нет ни на Pixel 6, ни на XR2 Gen 2 (проверено по
        // media_codecs_*.xml, UNIFIED-ENGINE.md §2). Значит — только SW-путь.
        default:
            return nullptr;
    }
}

}  // namespace ddd
