/*
 * jni_io_source.cpp — реализация. См. jni_io_source.h.
 */
#include "jni_io_source.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <thread>

#include "ddd_log.h"
#include "ff_include.h"
#include "jni_util.h"

namespace ddd {
namespace {

/**
 * Сколько раз повторить чтение, если Java вернула 0 байт.
 *
 * Ноль означает «данных пока нет» — так ведут себя обёртки над сетевыми
 * источниками в момент переподключения. Отдать этот ноль в AVIO нельзя: любое
 * отрицательное значение и `AVERROR_EOF` ставят `AVIOContext::eof_reached`, и
 * поток закрывается навсегда (`fill_buffer` в aviobuf.c). То есть один
 * спурьозный ноль от TorrServer превратился бы в «файл кончился» посреди фильма.
 */
constexpr int kZeroReadRetries = 8;
constexpr int kZeroReadWaitMs = 5;

}  // namespace

JavaIoSource *JavaIoSource::Create(JNIEnv *env, jobject engine_io, int buffer_size) {
    if (env == nullptr || engine_io == nullptr || buffer_size <= 0) return nullptr;

    jclass cls = env->GetObjectClass(engine_io);
    if (cls == nullptr) {
        ClearPendingException(env, "GetObjectClass(EngineIo)");
        return nullptr;
    }

    const jmethodID m_read = env->GetMethodID(cls, "read", "([BII)I");
    const jmethodID m_seek = env->GetMethodID(cls, "seekTo", "(J)J");
    const jmethodID m_size = env->GetMethodID(cls, "size", "()J");
    const jmethodID m_seekable = env->GetMethodID(cls, "seekable", "()Z");
    const jmethodID m_name = env->GetMethodID(cls, "name", "()Ljava/lang/String;");
    const jmethodID m_close = env->GetMethodID(cls, "close", "()V");
    if (m_read == nullptr || m_seek == nullptr || m_size == nullptr || m_seekable == nullptr ||
        m_name == nullptr || m_close == nullptr) {
        ClearPendingException(env, "GetMethodID(EngineIo)");
        DDD_LOGE("io/jni: объект не реализует EngineIo целиком");
        env->DeleteLocalRef(cls);
        return nullptr;
    }

    auto *src = new JavaIoSource();
    src->m_read_ = m_read;
    src->m_seek_ = m_seek;
    src->m_close_ = m_close;
    src->buffer_size_ = buffer_size;

    // Размер и перематываемость спрашиваются один раз: см. комментарий в заголовке.
    src->size_ = env->CallLongMethod(engine_io, m_size);
    if (ClearPendingException(env, "EngineIo.size")) src->size_ = -1;
    src->seekable_ = env->CallBooleanMethod(engine_io, m_seekable) == JNI_TRUE;
    if (ClearPendingException(env, "EngineIo.seekable")) src->seekable_ = false;

    auto name = ToUtf8(env, static_cast<jstring>(env->CallObjectMethod(engine_io, m_name)));
    if (ClearPendingException(env, "EngineIo.name")) name.clear();
    snprintf(src->name_, sizeof src->name_, "java:%s", name.empty() ? "?" : name.c_str());

    jbyteArray local_buf = env->NewByteArray(buffer_size);
    if (local_buf == nullptr) {
        ClearPendingException(env, "NewByteArray");
        delete src;
        env->DeleteLocalRef(cls);
        return nullptr;
    }
    src->buffer_ = static_cast<jbyteArray>(env->NewGlobalRef(local_buf));
    env->DeleteLocalRef(local_buf);
    src->io_ = env->NewGlobalRef(engine_io);
    env->DeleteLocalRef(cls);

    if (src->buffer_ == nullptr || src->io_ == nullptr) {
        DDD_LOGE("io/jni: не удалось создать глобальные ссылки");
        delete src;
        return nullptr;
    }

    DDD_LOGI("io/jni: источник '%s' готов (размер=%lld, seekable=%d)", src->name_,
             static_cast<long long>(src->size_), static_cast<int>(src->seekable_));
    return src;
}

JavaIoSource::~JavaIoSource() {
    JNIEnv *env = Env();
    if (env == nullptr) {
        // Без Env освободить ссылки нельзя; это утечка, но падать в деструкторе
        // хуже — сессия может закрываться уже при выгрузке процесса.
        DDD_LOGE("io/jni: нет JNIEnv при закрытии '%s'", name_);
        return;
    }
    if (io_ != nullptr) {
        if (m_close_ != nullptr) {
            env->CallVoidMethod(io_, m_close_);
            ClearPendingException(env, "EngineIo.close");
        }
        env->DeleteGlobalRef(io_);
        io_ = nullptr;
    }
    if (buffer_ != nullptr) {
        env->DeleteGlobalRef(buffer_);
        buffer_ = nullptr;
    }
}

int JavaIoSource::Read(uint8_t *buf, int size) {
    if (buf == nullptr || size <= 0) return 0;
    JNIEnv *env = Env();
    if (env == nullptr) return AVERROR(EIO);

    // Частичное чтение законно: AVIO смотрит на возвращённое число байт. А вот
    // писать в Java-массив больше, чем он вмещает, нельзя — `avio_read` при
    // больших запросах вызывает колбэк напрямую с размером больше буфера AVIO.
    const int want = std::min(size, buffer_size_);

    for (int attempt = 0; attempt <= kZeroReadRetries; ++attempt) {
        const jint n = env->CallIntMethod(io_, m_read_, buffer_, 0, want);
        if (ClearPendingException(env, "EngineIo.read")) return AVERROR(EIO);

        if (n < 0) return AVERROR_EOF;  // -1 = конец данных, как у InputStream
        if (n > want) {
            DDD_LOGE("io/jni: read вернул %d при запросе %d — реализация EngineIo сломана",
                     static_cast<int>(n), want);
            return AVERROR(EIO);
        }
        if (n > 0) {
            env->GetByteArrayRegion(buffer_, 0, n, reinterpret_cast<jbyte *>(buf));
            if (ClearPendingException(env, "GetByteArrayRegion")) return AVERROR(EIO);
            position_ += n;
            return static_cast<int>(n);
        }
        if (attempt < kZeroReadRetries)
            std::this_thread::sleep_for(std::chrono::milliseconds(kZeroReadWaitMs));
    }

    DDD_LOGE("io/jni: '%s' отдал 0 байт %d раз подряд", name_, kZeroReadRetries + 1);
    return AVERROR(EIO);
}

int64_t JavaIoSource::Seek(int64_t offset, int whence) {
    JNIEnv *env = Env();
    if (env == nullptr) return AVERROR(EIO);

    int64_t target = offset;
    switch (whence) {
        case SEEK_SET:
            break;
        case SEEK_CUR:
            target = position_ + offset;
            break;
        case SEEK_END:
            if (size_ < 0) return AVERROR(ENOSYS);  // размер неизвестен — от конца не отсчитать
            target = size_ + offset;
            break;
        default:
            return AVERROR(EINVAL);
    }
    if (target < 0) return AVERROR(EINVAL);

    const jlong actual = env->CallLongMethod(io_, m_seek_, static_cast<jlong>(target));
    if (ClearPendingException(env, "EngineIo.seekTo")) return AVERROR(EIO);
    if (actual < 0) return AVERROR(EIO);

    position_ = actual;
    return position_;
}

int64_t JavaIoSource::Size() { return size_; }

bool JavaIoSource::Seekable() { return seekable_; }

}  // namespace ddd
