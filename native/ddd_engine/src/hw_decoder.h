/*
 * hw_decoder.h — аппаратный декодер видео через `AMediaCodec`, вывод в память.
 *
 * Это вторая половина шага 5 (UNIFIED-ENGINE.md §6): первая дала 10-битный путь
 * в текстуру, здесь появляется источник настоящих 10-битных кадров.
 *
 * ПОЧЕМУ ByteBuffer, А НЕ ВЫВОД В SURFACE. Отдать декодеру `Surface` проще и
 * дешевле: кадр не ходит через CPU вовсе. Но тогда пиксели попадают в текстуру
 * уже сконвертированными драйвером — в 8 бит RGB, по его собственной матрице и с
 * его собственным тонмаппингом. Ровно это делает текущий DDD через Media3, и
 * ровно отсюда берётся вымытая картинка на гарнитуре: к моменту, когда кадр
 * доступен нам, 10 бит и PQ уже потеряны. Поэтому здесь запрашивается
 * `COLOR_FormatYUVP010` в память: конверсию, тонмаппинг (шаг 6) и Dolby Vision
 * (шаг 10) делает наш шейдер, и делает по метаданным файла, а не «на глаз».
 *
 * Цена решения — одно чтение кадра из буфера декодера в текстуру: на 4K 10 бит
 * это 12 МБ на кадр. Замер пути заливки на 4K дал 6.98 мс с отрисовкой и
 * `glFinish` (см. `UploadPath::kBytePair`), то есть бюджет 24–30 fps остаётся.
 *
 * ПОЧЕМУ КЛЮЧИ `AMediaFormat` ЗАДАНЫ СТРОКАМИ, А НЕ КОНСТАНТАМИ NDK. Половина
 * нужных констант (`AMEDIAFORMAT_KEY_SLICE_HEIGHT`, `_HDR_STATIC_INFO`,
 * `_COLOR_STANDARD`, `_CSD_0`) помечена `__INTRODUCED_IN(28)`, а minSdk проекта —
 * 23. Ссылка на них потребовала бы `__builtin_available` вокруг каждого вызова, и
 * при этом сами имена ключей («slice-height», «hdr-static-info») — часть
 * контракта `MediaFormat` и не менялись ни разу. Строка работает на всех
 * версиях; проверять надо не наличие константы, а наличие ключа в ответе
 * декодера, что здесь и делается.
 */
#pragma once

#include <sys/types.h>

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "video_renderer.h"

struct AMediaCodec;
struct AMediaFormat;
struct ANativeWindow;
struct AVBSFContext;
struct AVCodecParameters;
struct AVPacket;

namespace ddd {

struct DoviFrameMapping;

/**
 * Значения `MediaFormat.KEY_COLOR_FORMAT`, которые нас касаются.
 *
 * Числа заданы литералами по той же причине, что и ключи: в NDK их нет вовсе —
 * они объявлены только в Java (`MediaCodecInfo.CodecCapabilities`).
 */
enum : int32_t {
    kColorFormatYuv420Planar = 19,
    kColorFormatYuv420PackedPlanar = 20,
    kColorFormatYuv420SemiPlanar = 21,
    kColorFormatYuv420PackedSemiPlanar = 39,
    /**
     * Y + перемежённые UV по 16 бит, значащие 10 в старших разрядах.
     * Единственный 10-битный формат вывода в память, который стандартизован
     * Android (API 29); всё остальное — вендорские раскладки.
     */
    kColorFormatYuvP010 = 54,
    /** «Любая раскладка 4:2:0»: декодер обязан вернуть в ответе конкретную. */
    kColorFormatYuv420Flexible = 0x7F420888,
    kColorFormatQcomYuv420SemiPlanar = 0x7FA30C00,
    kColorFormatQcomYuv420SemiPlanar32m = 0x7FA30C04,
    kColorFormatTiYuv420PackedSemiPlanar = 0x7F000100
};

/**
 * Имя программного декодера Android для MIME; nullptr, если такого нет.
 *
 * Нужно снаружи, потому что выбрать программный декодер средствами NDK нельзя
 * вовсе: `AMediaCodecStore` появился в API 36, а `MediaCodecList` живёт в Java.
 * Имена компонентов C2 стабильны с Android 10 и заданы в AOSP
 * (`media_codecs_google_video.xml`).
 *
 * @param attempt 0 — `c2.android.*`; 1 — `OMX.google.*` для прошивок до Android 10.
 */
const char *SoftwareDecoderName(const char *mime, int attempt);

/** Что отдать `AMediaCodec_configure`. */
struct DecoderConfig {
    /** MIME из `MimeFromCodecId`; без него декодер не выбрать. */
    const char *mime = nullptr;

