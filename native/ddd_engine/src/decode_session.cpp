/*
 * decode_session.cpp — реализация. Обоснование решений — в decode_session.h.
 */
#include "decode_session.h"

#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <thread>

#include "ddd_log.h"
#include "dovi_rpu_parser.h"
#include "ff_include.h"
#include "media_format_map.h"
#include "sw_decoder.h"

namespace ddd {

namespace {

void SetError(std::string *error, const char *fmt, ...) {
    if (error == nullptr) return;
    char buf[256];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof buf, fmt, args);
    va_end(args);
    *error = buf;
}

/**
 * Матрица шейдера по значению `KEY_COLOR_STANDARD`.
 *
 * `ColorStandard` в рендерере — это именно матрица (три варианта), а
 * `MediaFormat` различает ещё и PAL/NTSC: у обоих матрица BT.601, разница лишь в
 * праймериз, а праймериз — забота тонмаппинга (шаг 6), не конверсии.
 */
ColorStandard StandardFromKey(int key, int height) {
    switch (key) {
        case kColorStandardBt601Pal:
        case kColorStandardBt601Ntsc:
            return ColorStandard::kBt601;
        case kColorStandardBt2020:
            return ColorStandard::kBt2020;
        case kColorStandardBt709:
            return ColorStandard::kBt709;
        default:
            // Стандарт не указан — а он не указан у большинства файлов. По высоте:
            // до 576 строк включительно это SD, то есть BT.601, выше — BT.709.
            // Это не эвристика «на всякий случай», а то же правило, по которому
            // работают и ExoPlayer, и VLC; без него SD-контент получает матрицу
            // HD и заметно уезжает в зелень.
            return height > 0 && height <= 576 ? ColorStandard::kBt601 : ColorStandard::kBt709;
    }
}

}  // namespace

DecodeSession *DecodeSession::Create(DemuxSession *demux, const DecodeSessionConfig &cfg,
                                     std::string *error) {
    if (demux == nullptr) {
        SetError(error, "нет сессии демукса");
        return nullptr;
    }

    const ProbeResult &probe = demux->probe();
    const int index = cfg.stream_index >= 0 ? cfg.stream_index : probe.best_video_index;
    if (index < 0) {
        SetError(error, "в источнике нет видеопотока");
        return nullptr;
    }

    const ProbeVideoTrack *track = nullptr;
    for (const ProbeVideoTrack &t : probe.video) {
        if (t.stream_index == index) {
            track = &t;
            break;
        }
    }
    if (track == nullptr) {
        SetError(error, "поток %d не видеопоток", index);
        return nullptr;
    }

    DecodeSession *self = new DecodeSession();
    self->demux_ = demux;
    self->stream_index_ = index;
    self->stream_bit_depth_ = track->bit_depth;
    self->allow_escalation_ = cfg.allow_software_escalation;
    self->force_software_ = cfg.force_software;
    self->surface_ = cfg.surface;
    self->codec_name_ = cfg.codec_name != nullptr ? cfg.codec_name : "";

    self->par_ = demux->CopyCodecParameters(index);
    if (self->par_ == nullptr) {
        SetError(error, "не скопировать параметры потока %d", index);
        delete self;
        return nullptr;
    }
    self->dovi_parser_ = DoviRpuParser::Create(self->par_);

    // MIME берётся из таблицы, а не из строки пробинга: указатель должен пережить
    // вызов, а `ProbeVideoTrack::mime` — это `std::string` внутри результата
    // пробинга. Пустой MIME означает «HW-пути нет» (см. media_format_map.h), и
    // подставлять похожий нельзя — это автоматический вход в SW-путь шага 7.
    self->mime_ = MimeFromCodecId(self->par_->codec_id);

    demux->StreamTimeBase(index, &self->time_base_num_, &self->time_base_den_);

    self->color_standard_key_ = probe.color.color_standard;
    self->color_transfer_key_ = probe.color.color_transfer;
    self->color_range_key_ = probe.color.color_range;
    self->standard_ = StandardFromKey(probe.color.color_standard, track->height);
    self->full_range_ = probe.color.color_range == kColorRangeFull;

    if (cfg.send_hdr_static_info && probe.color.has_static_info) {
        memcpy(self->hdr_static_info_, probe.color.static_info, kHdrStaticInfoSize);
        self->has_hdr_ = true;
    }

    self->ten_bit_requested_ = cfg.prefer_ten_bit && track->bit_depth > 8;

    bool started = false;
    std::string hw_error;
    if (!self->force_software_ && self->mime_ != nullptr) {
        started = self->StartHardware(self->ten_bit_requested_, cfg.codec_name, &hw_error);
    }
    if (!started) {
        if (!hw_error.empty()) DDD_LOGW("decode: HW недоступен, пробуем SW (%s)", hw_error.c_str());
        started = self->StartSoftware(error);
    }
    if (!started) {
        delete self;
        return nullptr;
    }
    DDD_LOGI("decode: поток %d, %s, %d бит, ten_bit_requested=%d, hdr_static=%d, rung=%d",
             index, self->mime_ != nullptr ? self->mime_ : track->codec.c_str(), track->bit_depth,
             static_cast<int>(self->ten_bit_requested_), static_cast<int>(self->has_hdr_),
             self->decoder_->rung());
    return self;
}

