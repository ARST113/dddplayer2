/*
 * demux_session.h — открытие источника, пробинг и поток демукса.
 *
 * Один экземпляр = один открытый медиаисточник. Декодирования здесь нет: сессия
 * доводит данные до очередей пакетов, а декодеры (шаги 5 и 7) забирают их
 * оттуда. Такое разделение позволяет проверить всю сетевую и контейнерную часть
 * до того, как появится хоть один кадр, — и именно это проверяется на шаге 3.
 *
 * Что здесь заменяет Media3:
 *  - `DefaultMediaSourceFactory` + экстракторы → `avformat_open_input` + пробинг;
 *  - `DataSource`-цепочка DDD → `IoSource` (`io_source.h`), то есть Java остаётся
 *    на месте;
 *  - `DefaultLoadControl` → политика очередей в `packet_queue.h`;
 *  - `Loader`-поток ExoPlayer → поток демукса ниже.
 */
#pragma once

#include <atomic>
#include <condition_variable>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "io_source.h"
#include "packet_queue.h"
#include "probe.h"

struct AVFormatContext;
struct AVPacket;
struct AVCodecParameters;

namespace ddd {

class DemuxSession {
public:
    /** Ключ-значение для `AVDictionary`: `user_agent`, `headers`, `timeout`, … */
    using Options = std::vector<std::pair<std::string, std::string>>;

    struct Stats {
        /** До какой позиции прочитан источник, мс от начала (не от start_time). */
        int64_t buffered_position_ms = 0;
        /**
         * Позиция первого пакета в очередях, мс, — то есть та, с которой пойдёт
         * декодирование. После seek показывает, куда демуксер реально встал:
         * [buffered_position_ms] к этому моменту уже уехал вперёд на всю глубину
         * буфера (до 50 с), и по нему точность seek не проверить. -1, если
         * очереди пусты.
         */
        int64_t queue_start_ms = -1;
        /** Длительность содержимого очередей, мс (минимум по видео/аудио). */
        int64_t buffered_duration_ms = 0;
        int64_t queued_bytes = 0;
        int queued_packets = 0;
        bool eof = false;
        /** Ошибки чтения, которые удалось переждать: индикатор проблем с сетью. */
        int read_errors = 0;
        int seeks = 0;
    };

    /**
     * Открывает источник.
     *
     * @param url     URL для FFmpeg; игнорируется, если задан [io].
     * @param io      источник байт; сессия забирает владение и удалит его в
     *                деструкторе. nullptr — читать по [url] средствами FFmpeg
     *                (протоколы `file`, `content`, `http`).
     * @param options опции демуксера.
     * @param av_error сюда пишется код `AVERROR` при неудаче (может быть nullptr).
     * @return сессия с уже выполненным пробингом либо nullptr.
     */
    static DemuxSession *Open(const char *url, IoSource *io, const Options &options,
                              int *av_error);

    ~DemuxSession();

    DemuxSession(const DemuxSession &) = delete;
    DemuxSession &operator=(const DemuxSession &) = delete;

    const ProbeResult &probe() const { return probe_; }

    /** Длительность, мс; 0 для live. */
    int64_t duration_ms() const;

    /**
     * Выбирает потоки, пакеты которых попадут в очереди. Остальные отбрасываются
     * сразу при чтении — это дешевле, чем фильтровать их дальше по конвейеру.
     *
     * @param video/audio/subtitle индексы потоков; -1 — не читать этот тип.
     *        По умолчанию (без вызова) выбраны `best_*` из пробинга.
     * @return false, если индекс не соответствует потоку нужного типа.
     */
    bool SelectStreams(int video, int audio, int subtitle);

    /** Запускает поток демукса. Повторный вызов игнорируется. */
    bool Start();

    /** Останавливает поток и прерывает висящее сетевое чтение. */
    void Stop();