    /**
     * Параметры потока: размеры, `extradata`, `codec_id`. Не во владении, но
     * должны жить до возврата из `Create` (extradata читается в csd-0).
     */
    const AVCodecParameters *par = nullptr;

    /** 25 байт CTA-861.3 или nullptr; см. `hdr_static_info.h`. */
    const uint8_t *hdr_static_info = nullptr;

    /** Значения `MediaFormat.KEY_COLOR_*`; 0 — ключ не задавать. */
    int color_standard = 0;
    int color_transfer = 0;
    int color_range = 0;

    /**
     * Просить `COLOR_FormatYUVP010` вместо 8-битной раскладки.
     *
     * Именно «просить»: декодер вправе ответить чем угодно, и что пришло на
     * самом деле, видно в [DecoderOutput::color_format].
     */
    bool prefer_ten_bit = false;

    /**
     * Конкретный кодек по имени вместо выбора по MIME; nullptr — по MIME.
     *
     * Нужно, потому что «декодер для video/hevc» и «декодер для video/hevc,
     * умеющий P010 в память» — разные множества. Когда аппаратный отказывается,
     * лестница в [HwVideoDecoder::Create] пробует программный по имени, и это
     * не то же самое, что путь шага 7: кадр по-прежнему идёт через MediaCodec,
     * а не через libavcodec.
     */
    const char *codec_name = nullptr;

    /**
     * Не-null включает прямой вывод MediaCodec в Surface.
     *
     * Это внутренний путь единого движка для декодеров (в первую очередь
     * Android dav1d), которые не отдают CPU ByteBuffer. Демукс, audio clock,
     * seek и плейлист при этом остаются теми же; меняется только последний
     * шаг выдачи декодированного SDR-кадра.
     */
    ANativeWindow *surface = nullptr;
};

/** Как декодер разложил кадр в буфере. Заполняется из output format. */
struct DecoderOutput {
    /** Сырое значение `KEY_COLOR_FORMAT` — то, что реально вернул декодер. */
    int32_t color_format = 0;
    FramePixelFormat format = FramePixelFormat::kYuv420p;
    /** Видимый размер: после применения crop. */
    int width = 0;
    int height = 0;
    /** Байт на строку плоскости Y. */
    int stride = 0;
    /** Строк в плоскости Y, включая невидимые: смещение плоскости UV. */
    int slice_height = 0;
    int crop_left = 0;
    int crop_top = 0;
    /** Ключи `stride`/`slice-height` пришли от декодера, а не додуманы нами. */
    bool stride_reported = false;
};

/**
 * Кадр в буфере декодера. Указатели живут до [HwVideoDecoder::ReleaseFrame] —
 * буфер принадлежит декодеру, и до возврата его нельзя ни держать, ни копить.
 */
struct DecodedFrame {
    FrameDesc frame;
    int64_t pts_us = 0;
    /** Dolby Vision RPU mapping paired to this exact presentation timestamp. */
    std::shared_ptr<const DoviFrameMapping> dovi_mapping;
    /** Индекс буфера декодера; -1 — кадра нет. */
    ssize_t index = -1;
};

/** Общий контракт HW MediaCodec и SW libavcodec для DecodeSession. */
class VideoDecoder {
public:
    enum class Feed { kQueued, kBusy, kError };
    enum class Pull { kFrame, kAgain, kEos, kError };

    virtual ~VideoDecoder() = default;
    virtual Feed Push(AVPacket *pkt, int64_t pts_us, int timeout_ms) = 0;
    virtual bool has_pending() const = 0;
    virtual Feed PushPending(int timeout_ms) = 0;
    virtual Feed PushEos(int timeout_ms) = 0;
    virtual Pull DequeueFrame(DecodedFrame *out, int timeout_ms) = 0;
    virtual void ReleaseFrame(const DecodedFrame &frame) = 0;
    /** Отдать текущий output buffer в настроенный Surface. */
    virtual bool RenderFrame(const DecodedFrame &frame) = 0;
    virtual bool Flush() = 0;

