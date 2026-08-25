/*
 * hw_decoder.cpp — реализация. Обоснование решений — в hw_decoder.h.
 */
#include "hw_decoder.h"

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaError.h>
#include <media/NdkMediaFormat.h>

#include <cstdarg>
#include <cstdio>
#include <cstring>

#include "ddd_log.h"
#include "ff_include.h"
#include "hdr_static_info.h"

namespace ddd {

namespace {

// Ключи `MediaFormat` строками, а не константами NDK: половина нужных помечена
// __INTRODUCED_IN(28) при minSdk 23 (подробно — в hw_decoder.h).
constexpr const char *kKeyMime = "mime";
constexpr const char *kKeyWidth = "width";
constexpr const char *kKeyHeight = "height";
constexpr const char *kKeyColorFormat = "color-format";
constexpr const char *kKeyStride = "stride";
constexpr const char *kKeySliceHeight = "slice-height";
constexpr const char *kKeyCsd0 = "csd-0";
constexpr const char *kKeyHdrStaticInfo = "hdr-static-info";
constexpr const char *kKeyColorStandard = "color-standard";
constexpr const char *kKeyColorTransfer = "color-transfer";
constexpr const char *kKeyColorRange = "color-range";
constexpr const char *kKeyCropLeft = "crop-left";
constexpr const char *kKeyCropTop = "crop-top";
constexpr const char *kKeyCropRight = "crop-right";
constexpr const char *kKeyCropBottom = "crop-bottom";

/**
 * Программные декодеры Android по MIME — третья ступень лестницы `Create`.
 *
 * Почему по именам, а не по флагу «software», — у объявления
 * `SoftwareDecoderName` в заголовке.
 */
struct SoftwareDecoders {
    const char *mime;
    const char *names[2];
};

constexpr SoftwareDecoders kSoftwareDecoders[] = {
    {"video/hevc", {"c2.android.hevc.decoder", "OMX.google.hevc.decoder"}},
    {"video/avc", {"c2.android.avc.decoder", "OMX.google.h264.decoder"}},
    {"video/av01", {"c2.android.av1.decoder", nullptr}},
    {"video/x-vnd.on2.vp9", {"c2.android.vp9.decoder", "OMX.google.vp9.decoder"}},
    {"video/x-vnd.on2.vp8", {"c2.android.vp8.decoder", "OMX.google.vp8.decoder"}},
    {"video/mpeg2", {"c2.android.mpeg2.decoder", "OMX.google.mpeg2.decoder"}},
    {"video/mp4v-es", {"c2.android.mpeg4.decoder", "OMX.google.mpeg4.decoder"}},
    {"video/3gpp", {"c2.android.h263.decoder", "OMX.google.h263.decoder"}},
};

/** Сколько имён перебирать: `c2.android.*`, затем `OMX.google.*`. */
constexpr int kSoftwareAttempts = 2;

void SetError(std::string *error, const char *fmt, ...) {
    if (error == nullptr) return;
    char buf[256];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof buf, fmt, args);
    va_end(args);
    *error = buf;
}

/** Начинается ли буфер со start code Annex-B (`00 00 01` или `00 00 00 01`). */
bool IsAnnexB(const uint8_t *data, int size) {
    if (data == nullptr || size < 4) return false;
    if (data[0] != 0 || data[1] != 0) return false;
    return data[2] == 1 || (data[2] == 0 && data[3] == 1);
}

/**
 * Раскладка кадра по значению `KEY_COLOR_FORMAT`.
 *
 * `COLOR_FormatYUV420Flexible` (0x7F420888) сознательно не распознаётся: это
 * значение имеет смысл только как запрос на входе. Если декодер вернул его и в
 * output format, конкретную раскладку из native не узнать вовсе — она доступна
 * лишь через `Image` в Java. Молча предположить NV12 значило бы получить
 * зелёно-розовую картинку на вендорских тайловых форматах, поэтому это отказ.
 */
bool PixelFormatFromColorFormat(int32_t color_format, FramePixelFormat *out) {
    switch (color_format) {
        case kColorFormatYuv420Planar:
        case kColorFormatYuv420PackedPlanar:
            *out = FramePixelFormat::kYuv420p;
            return true;
        // Все семиплоскостные 8-битные раскладки — NV12: порядок UV, а не VU.
        // NV21 у декодеров в ByteBuffer-режиме не встречается (это формат камеры
        // и `ImageFormat`), но в `FramePixelFormat` он есть ради шага 7.
        case kColorFormatYuv420SemiPlanar:
        case kColorFormatYuv420PackedSemiPlanar:
        case kColorFormatQcomYuv420SemiPlanar:
        case kColorFormatQcomYuv420SemiPlanar32m:
        case kColorFormatTiYuv420PackedSemiPlanar:
            *out = FramePixelFormat::kNv12;
            return true;
        case kColorFormatYuvP010:
            *out = FramePixelFormat::kP010;
            return true;
        default:
            return false;
    }
}

/**
 * Раскладывает плоскости кадра в буфере декодера.
 *
 * Crop применяется сдвигом указателей, а не обрезкой в шейдере: stride в
 * [FrameDesc] есть, и загрузка с ним уже умеет пропускать невидимые байты. По
 * цветности сдвиг делится на прореживание — при чётном crop (а он чётный у всех
 * реальных файлов: HEVC пишет crop в единицах chroma) это точно; на нечётном
 * цветность сдвинется на полпикселя, и это лучше, чем отбросить crop совсем.
 */
void FillPlanes(const DecoderOutput &o, const uint8_t *base, FrameDesc *frame) {
    const FormatInfo info = DescribeFormat(o.format);
    const int bps = info.sixteen_bit() ? 2 : 1;

    frame->format = o.format;
    frame->width = o.width;
    frame->height = o.height;

    const uint8_t *y = base + static_cast<size_t>(o.crop_top) * o.stride +
                       static_cast<size_t>(o.crop_left) * bps;
    frame->plane[0] = y;
    frame->stride[0] = o.stride;

    // Плоскость цветности начинается после slice_height строк яркости — именно
    // slice_height, а не height: невидимые строки выравнивания входят в отступ.
    const uint8_t *chroma = base + static_cast<size_t>(o.stride) * o.slice_height;

    if (info.semiplanar) {
        // Перемежённые UV: отсчётов вдвое меньше, но их два на позицию, поэтому
        // байт на строку столько же, сколько у яркости.
        frame->plane[1] = chroma + static_cast<size_t>(o.crop_top / info.sub_y) * o.stride +
                          static_cast<size_t>(o.crop_left / info.sub_x) * 2 * bps;
        frame->stride[1] = o.stride;
        frame->plane[2] = nullptr;
        frame->stride[2] = 0;
        return;
    }

    const int chroma_stride = o.stride / info.sub_x;
    const int chroma_rows = o.slice_height / info.sub_y;
    const size_t offset = static_cast<size_t>(o.crop_top / info.sub_y) * chroma_stride +
                          static_cast<size_t>(o.crop_left / info.sub_x) * bps;
    frame->plane[1] = chroma + offset;
    frame->stride[1] = chroma_stride;
    frame->plane[2] = chroma + static_cast<size_t>(chroma_stride) * chroma_rows + offset;
    frame->stride[2] = chroma_stride;
}

}  // namespace

