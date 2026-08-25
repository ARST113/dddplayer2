/*
 * packet_queue.h — очередь пакетов одного потока между демуксом и декодером.
 *
 * Это замена `DefaultLoadControl` из Media3, и параметры буферизации DDD
 * сохранены один-в-один (`PlayerManager.kt:371-379`):
 *
 *   minBufferMs = 15000, maxBufferMs = 50000,
 *   bufferForPlaybackMs = 500, bufferForPlaybackAfterRebufferMs = 5000,
 *   prioritizeTimeOverSizeThresholds = true
 *
 * Последний флаг — не деталь: он означает, что в DDD решает длительность
 * буфера, а не его размер в байтах. Поэтому здесь основной критерий — тоже
 * длительность, а лимит по байтам ([kMaxQueueBytes]) существует только как
 * защита от OOM на потоках с высоким битрейтом: 50 секунд 4K при 100 Мбит/с —
 * это 625 МБ, столько в кучу не положить.
 *
 * Гистерезис (`min` на возобновление, `max` на остановку) нужен, чтобы поток
 * демукса не просыпался на каждый вынутый пакет: без него на 4K это десятки
 * тысяч пробуждений в секунду.
 */
#pragma once

#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>

struct AVPacket;
struct AVRational;

namespace ddd {

/** Параметры буферизации, перенесённые из `DefaultLoadControl` DDD. */
constexpr int64_t kMinBufferMs = 15000;
constexpr int64_t kMaxBufferMs = 50000;
constexpr int64_t kBufferForPlaybackMs = 500;
constexpr int64_t kBufferForPlaybackAfterRebufferMs = 5000;

/**
 * Предохранитель по памяти на все очереди суммарно.
 *
 * Для сравнения: `DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE` в Media3 —
 * 2000 × 64 КБ ≈ 125 МБ, но при `prioritizeTimeOverSizeThresholds=true` он
 * фактически игнорируется. Здесь 96 МБ: достаточно, чтобы лимит не срабатывал
 * на обычном 4K (≈40 Мбит/с даёт ~19 МБ на 4 секунды… то есть 50 с не влезут —
 * и это правильно: играть важнее, чем добуферить).
 */
constexpr int64_t kMaxQueueBytes = 96LL * 1024 * 1024;

/**
 * Очередь пакетов одного потока.
 *
 * Потокобезопасна: пишет поток демукса, читает поток декодера. Пакеты хранятся
 * как `AVPacket*`, владение переходит в очередь.
 */
class PacketQueue {
public:
    /**
     * @param stream_index индекс потока в контейнере.
     * @param time_base    временная база потока: нужна, чтобы очередь сама
     *                     считала длительность буфера в микросекундах, а не
     *                     заставляла это делать каждого читателя.
     */
    PacketQueue(int stream_index, AVRational time_base);
    ~PacketQueue();

    PacketQueue(const PacketQueue &) = delete;
    PacketQueue &operator=(const PacketQueue &) = delete;

    /**
     * Кладёт пакет, забирая владение.
     * @return false, если очередь прервана ([Abort]) — тогда пакет освобождается.
     */
    bool Push(AVPacket *pkt);

    /**
     * Забирает пакет; владение переходит вызывающему.
     *
     * @param timeout_ms сколько ждать, если очередь пуста; 0 — не ждать.
     * @return nullptr при таймауте, EOF или прерывании.
     */
    AVPacket *Pop(int timeout_ms);

    /** Выбрасывает всё содержимое. Вызывается при seek. */
    void Flush();

    /** Помечает, что данных больше не будет (или что снова будут — после seek). */
    void SetEof(bool eof);

    /** Разблокирует всех ожидающих и запрещает дальнейшую работу. */
    void Abort();

    int stream_index() const { return stream_index_; }

    /** Длительность содержимого очереди, мкс. 0, если PTS неизвестны. */
    int64_t BufferedUs() const;

    /** PTS последнего положенного пакета, мкс от начала; `INT64_MIN` если нет. */
    int64_t LastPtsUs() const;

    /**
     * PTS первого пакета в очереди, мкс; `INT64_MIN` если очередь пуста.
     *
     * Это позиция, с которой продолжится декодирование, — в отличие от
     * [LastPtsUs], который показывает, до какой позиции прочитан источник. После
     * seek именно первый пакет говорит, куда фактически встал демуксер:
     * последний уже уехал вперёд на всю глубину буфера.
     */
    int64_t FirstPtsUs() const;

    int64_t Bytes() const;
    size_t Count() const;
    bool IsEof() const;

private:
    /** Пересчитывает first_/last_us_ под уже взятым замком. */
    void RecomputeBoundsLocked();
    int64_t ToUs(int64_t ts) const;

    const int stream_index_;
    const int tb_num_;
    const int tb_den_;

    mutable std::mutex mutex_;
    std::condition_variable cv_;
    std::deque<AVPacket *> queue_;

    int64_t bytes_ = 0;
    int64_t first_us_ = INT64_MIN;
    int64_t last_us_ = INT64_MIN;
    bool eof_ = false;
    bool aborted_ = false;
};

}  // namespace ddd
