/*
 * demux_session.cpp — реализация. См. demux_session.h.
 */
#include "demux_session.h"

#include <algorithm>
#include <chrono>

#include "ddd_log.h"
#include "ff_include.h"

namespace ddd {
namespace {

/** Сколько ждать в цикле, когда буфер полон или источник упёрся в EOF. */
constexpr int kIdleWaitMs = 30;

/**
 * Сколько подряд ошибок чтения переживаем, прежде чем считать источник мёртвым.
 *
 * У сетевых источников `av_read_frame` может вернуть ошибку на разрыве
 * соединения, который OkHttp затем переоткроет. Прерывать воспроизведение на
 * первой же ошибке — то самое поведение, из-за которого «TorrServer отвалился»
 * выглядит как «плеер сломался».
 */
constexpr int kMaxConsecutiveErrors = 32;

std::string AvErr(int code) {
    char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(code, buf, sizeof buf);
    return std::string(buf);
}

}  // namespace

DemuxSession::DemuxSession() = default;

DemuxSession::~DemuxSession() {
    Stop();

    // Порядок важен: сначала закрыть демуксер (он ещё может дочитывать из pb),
    // потом освободить AVIOContext, и только потом — сам источник.
    if (fc_ != nullptr) avformat_close_input(&fc_);
    if (avio_ != nullptr) FreeAvio(&avio_);
    queues_.clear();
    io_.reset();
}

int DemuxSession::InterruptCb(void *opaque) {
    auto *self = static_cast<DemuxSession *>(opaque);
    return self != nullptr && self->abort_.load() ? 1 : 0;
}

DemuxSession *DemuxSession::Open(const char *url, IoSource *io, const Options &options,
                                 int *av_error) {
    auto *session = new DemuxSession();
    if (!session->OpenInternal(url, io, options, av_error)) {
        delete session;
        return nullptr;
    }
    return session;
}

bool DemuxSession::OpenInternal(const char *url, IoSource *io, const Options &options,
                                int *av_error) {
    if (io != nullptr) io_.reset(io);

    fc_ = avformat_alloc_context();
    if (fc_ == nullptr) {
        if (av_error != nullptr) *av_error = AVERROR(ENOMEM);
        return false;
    }

    // Колбэк прерывания ставится ДО открытия: иначе `avformat_open_input` на
    // недоступном сервере висит до собственного таймаута, и Stop() его не снимет.
    fc_->interrupt_callback.callback = &DemuxSession::InterruptCb;
    fc_->interrupt_callback.opaque = this;

    if (io_ != nullptr) {
        avio_ = MakeAvio(io_.get());
        if (avio_ == nullptr) {
            avformat_free_context(fc_);
            fc_ = nullptr;
            if (av_error != nullptr) *av_error = AVERROR(ENOMEM);
            return false;
        }
        fc_->pb = avio_;
        fc_->flags |= AVFMT_FLAG_CUSTOM_IO;
    }

    AVDictionary *opts = nullptr;
    for (const auto &kv : options) av_dict_set(&opts, kv.first.c_str(), kv.second.c_str(), 0);

    const char *open_url = io_ != nullptr ? nullptr : url;
    int r = avformat_open_input(&fc_, open_url, nullptr, &opts);

    if (AVDictionaryEntry *e = av_dict_get(opts, "", nullptr, AV_DICT_IGNORE_SUFFIX)) {
        // Не ошибка, но полезно видеть: опция не понята демуксером.
        do {
            DDD_LOGW("demux: опция '%s' не использована", e->key);
        } while ((e = av_dict_get(opts, "", e, AV_DICT_IGNORE_SUFFIX)) != nullptr);
    }
    av_dict_free(&opts);

    if (r < 0) {
        // avformat_open_input при ошибке освобождает контекст и обнуляет fc_.
        // Наш AVIOContext он не трогает (AVFMT_FLAG_CUSTOM_IO) — освободит
        // деструктор через avio_.
        DDD_LOGE("demux: avformat_open_input('%s'): %s", open_url != nullptr ? open_url : "<io>",
                 AvErr(r).c_str());
        if (av_error != nullptr) *av_error = r;
        return false;
    }

    if (!Probe(fc_, &probe_)) {
        if (av_error != nullptr) *av_error = AVERROR_INVALIDDATA;
        return false;
    }

    video_index_ = probe_.best_video_index;
    audio_index_ = probe_.best_audio_index;
    subtitle_index_ = probe_.best_subtitle_index;
    return true;
}

int64_t DemuxSession::StartTimeUs() const {
    if (fc_ == nullptr || fc_->start_time == AV_NOPTS_VALUE) return 0;
    return fc_->start_time;
}

int64_t DemuxSession::duration_ms() const {
    return probe_.container.duration_us > 0 ? probe_.container.duration_us / 1000 : 0;
}

bool DemuxSession::SelectStreams(int video, int audio, int subtitle) {
    if (started_.load()) {
        DDD_LOGE("demux: SelectStreams после Start() не поддержан");
        return false;
    }
    const auto valid = [this](int idx, AVMediaType type) {
        if (idx < 0) return true;
        if (fc_ == nullptr || idx >= static_cast<int>(fc_->nb_streams)) return false;
        return fc_->streams[idx]->codecpar->codec_type == type;
    };
    if (!valid(video, AVMEDIA_TYPE_VIDEO) || !valid(audio, AVMEDIA_TYPE_AUDIO) ||
        !valid(subtitle, AVMEDIA_TYPE_SUBTITLE)) {
        return false;
    }
    video_index_ = video;
    audio_index_ = audio;
    subtitle_index_ = subtitle;
    return true;
}

void DemuxSession::CreateQueues() {
    queues_.clear();
    for (int idx : {video_index_, audio_index_, subtitle_index_}) {
        if (idx < 0) continue;
        queues_[idx] = std::make_unique<PacketQueue>(idx, fc_->streams[idx]->time_base);
    }
    // Все потоки, кроме выбранных, помечаем как ненужные: часть демуксеров
    // (в первую очередь HLS и MPEG-TS) на этом экономит реальную работу.
    for (unsigned i = 0; i < fc_->nb_streams; ++i) {
        const bool selected = queues_.count(static_cast<int>(i)) != 0;
        fc_->streams[i]->discard = selected ? AVDISCARD_DEFAULT : AVDISCARD_ALL;
    }
}

bool DemuxSession::Start() {
    if (fc_ == nullptr) return false;
    if (started_.exchange(true)) return true;

    CreateQueues();
    if (queues_.empty()) {
        DDD_LOGE("demux: нет ни одного выбранного потока");
        started_.store(false);
        return false;
    }

    abort_.store(false);
    thread_ = std::thread(&DemuxSession::ThreadBody, this);
    return true;
}

void DemuxSession::Stop() {
    std::lock_guard<std::mutex> stop_lock(stop_mutex_);
    abort_.store(true);
    for (auto &kv : queues_) kv.second->Abort();
    {
        std::lock_guard<std::mutex> lock(wait_mutex_);
        wait_cv_.notify_all();
    }
    if (thread_.joinable()) thread_.join();
    started_.store(false);
}

bool DemuxSession::Seek(int64_t position_ms) {
    if (fc_ == nullptr) return false;
    if (position_ms < 0) position_ms = 0;
    seek_request_ms_.store(position_ms);
    std::lock_guard<std::mutex> lock(wait_mutex_);
    wait_cv_.notify_all();
    return true;
}

AVPacket *DemuxSession::TakePacket(int stream_index, int timeout_ms) {
    auto it = queues_.find(stream_index);
    if (it == queues_.end()) return nullptr;
    return it->second->Pop(timeout_ms);
}

AVCodecParameters *DemuxSession::CopyCodecParameters(int stream_index) const {
    if (fc_ == nullptr || stream_index < 0 || stream_index >= static_cast<int>(fc_->nb_streams)) {
        return nullptr;
    }
    AVCodecParameters *dst = avcodec_parameters_alloc();
    if (dst == nullptr) return nullptr;
    if (avcodec_parameters_copy(dst, fc_->streams[stream_index]->codecpar) < 0) {
        avcodec_parameters_free(&dst);
        return nullptr;
    }
    return dst;
}

bool DemuxSession::StreamTimeBase(int stream_index, int *num, int *den) const {
    if (fc_ == nullptr || stream_index < 0 || stream_index >= static_cast<int>(fc_->nb_streams)) {
        return false;
    }
    const AVRational tb = fc_->streams[stream_index]->time_base;
    if (tb.num <= 0 || tb.den <= 0) return false;
    if (num != nullptr) *num = tb.num;
    if (den != nullptr) *den = tb.den;
    return true;
}

void DemuxSession::FlushQueues() {
    for (auto &kv : queues_) kv.second->Flush();
}

void DemuxSession::SetEofAll(bool eof) {
    for (auto &kv : queues_) kv.second->SetEof(eof);
}

bool DemuxSession::ShouldThrottle() {
    int64_t total_bytes = 0;
    int64_t min_primary_us = INT64_MAX;

    for (auto &kv : queues_) {
        total_bytes += kv.second->Bytes();
        // Субтитры не участвуют в оценке длительности: они разрежены, и по ним
        // буфер «никогда не наполняется» — цикл читал бы без остановки.
        if (kv.first == video_index_ || kv.first == audio_index_)
            min_primary_us = std::min(min_primary_us, kv.second->BufferedUs());
    }
    if (min_primary_us == INT64_MAX) min_primary_us = 0;

    if (total_bytes >= kMaxQueueBytes) {
        if (!throttled_)
            DDD_LOGW("demux: достигнут лимит памяти очередей (%lld Б) — пауза чтения",
                     static_cast<long long>(total_bytes));
        throttled_ = true;
        return true;
    }

    const int64_t min_ms = min_primary_us / 1000;
    if (throttled_) {
        // Гистерезис: возобновляем только когда буфер просел до минимума DDD.
        if (min_ms <= kMinBufferMs) throttled_ = false;
        return throttled_;
    }
    if (min_ms >= kMaxBufferMs) {
        throttled_ = true;
        return true;
    }
    return false;
}

void DemuxSession::ThreadBody() {
    int consecutive_errors = 0;

    while (!abort_.load()) {
        // ── seek: выполняется здесь, а не в вызывающем потоке, чтобы
        //    AVFormatContext оставался однопоточным.
        const int64_t seek_ms = seek_request_ms_.exchange(-1);
        if (seek_ms >= 0) {
            int64_t target = av_rescale(seek_ms, AV_TIME_BASE, 1000);
            // start_time обязателен: в MPEG-TS он часто не нулевой, и без него
            // seek уезжает на разницу — классическая «перемотка не туда».
            target += StartTimeUs();

            const int r = avformat_seek_file(fc_, -1, INT64_MIN, target, INT64_MAX, 0);
            if (r < 0) {
                DDD_LOGW("demux: seek на %lld мс не удался: %s", static_cast<long long>(seek_ms),
                         AvErr(r).c_str());
            } else {
                FlushQueues();
                eof_.store(false);
                throttled_ = false;
                seeks_.fetch_add(1);
                DDD_LOGI("demux: seek на %lld мс выполнен", static_cast<long long>(seek_ms));
            }
            continue;
        }

        if (ShouldThrottle() || eof_.load()) {
            std::unique_lock<std::mutex> lock(wait_mutex_);
            wait_cv_.wait_for(lock, std::chrono::milliseconds(kIdleWaitMs));
            continue;
        }

        AVPacket *pkt = av_packet_alloc();
        if (pkt == nullptr) {
            DDD_LOGE("demux: нет памяти на AVPacket");
            break;
        }

        const int r = av_read_frame(fc_, pkt);
        if (r < 0) {
            av_packet_free(&pkt);

            if (abort_.load()) break;
            if (r == AVERROR_EOF || (fc_->pb != nullptr && avio_feof(fc_->pb) != 0)) {
                DDD_LOGI("demux: конец данных, прочитано пакетов %lld",
                         static_cast<long long>(packets_read_.load()));
                eof_.store(true);
                SetEofAll(true);
                continue;  // ждём seek или Stop
            }
            if (r == AVERROR(EAGAIN)) {
                std::unique_lock<std::mutex> lock(wait_mutex_);
                wait_cv_.wait_for(lock, std::chrono::milliseconds(kIdleWaitMs));
                continue;
            }
            read_errors_.fetch_add(1);
            if (++consecutive_errors >= kMaxConsecutiveErrors) {
                DDD_LOGE("demux: %d ошибок чтения подряд, последняя: %s", consecutive_errors,
                         AvErr(r).c_str());
                eof_.store(true);
                SetEofAll(true);
                break;
            }
            DDD_LOGW("demux: ошибка чтения (%d-я подряд): %s", consecutive_errors,
                     AvErr(r).c_str());
            continue;
        }
        consecutive_errors = 0;

        auto it = queues_.find(pkt->stream_index);
        if (it == queues_.end()) {
            av_packet_free(&pkt);  // поток не выбран
            continue;
        }
        packets_read_.fetch_add(1);
        if (!it->second->Push(pkt)) break;  // очередь прервана — выходим
    }

    DDD_LOGI("demux: поток завершён (пакетов %lld, ошибок %d, seek'ов %d)",
             static_cast<long long>(packets_read_.load()), read_errors_.load(), seeks_.load());
}

DemuxSession::Stats DemuxSession::GetStats() const {
    Stats s;
    s.eof = eof_.load();
    s.read_errors = read_errors_.load();
    s.seeks = seeks_.load();

    int64_t last_us = INT64_MIN;
    int64_t first_us = INT64_MAX;
    int64_t min_primary_us = INT64_MAX;
    for (const auto &kv : queues_) {
        s.queued_bytes += kv.second->Bytes();
        s.queued_packets += static_cast<int>(kv.second->Count());
        const int64_t last = kv.second->LastPtsUs();
        if (last != INT64_MIN) last_us = std::max(last_us, last);
        const int64_t first = kv.second->FirstPtsUs();
        if (first != INT64_MIN) first_us = std::min(first_us, first);
        if (kv.first == video_index_ || kv.first == audio_index_)
            min_primary_us = std::min(min_primary_us, kv.second->BufferedUs());
    }
    if (last_us != INT64_MIN) {
        // Приводим к 0-базовой шкале: наружу движок отдаёт позиции от начала
        // файла, а не от start_time контейнера.
        const int64_t rel = last_us - StartTimeUs();
        s.buffered_position_ms = rel > 0 ? rel / 1000 : 0;
    }
    if (first_us != INT64_MAX) {
        const int64_t rel = first_us - StartTimeUs();
        s.queue_start_ms = rel > 0 ? rel / 1000 : 0;
    }
    s.buffered_duration_ms = min_primary_us == INT64_MAX ? 0 : min_primary_us / 1000;
    return s;
}

}  // namespace ddd