const char *SoftwareDecoderName(const char *mime, int attempt) {
    if (mime == nullptr || attempt < 0 || attempt >= kSoftwareAttempts) return nullptr;
    for (const SoftwareDecoders &row : kSoftwareDecoders) {
        if (strcmp(row.mime, mime) == 0) return row.names[attempt];
    }
    return nullptr;
}

HwVideoDecoder *HwVideoDecoder::Create(const DecoderConfig &cfg, std::string *error) {
    if (cfg.mime == nullptr || cfg.par == nullptr) {
        SetError(error, "нет MIME или AVCodecParameters");
        return nullptr;
    }

    HwVideoDecoder *self = new HwVideoDecoder();
    if (!self->PrepareBitstreamFilter(cfg.par, error)) {
        delete self;
        return nullptr;
    }

    // Ступень 1. Конкретный декодер по имени, если он задан вызывающим, — тогда
    // лестницы нет вовсе: спрашивали именно этот компонент, подмена ответа
    // молчанием была бы обманом.
    if (cfg.codec_name != nullptr) {
        std::string why;
        if (self->Start(cfg, cfg.codec_name, cfg.prefer_ten_bit, &why)) {
            self->rung_ = 1;
            return self;
        }
        SetError(error, "%s не поднялся: %s", cfg.codec_name, why.c_str());
        delete self;
        return nullptr;
    }

    std::string first_why;
    if (self->Start(cfg, nullptr, cfg.prefer_ten_bit, &first_why)) {
        self->rung_ = 1;
        return self;
    }
    DDD_LOGW("decoder: %s с color-format не поднялся (%s) — пробуем без ключа", cfg.mime,
             first_why.c_str());

    // Ступень 2. Тот же декодер, но выбор формата за ним. Кадр может прийти
    // 8-битным — это заметит вызывающий по DecoderOutput, здесь важно лишь то,
    // что декодер вообще запустился.
    std::string second_why;
    if (self->Start(cfg, nullptr, false, &second_why)) {
        self->rung_ = 2;
        return self;
    }
    DDD_LOGW("decoder: %s без color-format тоже не поднялся (%s)", cfg.mime, second_why.c_str());

    // Ступень 3. Программные декодеры Android по имени.
    for (int attempt = 0; attempt < kSoftwareAttempts; ++attempt) {
        const char *name = SoftwareDecoderName(cfg.mime, attempt);
        if (name == nullptr) continue;
        std::string why;
        if (self->Start(cfg, name, cfg.prefer_ten_bit, &why)) {
            self->rung_ = 3;
            DDD_LOGI("decoder: поднялся программный %s", name);
            return self;
        }
        DDD_LOGW("decoder: %s не поднялся (%s)", name, why.c_str());
    }

    SetError(error, "ни один декодер для %s не поднялся: %s", cfg.mime, first_why.c_str());
    delete self;
    return nullptr;
}

