/*
 * jni_io_source.h — `IoSource` поверх Java-объекта `EngineIo`.
 *
 * Это то самое место, где сохраняются серверные функции DDD: TorrServer `/cache`,
 * `LocalBridgeServer`, `ParsingDataSource`, OkHttp с его заголовками, cookies и
 * TLS остаются в Java как есть, а движок читает у них байты. Ни строчки сетевого
 * кода в native не переносится — переносится только чтение.
 *
 * Java-контракт сделан максимально узким: чтение, абсолютное позиционирование,
 * размер, признак перематываемости, закрытие. Арифметику `SEEK_CUR`/`SEEK_END`
 * делает native, потому что он и так обязан знать текущую позицию, а реализация
 * whence на стороне Java — это ещё одно место, где легко ошибиться на единицу и
 * получить «перемотка иногда уезжает».
 */
#pragma once

#include <jni.h>

#include <cstdint>

#include "io_source.h"

namespace ddd {

class JavaIoSource : public IoSource {
public:
    /**
     * @param engine_io  объект, реализующий `top.rootu.dddplayer.engine.EngineIo`.
     *                   Должен быть уже открыт: [Size] и [Seekable] спрашиваются
     *                   один раз, до первого чтения (их запрашивает
     *                   `avio_alloc_context` в `MakeAvio`).
     * @param buffer_size размер промежуточного `byte[]`; совпадает с размером
     *                   буфера AVIO, чтобы одно чтение FFmpeg = один вызов в Java.
     * @return nullptr, если у объекта нет нужных методов или не хватило памяти.
     */
    static JavaIoSource *Create(JNIEnv *env, jobject engine_io, int buffer_size = 64 * 1024);

    /** Вызывает `EngineIo.close()`: владение источником у движка, значит и закрытие. */
    ~JavaIoSource() override;

    int Read(uint8_t *buf, int size) override;
    int64_t Seek(int64_t offset, int whence) override;
    int64_t Size() override;
    bool Seekable() override;
    const char *Name() const override { return name_; }

private:
    JavaIoSource() = default;

    /** Глобальная ссылка: объект живёт дольше вызова, создавшего источник. */
    jobject io_ = nullptr;
    /**
     * Промежуточный Java-массив.
     *
     * Он нужен потому, что `byte[]` — единственный способ отдать данные из Java
     * без копирования через `DirectByteBuffer` (который потребовал бы менять
     * реализации `DataSource` в DDD). Массив создаётся один раз на источник:
     * выделять его на каждое чтение — это 64 КБ мусора на каждый пакет.
     */
    jbyteArray buffer_ = nullptr;
    int buffer_size_ = 0;

    jmethodID m_read_ = nullptr;
    jmethodID m_seek_ = nullptr;
    jmethodID m_close_ = nullptr;

    /**
     * Размер и перематываемость кэшируются при создании.
     *
     * У Media3 длина известна из `DataSource.open()`, и меняться она не может:
     * если бы она менялась, `AVSEEK_SIZE` возвращал бы разные ответы на один и
     * тот же файл, и mov-демуксер терял бы `moov`.
     */
    int64_t size_ = -1;
    bool seekable_ = false;

    /** Текущая позиция чтения — нужна для `SEEK_CUR`. */
    int64_t position_ = 0;

    char name_[256] = {0};
};

}  // namespace ddd
