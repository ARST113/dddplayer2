/*
 * io_source.h — источник байт для FFmpeg (тот самый `io_bridge` из
 * UNIFIED-ENGINE.md §4).
 *
 * Это механизм сохранения серверных функций DDD. Весь сетевой слой остаётся в
 * Java/OkHttp там, где он написан и работает: TorrServer `/cache` с его
 * индикаторами, `LocalBridgeServer`, `ParsingDataSource`, заголовки запросов,
 * cookies, самоподписанные сертификаты. Движок не знает про HTTP вообще — он
 * читает байты через этот интерфейс. Ровно тот же приём применён в 4XVR, где
 * SMB и UPnP подключены как `MySmbHttpWrapper` / `MyUpnpInputStream`.
 *
 * Из этого следует и решение по TLS: `https` в сборке FFmpeg сознательно
 * отсутствует (§6.1), потому что TLS живёт в OkHttp, а не в двух местах.
 *
 * Интерфейс намеренно повторяет семантику `AVIOContext`, а не `java.io.InputStream`:
 * у AVIO есть `AVSEEK_SIZE`, отрицательные коды ошибок и понятие «поток не
 * перематывается». Приводить эти понятия к InputStream и обратно — это как раз
 * то место, где обычно теряется seek по сети; поэтому граница проведена здесь,
 * а адаптацией занимается Java-сторона (`EngineIo`).
 */
#pragma once

#include <cstdint>
#include <cstdio>

struct AVIOContext;

namespace ddd {

/**
 * Абстрактный источник байт.
 *
 * Реализации:
 *  - `JavaIoSource` (`jni_io_source.h`) — поверх Java-объекта `EngineIo`;
 *  - `FileIoSource` (ниже) — обычный файл, чтобы весь AVIO-путь можно было
 *    проверить нативным тестом без JVM, Gradle и APK.
 *
 * Вызовы приходят из потока демукса (а при пробинге — из вызывающего потока),
 * но никогда одновременно: `DemuxSession` держит источник в одном потоке за раз.
 */
class IoSource {
public:
    virtual ~IoSource() = default;

    /**
     * @param buf  куда писать.
     * @param size сколько максимум.
     * @return прочитано байт (>0); `0` — конец данных; отрицательное — код
     *         `AVERROR`. Возврат 0 при живом источнике недопустим: FFmpeg
     *         воспримет это как EOF и закроет поток.
     */
    virtual int Read(uint8_t *buf, int size) = 0;

    /**
     * @param whence `SEEK_SET` / `SEEK_CUR` / `SEEK_END`.
     * @return новая абсолютная позиция или отрицательный `AVERROR`.
     *         Не вызывается, если [Seekable] вернул false.
     */
    virtual int64_t Seek(int64_t offset, int whence) = 0;

    /** @return размер в байтах или -1, если неизвестен (live, chunked). */
    virtual int64_t Size() = 0;

    /**
     * Можно ли перематывать. Для TorrServer `/cache` — да (сервер держит Range),
     * для live-HLS — нет. От этого зависит, сможет ли FFmpeg дочитать `moov` в
     * конце MP4: без seek прогрессивный MP4 не откроется вовсе.
     */
    virtual bool Seekable() = 0;

    /** Имя для логов: URL или тип источника. */
    virtual const char *Name() const { return "io"; }
};

/**
 * Оборачивает источник в `AVIOContext`, который можно положить в
 * `AVFormatContext::pb`.
 *
 * Буфер выделяется через `av_malloc` (требование FFmpeg) и освобождается в
 * [FreeAvio]. Размер по умолчанию — 64 КБ: столько же берёт
 * `ParsingDataSource.PIPE_BUFFER_SIZE` в DDD и столько же обычно отдаёт один
 * ответ OkHttp, то есть один `Read` = один сетевой чанк без лишней нарезки.
 *
 * @return контекст или nullptr при нехватке памяти.
 */
AVIOContext *MakeAvio(IoSource *source, int buffer_size = 64 * 1024);

/** Освобождает контекст вместе с его буфером. Источник не удаляет. */
void FreeAvio(AVIOContext **ctx);

/**
 * Источник поверх обычного файла.
 *
 * Нужен не для продакшна (там локальные файлы читает сам FFmpeg по протоколу
 * `file`), а для тестов: он позволяет прогнать весь путь
 * «свой AVIOContext → avformat_open_input → пробинг → seek» на устройстве без
 * JVM. Если ошибка видна в нативном бинаре, её не надо искать через три слоя
 * Gradle/JNI — тот же принцип, что в шаге 2.
 */
class FileIoSource : public IoSource {
public:
    /** @return nullptr, если файл не открылся. */
    static FileIoSource *Open(const char *path);

    ~FileIoSource() override;

    int Read(uint8_t *buf, int size) override;
    int64_t Seek(int64_t offset, int whence) override;
    int64_t Size() override;
    bool Seekable() override { return true; }
    const char *Name() const override { return name_; }

private:
    FileIoSource(FILE *f, int64_t size, const char *path);

    FILE *f_ = nullptr;
    int64_t size_ = -1;
    char name_[256] = {0};
};

}  // namespace ddd