HwVideoDecoder::~HwVideoDecoder() {
    if (codec_ != nullptr) {
        // stop перед delete: delete на работающем кодеке оставляет буферы в
        // очереди у mediaserver, и следующий Create на том же компоненте
        // получает отказ по занятости — выглядит как «файл не играет со второго
        // раза».
        AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
    }
    if (bsf_ != nullptr) av_bsf_free(&bsf_);
}

bool HwVideoDecoder::PrepareBitstreamFilter(const AVCodecParameters *par, std::string *error) {
    csd_.clear();

    const bool is_h264 = par->codec_id == AV_CODEC_ID_H264;
    const bool is_hevc = par->codec_id == AV_CODEC_ID_HEVC;
    if (!is_h264 && !is_hevc) {
        // Остальным кодекам Annex-B не нужен. csd-0 для них тоже не ставится:
        // формат конфигурации у каждого свой (`av1C`, `vpcC`), и отдать его как
        // есть — вернее всего сломать декодер. Для VP8/VP9/AV1 параметры
        // приходят в самих пакетах; AV1 в MP4 без sequence header в потоке —
        // открытый вопрос шага 7.
        return true;
    }
    if (par->extradata == nullptr || par->extradata_size <= 0) {
        // Сырой поток (.hevc/.h264 без контейнера): VPS/SPS/PPS лежат в первом
        // пакете, MediaCodec принимает их in-band.
        return true;
    }
    if (IsAnnexB(par->extradata, par->extradata_size)) {
        csd_.assign(par->extradata, par->extradata + par->extradata_size);
        return true;
    }

    const char *name = is_hevc ? "hevc_mp4toannexb" : "h264_mp4toannexb";
    const AVBitStreamFilter *filter = av_bsf_get_by_name(name);
    if (filter == nullptr) {
        SetError(error, "в сборке FFmpeg нет фильтра %s", name);
        return false;
    }
    int ret = av_bsf_alloc(filter, &bsf_);
    if (ret < 0) {
        SetError(error, "av_bsf_alloc(%s) = %d", name, ret);
        return false;
    }
    ret = avcodec_parameters_copy(bsf_->par_in, par);
    if (ret < 0) {
        SetError(error, "avcodec_parameters_copy = %d", ret);
        return false;
    }
    // Временная база фильтру нужна формально: PTS уходит в MediaCodec отдельным
    // аргументом в микросекундах, а не через пакет.
    bsf_->time_base_in = AVRational{1, 1000000};
    ret = av_bsf_init(bsf_);
    if (ret < 0) {
        SetError(error, "av_bsf_init(%s) = %d", name, ret);
        return false;
    }
    // Именно здесь появляется csd-0: init переписывает extradata из hvcC/avcC в
    // Annex-B, и это ровно то, что ждёт `MediaFormat`.
    if (bsf_->par_out->extradata != nullptr && bsf_->par_out->extradata_size > 0) {
        csd_.assign(bsf_->par_out->extradata,
                    bsf_->par_out->extradata + bsf_->par_out->extradata_size);
    }
    return true;
}

