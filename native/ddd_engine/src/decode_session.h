/*
 * decode_session.h — демукс + HW-декодер как один синхронный источник кадров.
 *
 * Разделение обязанностей: `DemuxSession` доводит пакеты до очередей,
 * `HwVideoDecoder` превращает их в кадры, а здесь живёт то, что не принадлежит ни
 * тому, ни другому:
 *
 *  - откуда взять MIME, extradata, `hdr-static-info` и цветовые ключи (из
 *    пробинга) и как их отдать декодеру;
 *  - насос: пакет → декодер → кадр, с корректным поведением на EOS;
 *  - ЭСКАЛАЦИЯ 10 БИТ. Аппаратный декодер вправе не поддерживать
 *    `COLOR_FormatYUVP010` в память и ответить 8-битной раскладкой, и это
 *    случается ровно на тех файлах, ради которых затевался проект. Здесь это
 *    обнаруживается на первом кадре и приводит к пересозданию декодера на
 *    программном (`c2.android.hevc.decoder`), который P010 умеет всегда.
 *
 * ПОЧЕМУ ПРОВЕРКА НА ПЕРВОМ КАДРЕ, А НЕ ПРИ СОЗДАНИИ. Раскладку вывода до первого
 * кадра узнать нельзя: `configure` соглашается на любой `color-format`, а
 * возвращает то, что умеет, и говорит об этом только в output format. Проверка в
 * `Create` означала бы декодировать кадр и выбросить его — то есть терять первый
 * кадр КАЖДОГО файла ради случая, который на большинстве файлов не наступает.
 * Поэтому первый кадр проверяется по дороге: обычный путь не платит ничего, а
 * платит только тот случай, где декодер и так пересоздаётся.
 *
 * Эскалация ОБЪЯВЛЯЕТСЯ, а не прячется: причина остаётся в [escalation], имя
 * реального декодера — в [decoder_name], а вернувшаяся глубина — в [output].
 * Молчаливый откат к 8 битам на 10-битном файле — это ровно тот отказ, который
 * шаг 5 обязан ловить, а не сглаживать; тест обязан его видеть.
 *
 * Владение: сессия НЕ владеет `DemuxSession` — он живёт своей жизнью (его
 * открывает и закрывает Kotlin), и один и тот же демукс дальше будет кормить и
 * видео-, и аудиодекодер.
 *
 * Поток: все методы обязаны вызываться из одного потока. `AMediaCodec` формально
 * потокобезопасен, но чередование `dequeue` из двух потоков даёт кадры не в том
 * порядке, и отладка этого стоит дороже, чем ограничение.
 */
#pragma once

#include <cstdint>
#include <map>
#include <memory>
#include <string>

#include "demux_session.h"
#include "hw_decoder.h"

struct AVCodecParameters;
struct ANativeWindow;

namespace ddd {

class DoviRpuParser;
struct DoviFrameMapping;

struct DecodeSessionConfig {
    /** Индекс видеопотока; -1 — `best_video_index` из пробинга. */
    int stream_index = -1;

    /**
     * Просить 10 бит (`COLOR_FormatYUVP010`) для потоков глубиной больше 8.
     *
     * Для 8-битных потоков не делает ничего: P010 на 8-битном файле — это
     * растянутые вверх байты, то есть вдвое больше памяти без единого лишнего
     * бита.
     */
    bool prefer_ten_bit = true;

    /**
     * Разрешить эскалацию на программный декодер, если аппаратный вернул не 10
     * бит на 10-битном потоке.
     *
     * Выключается в проверках: без этого нельзя увидеть, что именно умеет
     * аппаратный декодер устройства, — эскалация замаскировала бы отказ.
     */
    bool allow_software_escalation = true;

    /** Отдать декодеру `hdr-static-info` из пробинга, если он там есть. */
    bool send_hdr_static_info = true;

    /** Конкретный компонент по имени; nullptr — выбор по MIME. */
    const char *codec_name = nullptr;

    /** Сразу использовать libavcodec, не пробуя MediaCodec. */
    bool force_software = false;

    /** Прямой MediaCodec output Surface для декодеров без ByteBuffer-вывода. */
    ANativeWindow *surface = nullptr;
};

class DecodeSession {
public:
    /**
     * Создаёт декодер для видеопотока уже открытой сессии демукса.
     *
     * Демукс должен быть запущен ([DemuxSession::Start]) и его видеопоток —
     * выбран: пакеты берутся из очередей, а не читаются здесь.
     *
     * @param demux не во владении; должен жить дольше сессии декодирования.
     * @param error сюда пишется причина отказа (может быть nullptr).
     */
    static DecodeSession *Create(DemuxSession *demux, const DecodeSessionConfig &cfg,
                                 std::string *error);

    ~DecodeSession();

    DecodeSession(const DecodeSession &) = delete;
    DecodeSession &operator=(const DecodeSession &) = delete;

    /** Итог одного шага насоса. */
    enum class Step {
        kFrame,
        /** Кадра пока нет: нужно больше данных или больше времени. */
        kAgain,
        /** Поток закончился и декодер отдал всё, что мог. */
        kEos,
        kError
    };