    virtual const DecoderOutput &output() const = 0;
    virtual const std::string &name() const = 0;
    /** 1–3 MediaCodec, 4 libavcodec. */
    virtual int rung() const = 0;
    virtual int64_t frames_out() const = 0;
    virtual int64_t packets_in() const = 0;
    virtual bool surface_output() const = 0;
};

class HwVideoDecoder final : public VideoDecoder {
public:
    /**
     * Создаёт и запускает декодер.
     *
     * Пробует по порядку: (1) декодер по MIME с запрошенным цветовым форматом;
     * (2) тот же декодер без ключа `color-format` — пусть выберет сам; (3)
     * программные декодеры Android по имени (`c2.android.*`, на старых
     * прошивках `OMX.google.*`). Причина лестницы в том, что поддержка
     * `COLOR_FormatYUVP010` в память у аппаратных декодеров необязательна, а
     * молча получить 8 бит на 10-битном файле — это ровно тот отказ, который
     * шаг 5 обязан заметить, а не сгладить.
     *
     * @param error сюда пишется причина отказа (может быть nullptr).
     * @return nullptr, если ни одна ступень не поднялась.
     */
    static HwVideoDecoder *Create(const DecoderConfig &cfg, std::string *error);

    ~HwVideoDecoder() override;

    HwVideoDecoder(const HwVideoDecoder &) = delete;
    HwVideoDecoder &operator=(const HwVideoDecoder &) = delete;

    /**
     * Отдаёт пакет декодеру. Владение пакетом переходит декодеру в любом случае.
     *
     * Требует `!has_pending()`. При [Feed::kBusy] отфильтрованные данные
     * сохранены внутри, и следующим вызовом обязан быть [PushPending] — иначе
     * кадр потеряется молча, а на экране это выглядит как рывок без причины.
     */
    Feed Push(AVPacket *pkt, int64_t pts_us, int timeout_ms) override;

    /** Есть ли непереданный остаток после [Feed::kBusy]. */
    bool has_pending() const override { return !pending_.empty(); }

    /** Дописывает остаток во входные буферы декодера. */
    Feed PushPending(int timeout_ms) override;

    /** Сообщает конец данных: после этого декодер отдаст оставшиеся кадры. */
    Feed PushEos(int timeout_ms) override;

    /**
     * Вынимает кадр. Возвращённые указатели действительны до [ReleaseFrame].
     *
     * @param out заполняется при [Pull::kFrame].
     */
    Pull DequeueFrame(DecodedFrame *out, int timeout_ms) override;

    /** Возвращает буфер декодеру. Обязателен для каждого [Pull::kFrame]. */
    void ReleaseFrame(const DecodedFrame &frame) override;

    bool RenderFrame(const DecodedFrame &frame) override;

    /** Сбрасывает состояние декодера при seek. */
    bool Flush() override;

    const DecoderOutput &output() const override { return output_; }

    /** Имя реально работающего декодера или «?» — в баг-репорты и в лог. */
    const std::string &name() const override { return name_; }

    /** Какая ступень лестницы [Create] сработала: 1, 2 или 3. */
    int rung() const override { return rung_; }

    int64_t frames_out() const override { return frames_out_; }
    int64_t packets_in() const override { return packets_in_; }
    bool surface_output() const override { return surface_output_; }

private:
    HwVideoDecoder() = default;

    /** Одна попытка поднять декодер; заполняет codec_ и name_. */
    bool Start(const DecoderConfig &cfg, const char *codec_name, bool set_color_format,
               std::string *error);

    /**
     * Готовит фильтр Annex-B, если extradata в формате hvcC/avcC.
     *
     * MediaCodec принимает H.264 и HEVC только в Annex-B: и csd-0, и каждый
     * пакет. Контейнеры MP4 и Matroska хранят их с префиксом длины, поэтому без
     * этого преобразования декодер получает мусор и молча не отдаёт ни одного
     * кадра — самый неприятный вид отказа, потому что выглядит как «файл не
     * играет», а не как ошибка.
     */
    bool PrepareBitstreamFilter(const AVCodecParameters *par, std::string *error);

    /** Забирает output format в [output_]; true — раскладка распознана. */
    bool ReadOutputFormat(std::string *error);

    /** Кладёт содержимое [pending_] в буфер декодера. */
    Feed WritePending(int timeout_ms);

    AMediaCodec *codec_ = nullptr;
    AVBSFContext *bsf_ = nullptr;

    /** Annex-B версия extradata: то, что уходит в csd-0. */
    std::vector<uint8_t> csd_;

    /**
     * Отфильтрованные данные, которым не хватило входного буфера.
     *
     * Копия, а не `AVPacket*`: пакет после `av_bsf_receive_packet` всё равно
     * пришлось бы держать живым, а размер здесь — единицы килобайт.
     */
    std::vector<uint8_t> pending_;
    int64_t pending_pts_us_ = 0;

    DecoderOutput output_;
    std::string name_ = "?";
    int rung_ = 0;
    int64_t frames_out_ = 0;
    int64_t packets_in_ = 0;
    bool eos_sent_ = false;
    bool format_known_ = false;
    bool surface_output_ = false;
};

}  // namespace ddd