bool HwVideoDecoder::Start(const DecoderConfig &cfg, const char *codec_name, bool set_color_format,
                           std::string *error) {
    if (codec_ != nullptr) {
        AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
    }
    format_known_ = false;
    eos_sent_ = false;
    surface_output_ = cfg.surface != nullptr;
    output_ = DecoderOutput();

    codec_ = codec_name != nullptr ? AMediaCodec_createCodecByName(codec_name)
                                   : AMediaCodec_createDecoderByType(cfg.mime);
    if (codec_ == nullptr) {
        SetError(error, "нет декодера %s", codec_name != nullptr ? codec_name : cfg.mime);
        return false;
    }

    AMediaFormat *fmt = AMediaFormat_new();
    AMediaFormat_setString(fmt, kKeyMime, cfg.mime);
    AMediaFormat_setInt32(fmt, kKeyWidth, cfg.par->width);
    AMediaFormat_setInt32(fmt, kKeyHeight, cfg.par->height);
    if (set_color_format && !surface_output_) {
        AMediaFormat_setInt32(fmt, kKeyColorFormat,
                              cfg.prefer_ten_bit ? kColorFormatYuvP010
                                                 : kColorFormatYuv420Flexible);
    }
    if (!csd_.empty()) {
        AMediaFormat_setBuffer(fmt, kKeyCsd0, csd_.data(), csd_.size());
    }
    if (cfg.hdr_static_info != nullptr) {
        // 25 байт CTA-861.3. Ради них и собирался `hdr_static_info.cpp`: без них
        // декодер не знает пиковую яркость мастеринга, и тонмаппинг шага 6
        // остаётся «на глаз».
        AMediaFormat_setBuffer(fmt, kKeyHdrStaticInfo, cfg.hdr_static_info, kHdrStaticInfoSize);
    }
    if (cfg.color_standard != 0) AMediaFormat_setInt32(fmt, kKeyColorStandard, cfg.color_standard);
    if (cfg.color_transfer != 0) AMediaFormat_setInt32(fmt, kKeyColorTransfer, cfg.color_transfer);
    if (cfg.color_range != 0) AMediaFormat_setInt32(fmt, kKeyColorRange, cfg.color_range);

    const media_status_t configured = AMediaCodec_configure(codec_, fmt, cfg.surface, nullptr, 0);
    AMediaFormat_delete(fmt);
    if (configured != AMEDIA_OK) {
        SetError(error, "configure = %d", static_cast<int>(configured));
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
        return false;
    }

    const media_status_t started = AMediaCodec_start(codec_);
    if (started != AMEDIA_OK) {
        SetError(error, "start = %d", static_cast<int>(started));
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
        return false;
    }

    name_ = codec_name != nullptr ? codec_name : "?";
    if (codec_name == nullptr) {
        // Имя выбранного по MIME компонента — единственный способ понять, HW это
        // или SW, и оно же нужно в баг-репортах: «не играет на Pico» без имени
        // декодера не отлаживается.
        if (__builtin_available(android 28, *)) {
            char *reported = nullptr;
            if (AMediaCodec_getName(codec_, &reported) == AMEDIA_OK && reported != nullptr) {
                name_ = reported;
                AMediaCodec_releaseName(codec_, reported);
            }
        }
    }
    DDD_LOGI("decoder: %s, mime=%s, %dx%d, csd=%zu Б, output=%s, color-format=%s", name_.c_str(), cfg.mime,
             cfg.par->width, cfg.par->height, csd_.size(),
             surface_output_ ? "Surface" : "ByteBuffer",
             set_color_format ? (cfg.prefer_ten_bit ? "P010" : "flexible") : "по умолчанию");
    return true;
}

