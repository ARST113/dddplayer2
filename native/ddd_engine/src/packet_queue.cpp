/*
 * packet_queue.cpp — реализация очереди. См. packet_queue.h.
 */
#include "packet_queue.h"

#include <chrono>

#include "ddd_log.h"
#include "ff_include.h"

namespace ddd {

PacketQueue::PacketQueue(int stream_index, AVRational time_base)
    : stream_index_(stream_index),
      tb_num_(time_base.num > 0 ? time_base.num : 1),
      tb_den_(time_base.den > 0 ? time_base.den : AV_TIME_BASE) {}

PacketQueue::~PacketQueue() {
    Abort();
    Flush();
}

int64_t PacketQueue::ToUs(int64_t ts) const {
    if (ts == AV_NOPTS_VALUE) return INT64_MIN;
    return av_rescale_q(ts, AVRational{tb_num_, tb_den_}, AVRational{1, AV_TIME_BASE});
}

bool PacketQueue::Push(AVPacket *pkt) {
    if (pkt == nullptr) return false;

    std::lock_guard<std::mutex> lock(mutex_);
    if (aborted_) {
        av_packet_free(&pkt);
        return false;
    }

    // Для длительности буфера берём DTS, если PTS нет: у B-кадров PTS может
    // отсутствовать в некоторых контейнерах, а порядок DTS монотонен.
    const int64_t ts = pkt->pts != AV_NOPTS_VALUE ? pkt->pts : pkt->dts;
    const int64_t us = ToUs(ts);

    bytes_ += pkt->size;
    if (us != INT64_MIN) {
        if (first_us_ == INT64_MIN) first_us_ = us;
        last_us_ = us;
    }
    queue_.push_back(pkt);
    eof_ = false;
    cv_.notify_all();
    return true;
}

AVPacket *PacketQueue::Pop(int timeout_ms) {
    std::unique_lock<std::mutex> lock(mutex_);

    const auto ready = [this] { return !queue_.empty() || eof_ || aborted_; };
    if (!ready()) {
        if (timeout_ms <= 0) return nullptr;
        cv_.wait_for(lock, std::chrono::milliseconds(timeout_ms), ready);
    }
    if (queue_.empty()) return nullptr;

    AVPacket *pkt = queue_.front();
    queue_.pop_front();
    bytes_ -= pkt->size;
    RecomputeBoundsLocked();
    cv_.notify_all();  // разбудить демукс: место освободилось
    return pkt;
}

void PacketQueue::RecomputeBoundsLocked() {
    if (queue_.empty()) {
        first_us_ = INT64_MIN;
        // last_us_ сознательно НЕ сбрасывается: это позиция, до которой источник
        // прочитан, и она нужна для `bufferedPosition` даже после того, как
        // декодер выгреб очередь. Сброс происходит только во Flush().
        return;
    }
    const AVPacket *front = queue_.front();
    const int64_t ts = front->pts != AV_NOPTS_VALUE ? front->pts : front->dts;
    first_us_ = ToUs(ts);
}

void PacketQueue::Flush() {
    std::lock_guard<std::mutex> lock(mutex_);
    for (AVPacket *pkt : queue_) {
        AVPacket *p = pkt;
        av_packet_free(&p);
    }
    queue_.clear();
    bytes_ = 0;
    first_us_ = INT64_MIN;
    last_us_ = INT64_MIN;
    eof_ = false;
    cv_.notify_all();
}

void PacketQueue::SetEof(bool eof) {
    std::lock_guard<std::mutex> lock(mutex_);
    eof_ = eof;
    cv_.notify_all();
}

void PacketQueue::Abort() {
    std::lock_guard<std::mutex> lock(mutex_);
    aborted_ = true;
    cv_.notify_all();
}

int64_t PacketQueue::BufferedUs() const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (first_us_ == INT64_MIN || last_us_ == INT64_MIN) return 0;
    const int64_t d = last_us_ - first_us_;
    return d > 0 ? d : 0;
}

int64_t PacketQueue::LastPtsUs() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return last_us_;
}

int64_t PacketQueue::FirstPtsUs() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return first_us_;
}

int64_t PacketQueue::Bytes() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return bytes_;
}

size_t PacketQueue::Count() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return queue_.size();
}

bool PacketQueue::IsEof() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return eof_ && queue_.empty();
}

}  // namespace ddd