    /**
     * Один шаг: докормить декодер и попытаться вынуть кадр.
     *
     * На первом кадре здесь же проверяется глубина и при необходимости
     * выполняется эскалация — тогда возвращается [Step::kAgain], а кадр придёт
     * следующими вызовами уже от нового декодера и снова с начала потока.
     *
     * @param out        заполняется при [Step::kFrame]; указатели действительны
     *                   до [ReleaseFrame].
     * @param timeout_ms сколько ждать кадра; 0 — без ожидания.
     */
    Step NextFrame(DecodedFrame *out, int timeout_ms);

    /** Возвращает буфер декодеру. Обязателен для каждого [Step::kFrame]. */
    void ReleaseFrame(const DecodedFrame &frame);

    /** Представляет текущий кадр в Surface; допустимо только для surface output. */
    bool RenderFrame(const DecodedFrame &frame);

    /**
     * Сброс после seek: чистит декодер. Сам seek делает `DemuxSession` — очереди
     * пакетов принадлежат ему.
     */
    bool Flush();

    const DecoderOutput &output() const { return decoder_->output(); }
    const std::string &decoder_name() const { return decoder_->name(); }
    int rung() const { return decoder_->rung(); }
    int64_t frames_out() const { return decoder_->frames_out(); }
    int64_t packets_in() const { return decoder_->packets_in(); }
    bool surface_output() const { return decoder_->surface_output(); }

    /** Индекс декодируемого потока. */
    int stream_index() const { return stream_index_; }

    /** Глубина потока по пробингу: 8, 10 или 12. */
    int stream_bit_depth() const { return stream_bit_depth_; }

    /** Просили ли у декодера 10 бит. */
    bool ten_bit_requested() const { return ten_bit_requested_; }

    /**
     * Пришли ли 10 (и более) бит на самом деле.
     *
     * Осмысленно только после первого кадра: до него раскладка вывода неизвестна
     * (см. шапку файла), и здесь будет false просто потому, что формат ещё не
     * прочитан.
     */
    bool ten_bit_output() const { return DescribeFormat(output().format).sixteen_bit(); }

    /**
     * Причина эскалации на программный декодер и его имя; пусто — эскалации не
     * было.
     */
    const std::string &escalation() const { return escalation_; }

    /** Цвет из пробинга — то, с чем кадр уйдёт в шейдер. */
    ColorStandard standard() const { return standard_; }
    bool full_range() const { return full_range_; }

private:
    DecodeSession() = default;

    /** Создаёт `HwVideoDecoder` с указанным именем компонента (или по MIME). */
    bool StartHardware(bool prefer_ten_bit, const char *codec_name, std::string *error);

    /** Создаёт libavcodec-декодер в тот же FrameDesc. */
    bool StartSoftware(std::string *error);

    /**
     * Первый кадр пришёл не 10-битным: пересоздаёт декодер программным и
     * возвращает демукс к началу.
     *
     * @return false — эскалация не удалась и восстановить декодер не вышло.
     */
    bool Escalate();

    /**
     * Возвращает демукс на нулевую позицию и ждёт, пока он реально перемотает.
     *
     * Ждать обязательно: `DemuxSession::Seek` только оставляет заявку, а
     * выполняет её поток демукса. Без ожидания следующий `TakePacket` вернёт
     * пакет из очереди СТАРОЙ позиции, и новый декодер получит середину потока
     * вместо ключевого кадра — то есть не отдаст ни одного кадра.
     *
     * @return false, если источник не перематывается (live).
     */
    bool RewindDemux();

    /** Забирает пакет из демукса и отдаёт декодеру; false — ошибка. */
    bool Pump(int timeout_ms);

    DemuxSession *demux_ = nullptr;
    VideoDecoder *decoder_ = nullptr;

    /**
     * Копия параметров потока. Живёт всю сессию, потому что нужна повторно при
     * эскалации, а брать её из `AVFormatContext` второй раз нельзя: демуксер
     * правит `codecpar` в своём потоке (см. `CopyCodecParameters`).
     */
    AVCodecParameters *par_ = nullptr;

    const char *mime_ = nullptr;
    int stream_index_ = -1;
    int stream_bit_depth_ = 8;
    int time_base_num_ = 1;
    int time_base_den_ = 1000000;

    ColorStandard standard_ = ColorStandard::kBt709;
    bool full_range_ = false;

    /** 25 байт CTA-861.3 из пробинга; используется, если [has_hdr_] истинно. */
    uint8_t hdr_static_info_[kHdrStaticInfoSize] = {0};
    bool has_hdr_ = false;

    int color_standard_key_ = 0;
    int color_transfer_key_ = 0;
    int color_range_key_ = 0;

    /** Из конфигурации: нужны при эскалации, то есть уже после `Create`. */
    bool allow_escalation_ = true;
    bool force_software_ = false;
    ANativeWindow *surface_ = nullptr;
    /** Копия: строка из JNI не живёт дольше NativeCreate. */
    std::string codec_name_;

    bool ten_bit_requested_ = false;
    /** Глубина первого кадра проверена: больше проверять незачем. */
    bool depth_checked_ = false;
    std::string escalation_;

    /** EOS уже отдан декодеру: пакетов больше не будет. */
    bool eos_pushed_ = false;

    /** RPU is parsed from compressed packets and paired back after B-frame reorder. */
    std::unique_ptr<DoviRpuParser> dovi_parser_;
    std::map<int64_t, std::shared_ptr<const DoviFrameMapping>> dovi_by_pts_;
};

}  // namespace ddd