DecodeSession::~DecodeSession() {
    delete decoder_;
    if (par_ != nullptr) avcodec_parameters_free(&par_);
}

bool DecodeSession::StartHardware(bool prefer_ten_bit, const char *codec_name, std::string *error) {
    if (mime_ == nullptr) {
        SetError(error, "для потока нет MIME MediaCodec");
        return false;
    }
    DecoderConfig dc;
    dc.mime = mime_;
    dc.par = par_;
    dc.hdr_static_info = has_hdr_ ? hdr_static_info_ : nullptr;
    dc.color_standard = color_standard_key_;
    dc.color_transfer = color_transfer_key_;
    dc.color_range = color_range_key_;
    dc.prefer_ten_bit = prefer_ten_bit;
    dc.codec_name = codec_name;
    dc.surface = surface_;

    VideoDecoder *next = HwVideoDecoder::Create(dc, error);
    if (next == nullptr) return false;
    delete decoder_;
    decoder_ = next;
    eos_pushed_ = false;
    return true;
}

bool DecodeSession::StartSoftware(std::string *error) {
    VideoDecoder *next = SwVideoDecoder::Create(par_, error);
    if (next == nullptr) return false;
    delete decoder_;
    decoder_ = next;
    eos_pushed_ = false;
    return true;
}

