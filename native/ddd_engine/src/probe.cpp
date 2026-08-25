/*
 * probe.cpp — реализация пробинга. См. probe.h.
 */
#include "probe.h"

#include <cmath>
#include <cstring>

#include "ddd_log.h"
#include "ff_include.h"
#include "media_format_map.h"

namespace ddd {
namespace {

const AVPacketSideData *Side(const AVCodecParameters *par, AVPacketSideDataType type) {
    return av_packet_side_data_get(par->coded_side_data, par->nb_coded_side_data, type);
}

std::string DictValue(const AVDictionary *meta, const char *key) {
    const AVDictionaryEntry *e = av_dict_get(meta, key, nullptr, 0);
    return (e != nullptr && e->value != nullptr) ? std::string(e->value) : std::string();
}

std::string CodecName(AVCodecID id) {
    const AVCodecDescriptor *d = avcodec_descriptor_get(id);
    return d != nullptr && d->name != nullptr ? std::string(d->name) : std::string("unknown");
}

/**
 * Разрядность компонента.
 *
 * `par->format` до открытия декодера бывает `AV_PIX_FMT_NONE` (например, у HEVC в
 * MPEG-TS), поэтому нужны запасные пути. Порядок важен: разрядность решает, идти
 * ли в 10-битный путь текстуры (шаг 5), и ошибка здесь означает молчаливую
 * деградацию HDR-файла до 8 бит — то есть ровно ту проблему, из-за которой
 * пишется движок.
 */
int BitDepth(const AVCodecParameters *par) {
    if (par->format != AV_PIX_FMT_NONE) {
        const AVPixFmtDescriptor *d = av_pix_fmt_desc_get(static_cast<AVPixelFormat>(par->format));
        if (d != nullptr && d->comp[0].depth > 0) return d->comp[0].depth;
    }
    if (par->bits_per_raw_sample > 0) return par->bits_per_raw_sample;

    // По профилю: Main 10 у HEVC и VP9 Profile 2 — это гарантированно 10 бит.
    if (par->codec_id == AV_CODEC_ID_HEVC && par->profile == AV_PROFILE_HEVC_MAIN_10) return 10;
    if (par->codec_id == AV_CODEC_ID_VP9 && par->profile == AV_PROFILE_VP9_2) return 10;
    return 8;
}

int Rotation(const AVCodecParameters *par) {
    const AVPacketSideData *sd = Side(par, AV_PKT_DATA_DISPLAYMATRIX);
    if (sd == nullptr || sd->size < 9 * sizeof(int32_t)) return 0;

    // Знак: av_display_rotation_get возвращает поворот, который матрица применяет
    // к кадру; поворот, который надо применить при выводе, — обратный. Ровно так
    // считает и ffmpeg CLI.
    const double deg = -av_display_rotation_get(reinterpret_cast<const int32_t *>(sd->data));
    if (std::isnan(deg)) return 0;

    int r = static_cast<int>(std::lround(deg)) % 360;
    if (r < 0) r += 360;
    // MediaFormat.KEY_ROTATION допускает только кратные 90.
    return (r / 90) * 90 % 360;
}

float FrameRate(const AVStream *st) {
    if (st->avg_frame_rate.num > 0 && st->avg_frame_rate.den > 0)
        return static_cast<float>(av_q2d(st->avg_frame_rate));
    if (st->r_frame_rate.num > 0 && st->r_frame_rate.den > 0)
        return static_cast<float>(av_q2d(st->r_frame_rate));
    return 0.f;
}

void FillColor(const AVCodecParameters *par, ProbeColorInfo *out) {
    out->color_standard = ColorStandardFromFf(par->color_primaries, par->color_space);
    out->color_transfer = ColorTransferFromFf(par->color_trc);
    out->color_range = ColorRangeFromFf(par->color_range);
    out->bit_depth = BitDepth(par);

    const AVMasteringDisplayMetadata *mastering = nullptr;
    const AVContentLightMetadata *light = nullptr;

    if (const AVPacketSideData *sd = Side(par, AV_PKT_DATA_MASTERING_DISPLAY_METADATA))
        mastering = reinterpret_cast<const AVMasteringDisplayMetadata *>(sd->data);
    if (const AVPacketSideData *sd = Side(par, AV_PKT_DATA_CONTENT_LIGHT_LEVEL))
        light = reinterpret_cast<const AVContentLightMetadata *>(sd->data);

    out->has_static_info = BuildHdrStaticInfo(mastering, light, out->static_info);

    if (const AVPacketSideData *sd = Side(par, AV_PKT_DATA_DOVI_CONF)) {
        const auto *dv = reinterpret_cast<const AVDOVIDecoderConfigurationRecord *>(sd->data);
        out->dolby_profile = dv->dv_profile;
        DDD_LOGI("probe: Dolby Vision profile=%d level=%d rpu=%d el=%d bl=%d compat=%d",
                 dv->dv_profile, dv->dv_level, dv->rpu_present_flag, dv->el_present_flag,
                 dv->bl_present_flag, dv->dv_bl_signal_compatibility_id);
    }

    // Предупреждение ровно про тот случай, который на гарнитуре даёт вымытую
    // картинку: передаточная PQ/HLG есть, а данных для тонмаппера нет.
    const bool hdr_transfer = out->color_transfer == kColorTransferSt2084 ||
                              out->color_transfer == kColorTransferHlg;
    if (hdr_transfer && !out->has_static_info)
        DDD_LOGW("probe: transfer=%d (PQ/HLG), но mastering/CLL в контейнере нет — "
                 "тонмаппинг пойдёт по значениям по умолчанию",
                 out->color_transfer);
}

/** Стереораскладка из side data. Подробности про MV-HEVC — в комментарии внутри. */
StereoLayout DetectStereo(const AVStream *st) {
    const AVPacketSideData *sd = Side(st->codecpar, AV_PKT_DATA_STEREO3D);
    if (sd != nullptr) {
        const auto *s = reinterpret_cast<const AVStereo3D *>(sd->data);
        switch (s->type) {
            case AV_STEREO3D_SIDEBYSIDE:
            case AV_STEREO3D_SIDEBYSIDE_QUINCUNX:
                return StereoLayout::kSideBySide;
            case AV_STEREO3D_TOPBOTTOM:
                return StereoLayout::kOverUnder;
            case AV_STEREO3D_UNSPEC:
                // Apple Spatial Video: бокс `eyes`/`stri` в MP4 сообщает, что в
                // потоке есть оба глаза, но упаковки в кадре нет — виды лежат
                // отдельными слоями MV-HEVC. FFmpeg отражает это как
                // type=UNSPEC + view=PACKED (см. mov_read_eyes в libavformat/mov.c).
                if (s->view == AV_STEREO3D_VIEW_PACKED && st->codecpar->codec_id == AV_CODEC_ID_HEVC)
                    return StereoLayout::kMvHevc;
                return StereoLayout::kMono;
            case AV_STEREO3D_FRAMESEQUENCE:
            case AV_STEREO3D_CHECKERBOARD:
            case AV_STEREO3D_LINES:
            case AV_STEREO3D_COLUMNS:
                // Реально встречается редко; до шага 11 честнее играть как моно,
                // чем отдать в шейдер раскладку, которой он не умеет.
                DDD_LOGW("probe: стереораскладка %d пока не поддержана, играем как моно", s->type);
                return StereoLayout::kMono;
            default:
                return StereoLayout::kMono;
        }
    }

    // Запасной путь: тег `stereo_mode` в Matroska у части файлов остаётся только
    // в метаданных дорожки.
    const std::string mode = DictValue(st->metadata, "stereo_mode");
    if (mode == "left_right" || mode == "right_left") return StereoLayout::kSideBySide;
    if (mode == "top_bottom" || mode == "bottom_top") return StereoLayout::kOverUnder;
    return StereoLayout::kMono;
}

Projection DetectProjection(const AVStream *st) {
    const AVPacketSideData *sd = Side(st->codecpar, AV_PKT_DATA_SPHERICAL);
    if (sd == nullptr) return Projection::kFlat;

    const auto *sph = reinterpret_cast<const AVSphericalMapping *>(sd->data);
    switch (sph->projection) {
        case AV_SPHERICAL_EQUIRECTANGULAR:
            return Projection::kEquirect360;
        case AV_SPHERICAL_CUBEMAP:
            return Projection::kCubemap;
        case AV_SPHERICAL_EQUIRECTANGULAR_TILE: {
            // Границы заданы в 0.32 fixed point как доля кадра, отрезанная слева и
            // справа. VR180 — это ровно половина сферы по горизонтали.
            const double kFull = 4294967296.0;  // 2^32
            const double cut = (static_cast<double>(sph->bound_left) +
                                static_cast<double>(sph->bound_right)) / kFull;
            const double coverage = 1.0 - cut;
            return coverage <= 0.6 ? Projection::kEquirect180 : Projection::kEquirect360;
        }
        default:
            return Projection::kFlat;
    }
}

}  // namespace

bool Probe(AVFormatContext *fc, ProbeResult *out) {
    if (fc == nullptr || out == nullptr) return false;

    const int r = avformat_find_stream_info(fc, nullptr);
    if (r < 0) {
        char err[AV_ERROR_MAX_STRING_SIZE] = {0};
        av_strerror(r, err, sizeof err);
        DDD_LOGE("probe: avformat_find_stream_info: %s", err);
        return false;
    }

    out->container.format = fc->iformat != nullptr && fc->iformat->name != nullptr
                                ? fc->iformat->name : "";
    out->container.long_name = fc->iformat != nullptr && fc->iformat->long_name != nullptr
                                  ? fc->iformat->long_name : "";
    out->container.duration_us = fc->duration == AV_NOPTS_VALUE ? 0 : fc->duration;
    out->container.bitrate = fc->bit_rate;
    out->container.seekable = fc->pb != nullptr ? fc->pb->seekable != 0 : false;

    for (unsigned i = 0; i < fc->nb_streams; ++i) {
        AVStream *st = fc->streams[i];
        const AVCodecParameters *par = st->codecpar;
        const bool is_default = (st->disposition & AV_DISPOSITION_DEFAULT) != 0;
        const bool is_forced = (st->disposition & AV_DISPOSITION_FORCED) != 0;

        switch (par->codec_type) {
            case AVMEDIA_TYPE_VIDEO: {
                if ((st->disposition & AV_DISPOSITION_ATTACHED_PIC) != 0) {
                    DDD_LOGD("probe: поток %u — обложка, пропущен", i);
                    continue;
                }
                ProbeVideoTrack t;
                t.stream_index = static_cast<int>(i);
                t.width = par->width;
                t.height = par->height;
                t.sar_num = st->sample_aspect_ratio.num != 0 ? st->sample_aspect_ratio.num
                                                             : par->sample_aspect_ratio.num;
                t.sar_den = st->sample_aspect_ratio.den != 0 ? st->sample_aspect_ratio.den
                                                             : par->sample_aspect_ratio.den;
                // Неуказанный SAR FFmpeg отдаёт как 0:1 (встречается, например, в
                // MP4 от Dolby-энкодеров). Наружу отдаём 1:1: 0 в числителе
                // означал бы нулевую ширину кадра у любого, кто честно применит
                // соотношение при расчёте геометрии.
                if (t.sar_num <= 0 || t.sar_den <= 0) {
                    t.sar_num = 1;
                    t.sar_den = 1;
                }
                t.frame_rate = FrameRate(st);
                t.bitrate = static_cast<int>(par->bit_rate);
                t.codec = CodecName(par->codec_id);
                if (const char *mime = MimeFromCodecId(par->codec_id)) t.mime = mime;
                t.profile = par->profile;
                t.level = par->level;
                t.rotation = Rotation(par);
                t.bit_depth = BitDepth(par);
                t.is_default = is_default;
                out->video.push_back(t);
                break;
            }
            case AVMEDIA_TYPE_AUDIO: {
                ProbeAudioTrack t;
                t.stream_index = static_cast<int>(i);
                t.codec = CodecName(par->codec_id);
                if (const char *p = avcodec_profile_name(par->codec_id, par->profile))
                    t.profile = p;
                t.channels = par->ch_layout.nb_channels;
                t.sample_rate = par->sample_rate;
                t.bitrate = static_cast<int>(par->bit_rate);
                t.language = DictValue(st->metadata, "language");
                t.title = DictValue(st->metadata, "title");
                if (t.title.empty()) t.title = DictValue(st->metadata, "handler_name");
                t.is_default = is_default;
                t.is_forced = is_forced;
                out->audio.push_back(t);
                break;
            }
            case AVMEDIA_TYPE_SUBTITLE: {
                ProbeSubtitleTrack t;
                t.stream_index = static_cast<int>(i);
                t.codec = CodecName(par->codec_id);
                t.language = DictValue(st->metadata, "language");
                t.title = DictValue(st->metadata, "title");
                t.is_default = is_default;
                t.is_forced = is_forced;
                const AVCodecDescriptor *d = avcodec_descriptor_get(par->codec_id);
                t.is_bitmap = d != nullptr && (d->props & AV_CODEC_PROP_BITMAP_SUB) != 0;
                out->subtitle.push_back(t);
                break;
            }
            default:
                // DATA и ATTACHMENT: см. комментарий к Probe в probe.h.
                DDD_LOGD("probe: поток %u типа %s пропущен", i,
                         av_get_media_type_string(par->codec_type)
                             ? av_get_media_type_string(par->codec_type) : "?");
                break;
        }
    }

    out->best_video_index = av_find_best_stream(fc, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    out->best_audio_index = av_find_best_stream(fc, AVMEDIA_TYPE_AUDIO, -1,
                                                out->best_video_index, nullptr, 0);
    out->best_subtitle_index = av_find_best_stream(fc, AVMEDIA_TYPE_SUBTITLE, -1,
                                                   out->best_audio_index, nullptr, 0);
    if (out->best_video_index < 0) out->best_video_index = -1;
    if (out->best_audio_index < 0) out->best_audio_index = -1;
    if (out->best_subtitle_index < 0) out->best_subtitle_index = -1;

    if (out->best_video_index >= 0) {
        AVStream *st = fc->streams[out->best_video_index];
        FillColor(st->codecpar, &out->color);
        out->stereo = DetectStereo(st);
        out->projection = DetectProjection(st);

        if (out->color.dolby_profile > 0) {
            out->color.dolby_stream_index = out->best_video_index;
        } else {
            // Профиль 7 держит `dvvC` на дорожке enhancement layer, а не на базовой.
            // Ищем по всем видеопотокам, иначе двухслойный DV не отличить от HDR10.
            for (unsigned i = 0; i < fc->nb_streams; ++i) {
                const AVCodecParameters *par = fc->streams[i]->codecpar;
                if (par->codec_type != AVMEDIA_TYPE_VIDEO) continue;
                if (static_cast<int>(i) == out->best_video_index) continue;
                if (const AVPacketSideData *sd = Side(par, AV_PKT_DATA_DOVI_CONF)) {
                    const auto *dv =
                        reinterpret_cast<const AVDOVIDecoderConfigurationRecord *>(sd->data);
                    out->color.dolby_profile = dv->dv_profile;
                    out->color.dolby_stream_index = static_cast<int>(i);
                    DDD_LOGI("probe: Dolby Vision profile=%d на потоке %u (enhancement layer), "
                             "базовый поток %d",
                             dv->dv_profile, i, out->best_video_index);
                    break;
                }
            }
        }
    }

    DDD_LOGI("probe: %s, %.3f с, дорожек V/A/S = %zu/%zu/%zu, основное видео=%d, "
             "цвет std=%d trc=%d range=%d %dbit, hdr-static=%d, DV=%d, стерео=%d, проекция=%d",
             out->container.format.c_str(), out->container.duration_us / 1e6,
             out->video.size(), out->audio.size(), out->subtitle.size(), out->best_video_index,
             out->color.color_standard, out->color.color_transfer, out->color.color_range,
             out->color.bit_depth, static_cast<int>(out->color.has_static_info),
             out->color.dolby_profile, static_cast<int>(out->stereo),
             static_cast<int>(out->projection));
    return true;
}

}  // namespace ddd