    /**
     * Просит поток демукса перейти на позицию. Возврат означает, что запрос
     * принят, а не что seek выполнен: сам seek делается в потоке демукса, чтобы
     * не гонять `AVFormatContext` из двух потоков.
     */
    bool Seek(int64_t position_ms);

    /**
     * Забирает пакет потока [stream_index]; владение переходит вызывающему
     * (`av_packet_free`).
     *
     * @return nullptr при таймауте, конце данных или остановке.
     */
    AVPacket *TakePacket(int stream_index, int timeout_ms);

    /**
     * Копия `AVCodecParameters` потока — для настройки декодера.
     *
     * Копия, а не указатель внутрь `AVFormatContext`, намеренно. Декодер живёт в
     * другом потоке, а `AVStream` принадлежит потоку демукса: часть демуксеров
     * (HLS, MPEG-TS) правит `codecpar` на ходу при смене сегмента. Читать эту
     * структуру из чужого потока — гонка, которая проявляется не «иногда
     * падает», а «раз в сто открытий декодер получил половину старого extradata».
     *
     * @return выделенный `AVCodecParameters`, освобождать
     *         `avcodec_parameters_free`; nullptr, если потока нет.
     */
    AVCodecParameters *CopyCodecParameters(int stream_index) const;

    /**
     * Временная база потока — для пересчёта PTS пакетов в микросекунды.
     *
     * @return false, если такого потока нет.
     */
    bool StreamTimeBase(int stream_index, int *num, int *den) const;

    Stats GetStats() const;

    /** Диагностика: сколько всего пакетов прочитано с начала (и после seek). */
    int64_t packets_read() const { return packets_read_.load(); }

private:
    DemuxSession();

    bool OpenInternal(const char *url, IoSource *io, const Options &options, int *av_error);
    void ThreadBody();
    void CreateQueues();
    /** true — очереди заполнены, читать дальше нельзя (см. гистерезис в packet_queue.h). */
    bool ShouldThrottle();
    void FlushQueues();
    void SetEofAll(bool eof);
    static int InterruptCb(void *opaque);
    int64_t StartTimeUs() const;

    AVFormatContext *fc_ = nullptr;
    /**
     * Свой AVIOContext. Хранится отдельно от `fc_->pb` намеренно: при ошибке
     * `avformat_open_input` освобождает `AVFormatContext` и обнуляет указатель,
     * но пользовательский `pb` не трогает (флаг `AVFMT_FLAG_CUSTOM_IO`). Без
     * своей копии указателя его буфер утёк бы на каждой неудачной попытке
     * открытия — то есть на каждом недоступном URL.
     */
    AVIOContext *avio_ = nullptr;
    std::unique_ptr<IoSource> io_;
    ProbeResult probe_;

    int video_index_ = -1;
    int audio_index_ = -1;
    int subtitle_index_ = -1;

    /** Очереди по индексу потока; создаются в [Start] под выбранные потоки. */
    std::map<int, std::unique_ptr<PacketQueue>> queues_;

    std::thread thread_;
    std::atomic<bool> abort_{false};
    std::atomic<bool> started_{false};
    std::atomic<int64_t> packets_read_{0};
    std::atomic<int> read_errors_{0};
    std::atomic<int> seeks_{0};
    std::atomic<bool> eof_{false};

    /** Запрос seek в мс; -1 — нет запроса. */
    std::atomic<int64_t> seek_request_ms_{-1};

    /** Гистерезис буферизации: true — ждём, пока буфер опустится до минимума. */
    bool throttled_ = false;

    mutable std::mutex wait_mutex_;
    std::condition_variable wait_cv_;

    /**
     * Stop вызывают и управляющий Kotlin-поток, и cleanup decode-потока.
     * std::thread::joinable()+join не образуют атомарную операцию: без этого
     * mutex два одновременных Stop могли оба увидеть joinable=true и второй
     * join завершал процесс через std::system_error(EINVAL).
     */
    std::mutex stop_mutex_;
};

}  // namespace ddd