bool DecodeSession::RewindDemux() {
    const int before = demux_->GetStats().seeks;
    if (!demux_->Seek(0)) return false;

    // 2 с с запасом: локальный файл перематывается за единицы миллисекунд, а
    // сетевой источник в этот момент ещё и переоткрывает соединение.
    for (int i = 0; i < 200; ++i) {
        if (demux_->GetStats().seeks > before) return true;
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    DDD_LOGW("decode: демукс не подтвердил перемотку к началу");
    return false;
}

bool DecodeSession::Escalate() {
    // Аппаратный декодер отдал 8 бит на 10-битном потоке — то самое место, где 10
    // бит теряются молча и картинка становится вымытой. libavcodec сохраняет
    // исходную глубину и отдаёт те же YUV-плоскости renderer; это и есть шаг 7.
    const std::string hw_name = decoder_->name();
    const int32_t hw_format = decoder_->output().color_format;

    std::string why;
    if (StartSoftware(&why) && RewindDemux()) {
        escalation_ = hw_name + " вернул color-format 0x" + std::to_string(hw_format) +
                      " (8 бит), заменён на " + decoder_->name();
        DDD_LOGW("decode: %s", escalation_.c_str());
        return Flush();
    }
    DDD_LOGW("decode: эскалация на libavcodec не удалась (%s)", why.c_str());

    // SW не поднялся (или источник не перематывается).
    // Возвращаем аппаратный: файл сыграет, но именно тем 8-битным путём, ради
    // ухода от которого затевался шаг 5, — и об этом сказано в escalation().
    const char *codec_name = codec_name_.empty() ? nullptr : codec_name_.c_str();
    if (!StartHardware(false, codec_name, &why)) {
        DDD_LOGE("decode: после неудачной эскалации декодер не поднялся: %s", why.c_str());
        return false;
    }
    RewindDemux();
    escalation_ = "10 бит недоступны: " + hw_name + " вернул 8 бит, программная замена не вышла";
    DDD_LOGW("decode: %s", escalation_.c_str());
    return Flush();
}

bool DecodeSession::Pump(int timeout_ms) {
    if (decoder_->has_pending()) {
        return decoder_->PushPending(timeout_ms) != VideoDecoder::Feed::kError;
    }
    if (eos_pushed_) return true;

    AVPacket *pkt = demux_->TakePacket(stream_index_, timeout_ms);
    if (pkt == nullptr) {
        // Пакета нет: либо очередь пуста и данные ещё едут, либо источник
        // кончился. Различить можно только по статистике демукса — `Pop` о конце
        // данных не сообщает, он просто возвращает nullptr.
        if (demux_->GetStats().eof) {
            const VideoDecoder::Feed f = decoder_->PushEos(timeout_ms);
            if (f == VideoDecoder::Feed::kError) return false;
            if (f == VideoDecoder::Feed::kQueued) eos_pushed_ = true;
        }
        return true;
    }

    // PTS в микросекундах: MediaCodec другого не принимает. `AV_NOPTS_VALUE`
    // бывает у пакетов до первого ключевого кадра — им 0, иначе кадр уедет в
    // отрицательное время и синхронизация шага 8 сойдёт с ума.
    const int64_t ts = pkt->pts != AV_NOPTS_VALUE ? pkt->pts : pkt->dts;
    const int64_t pts_us =
        ts != AV_NOPTS_VALUE
            ? av_rescale_q(ts, AVRational{time_base_num_, time_base_den_}, AVRational{1, 1000000})
            : 0;

    if (dovi_parser_) {
        if (auto mapping = dovi_parser_->ParsePacket(pkt->data, pkt->size)) {
            dovi_by_pts_[pts_us] = std::move(mapping);
            while (dovi_by_pts_.size() > 512) dovi_by_pts_.erase(dovi_by_pts_.begin());
        }
    }

    return decoder_->Push(pkt, pts_us, timeout_ms) != VideoDecoder::Feed::kError;
}

DecodeSession::Step DecodeSession::NextFrame(DecodedFrame *out, int timeout_ms) {
    if (decoder_ == nullptr || out == nullptr) return Step::kError;

    // Порядок «сначала докормить, потом вынуть» существенен: пустому декодеру
    // `dequeueOutputBuffer` вернёт kAgain только по истечении таймаута, и на
    // каждый кадр набегала бы лишняя задержка в timeout_ms.
    if (!Pump(timeout_ms)) return Step::kError;

    switch (decoder_->DequeueFrame(out, timeout_ms)) {
        case VideoDecoder::Pull::kFrame:
            break;
        case VideoDecoder::Pull::kAgain:
            return Step::kAgain;
        case VideoDecoder::Pull::kEos:
            return Step::kEos;
        default:
            return Step::kError;
    }

    if (!depth_checked_ && !decoder_->surface_output()) {
        depth_checked_ = true;
        const bool ten_bit = DescribeFormat(decoder_->output().format).sixteen_bit();
        if (ten_bit_requested_ && !ten_bit) {
            if (allow_escalation_) {
                // Кадр возвращается декодеру немедленно: он будет удалён вместе с
                // декодером, а держать чужой буфер после `delete` нельзя.
                decoder_->ReleaseFrame(*out);
                out->index = -1;
                if (!Escalate()) return Step::kError;
                depth_checked_ = false;  // у нового декодера своя раскладка
                return Step::kAgain;
            }
            DDD_LOGW("decode: %s вернул 8 бит на %d-битном потоке, эскалация запрещена",
                     decoder_->name().c_str(), stream_bit_depth_);
        }
    }

    // Цвет декодер не знает: `MediaCodec` цветовые ключи принимает, но обратно
    // отдаёт не всегда и не полностью. Источник истины — пробинг контейнера.
    out->frame.standard = standard_;
    out->frame.full_range = full_range_;
    if (dovi_parser_) {
        const auto it = dovi_by_pts_.find(out->pts_us);
        if (it != dovi_by_pts_.end()) {
            out->dovi_mapping = it->second;
            dovi_by_pts_.erase(it);
        } else {
            out->dovi_mapping.reset();
        }
    }
    return Step::kFrame;
}

void DecodeSession::ReleaseFrame(const DecodedFrame &frame) {
    if (decoder_ != nullptr) decoder_->ReleaseFrame(frame);
}

bool DecodeSession::RenderFrame(const DecodedFrame &frame) {
    return decoder_ != nullptr && decoder_->RenderFrame(frame);
}

bool DecodeSession::Flush() {
    eos_pushed_ = false;
    dovi_by_pts_.clear();
    if (dovi_parser_) dovi_parser_->Flush();
    return decoder_ != nullptr && decoder_->Flush();
}

}  // namespace ddd