HwVideoDecoder::Feed HwVideoDecoder::Push(AVPacket *pkt, int64_t pts_us, int timeout_ms) {
    if (pkt == nullptr) return Feed::kError;
    if (!pending_.empty()) {
        // Контракт: сначала PushPending. Иначе прошлый кадр потерялся бы молча.
        DDD_LOGE("decoder: Push при непустом остатке (%zu Б)", pending_.size());
        av_packet_free(&pkt);
        return Feed::kError;
    }

    ++packets_in_;
    pending_pts_us_ = pts_us;

    if (bsf_ != nullptr) {
        const int sent = av_bsf_send_packet(bsf_, pkt);
        // Пакет отдан фильтру в любом случае: send_packet забирает ссылку на
        // данные даже при ошибке, и держать структуру дальше незачем.
        av_packet_free(&pkt);
        if (sent < 0) {
            DDD_LOGE("decoder: av_bsf_send_packet = %d", sent);
            return Feed::kError;
        }
        AVPacket *out = av_packet_alloc();
        if (out == nullptr) return Feed::kError;
        // Один вход может дать несколько выходов (например, SPS/PPS отдельным
        // пакетом). Annex-B склеивается конкатенацией, поэтому всё, что вышло,
        // уходит в декодер одним access unit — с тем же PTS.
        while (av_bsf_receive_packet(bsf_, out) == 0) {
            pending_.insert(pending_.end(), out->data, out->data + out->size);
            av_packet_unref(out);
        }
        av_packet_free(&out);
    } else {
        pending_.assign(pkt->data, pkt->data + pkt->size);
        av_packet_free(&pkt);
    }

    if (pending_.empty()) return Feed::kQueued;  // фильтр проглотил пакет целиком
    return WritePending(timeout_ms);
}

HwVideoDecoder::Feed HwVideoDecoder::PushPending(int timeout_ms) {
    if (pending_.empty()) return Feed::kQueued;
    return WritePending(timeout_ms);
}

HwVideoDecoder::Feed HwVideoDecoder::WritePending(int timeout_ms) {
    if (codec_ == nullptr) return Feed::kError;

    const ssize_t index =
        AMediaCodec_dequeueInputBuffer(codec_, static_cast<int64_t>(timeout_ms) * 1000);
    if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER) return Feed::kBusy;
    if (index < 0) {
        DDD_LOGE("decoder: dequeueInputBuffer = %zd", index);
        return Feed::kError;
    }

    size_t capacity = 0;
    uint8_t *buffer = AMediaCodec_getInputBuffer(codec_, static_cast<size_t>(index), &capacity);
    if (buffer == nullptr) {
        DDD_LOGE("decoder: getInputBuffer(%zd) вернул null", index);
        return Feed::kError;
    }
    if (capacity < pending_.size()) {
        // Разрезать access unit нельзя: в ByteBuffer-режиме MediaCodec ждёт
        // целый кадр. Возвращаем буфер пустым, чтобы не оставить его занятым, и
        // сообщаем размеры — это единственный способ понять причину по логу.
        DDD_LOGE("decoder: входной буфер %zu Б мал для пакета %zu Б", capacity, pending_.size());
        AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(index), 0, 0, pending_pts_us_, 0);
        pending_.clear();
        return Feed::kError;
    }

    memcpy(buffer, pending_.data(), pending_.size());
    const media_status_t queued = AMediaCodec_queueInputBuffer(
        codec_, static_cast<size_t>(index), 0, pending_.size(), pending_pts_us_, 0);
    pending_.clear();
    if (queued != AMEDIA_OK) {
        DDD_LOGE("decoder: queueInputBuffer = %d", static_cast<int>(queued));
        return Feed::kError;
    }
    return Feed::kQueued;
}

HwVideoDecoder::Feed HwVideoDecoder::PushEos(int timeout_ms) {
    if (codec_ == nullptr) return Feed::kError;
    if (eos_sent_) return Feed::kQueued;

    const ssize_t index =
        AMediaCodec_dequeueInputBuffer(codec_, static_cast<int64_t>(timeout_ms) * 1000);
    if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER) return Feed::kBusy;
    if (index < 0) {
        DDD_LOGE("decoder: dequeueInputBuffer(eos) = %zd", index);
        return Feed::kError;
    }
    const media_status_t queued =
        AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(index), 0, 0, 0,
                                     AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
    if (queued != AMEDIA_OK) {
        DDD_LOGE("decoder: queueInputBuffer(eos) = %d", static_cast<int>(queued));
        return Feed::kError;
    }
    eos_sent_ = true;
    return Feed::kQueued;
}

bool HwVideoDecoder::ReadOutputFormat(std::string *error) {
    AMediaFormat *fmt = AMediaCodec_getOutputFormat(codec_);
    if (fmt == nullptr) {
        SetError(error, "getOutputFormat вернул null");
        return false;
    }

    DecoderOutput out;
    int32_t value = 0;
    if (AMediaFormat_getInt32(fmt, kKeyColorFormat, &value)) out.color_format = value;
    if (AMediaFormat_getInt32(fmt, kKeyWidth, &value)) out.width = value;
    if (AMediaFormat_getInt32(fmt, kKeyHeight, &value)) out.height = value;

    if (!surface_output_ && !PixelFormatFromColorFormat(out.color_format, &out.format)) {
        SetError(error, "неизвестный color-format 0x%x", out.color_format);
        AMediaFormat_delete(fmt);
        return false;
    }
    const FormatInfo info = DescribeFormat(out.format);
    const int bps = !surface_output_ && info.sixteen_bit() ? 2 : 1;

    int32_t stride = 0;
    int32_t slice = 0;
    const bool has_stride = AMediaFormat_getInt32(fmt, kKeyStride, &stride) && stride > 0;
    const bool has_slice = AMediaFormat_getInt32(fmt, kKeySliceHeight, &slice) && slice > 0;
    out.stride_reported = has_stride && has_slice;
    // Додуманные значения — не «разумный дефолт», а последняя попытка: у
    // выровненных раскладок (QCOM 32m) они неверны, и картинка поедет по
    // диагонали. Поэтому факт додумывания виден в [stride_reported] и попадает в
    // лог: при косой картинке это первое, на что надо смотреть.
    out.stride = has_stride ? stride : out.width * bps;
    out.slice_height = has_slice ? slice : out.height;

    int32_t left = 0;
    int32_t top = 0;
    int32_t right = 0;
    int32_t bottom = 0;
    if (AMediaFormat_getInt32(fmt, kKeyCropLeft, &left) &&
        AMediaFormat_getInt32(fmt, kKeyCropTop, &top) &&
        AMediaFormat_getInt32(fmt, kKeyCropRight, &right) &&
        AMediaFormat_getInt32(fmt, kKeyCropBottom, &bottom) && right >= left && bottom >= top) {
        // Границы включительные — отсюда +1. Классическая ошибка на единицу
        // здесь даёт полосу шириной в пиксель по краю кадра.
        out.crop_left = left;
        out.crop_top = top;
        out.width = right - left + 1;
        out.height = bottom - top + 1;
    }

    AMediaFormat_delete(fmt);

    if (out.width <= 0 || out.height <= 0) {
        SetError(error, "размер кадра %dx%d", out.width, out.height);
        return false;
    }

    output_ = out;
    format_known_ = true;
    DDD_LOGI("decoder: output %dx%d, color-format 0x%x, stride %d (%s), slice %d, crop %d,%d",
             out.width, out.height, out.color_format, out.stride,
             out.stride_reported ? "от декодера" : "додуман", out.slice_height, out.crop_left,
             out.crop_top);
    return true;
}

HwVideoDecoder::Pull HwVideoDecoder::DequeueFrame(DecodedFrame *out, int timeout_ms) {
    if (codec_ == nullptr || out == nullptr) return Pull::kError;

    AMediaCodecBufferInfo info = {};
    const ssize_t index =
        AMediaCodec_dequeueOutputBuffer(codec_, &info, static_cast<int64_t>(timeout_ms) * 1000);

    if (index >= 0) {
        const bool eos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
        if (surface_output_) {
            if (eos) {
                AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(index), false);
                return Pull::kEos;
            }
            if (!format_known_) {
                std::string why;
                if (!ReadOutputFormat(&why)) {
                    DDD_LOGE("decoder: Surface-формат вывода не разобран: %s", why.c_str());
                    AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(index), false);
                    return Pull::kError;
                }
            }
            out->frame.width = output_.width;
            out->frame.height = output_.height;
            out->pts_us = info.presentationTimeUs;
            out->index = index;
            ++frames_out_;
            return Pull::kFrame;
        }
        if (info.size <= 0) {
            // Буфер без данных бывает и при EOS, и при выдаче служебных данных:
            // вернуть его надо в обоих случаях, иначе он останется занятым.
            AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(index), false);
            return eos ? Pull::kEos : Pull::kAgain;
        }
        if (!format_known_) {
            // На части устройств INFO_OUTPUT_FORMAT_CHANGED до первого кадра не
            // приходит вовсе — формат приходится читать здесь.
            std::string why;
            if (!ReadOutputFormat(&why)) {
                DDD_LOGE("decoder: формат вывода не разобран: %s", why.c_str());
                AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(index), false);
                return Pull::kError;
            }
        }

        size_t capacity = 0;
        uint8_t *buffer = AMediaCodec_getOutputBuffer(codec_, static_cast<size_t>(index), &capacity);
        if (buffer == nullptr) {
            DDD_LOGE("decoder: getOutputBuffer(%zd) вернул null", index);
            AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(index), false);
            return Pull::kError;
        }

        FillPlanes(output_, buffer + info.offset, &out->frame);
        out->pts_us = info.presentationTimeUs;
        out->index = index;
        ++frames_out_;
        return Pull::kFrame;
    }

    switch (index) {
        case AMEDIACODEC_INFO_TRY_AGAIN_LATER:
            return Pull::kAgain;
        case AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED: {
            std::string why;
            if (!ReadOutputFormat(&why)) {
                DDD_LOGE("decoder: формат вывода не разобран: %s", why.c_str());
                return Pull::kError;
            }
            return Pull::kAgain;
        }
        case AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED:
            // Устаревшее событие (до API 21 менялся массив буферов). В NDK
            // буферы берутся по индексу, так что делать нечего.
            return Pull::kAgain;
        default:
            DDD_LOGE("decoder: dequeueOutputBuffer = %zd", index);
            return Pull::kError;
    }
}

void HwVideoDecoder::ReleaseFrame(const DecodedFrame &frame) {
    if (codec_ == nullptr || frame.index < 0) return;
    AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(frame.index), false);
}

bool HwVideoDecoder::RenderFrame(const DecodedFrame &frame) {
    if (codec_ == nullptr || frame.index < 0 || !surface_output_) return false;
    return AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(frame.index), true) ==
           AMEDIA_OK;
}

bool HwVideoDecoder::Flush() {
    if (codec_ == nullptr) return false;
    pending_.clear();
    eos_sent_ = false;
    if (bsf_ != nullptr) av_bsf_flush(bsf_);
    const media_status_t st = AMediaCodec_flush(codec_);
    if (st != AMEDIA_OK) {
        DDD_LOGE("decoder: flush = %d", static_cast<int>(st));
        return false;
    }
    // format_known_ намеренно не сбрасывается: flush не меняет раскладку вывода,
    // а сброс заставил бы перечитывать формат на первом же кадре после seek —
    // там его ещё может не быть, и кадр был бы отброшен как ошибка.
    return true;
}

}  // namespace ddd
