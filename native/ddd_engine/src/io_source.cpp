/*
 * io_source.cpp — колбэки AVIO и файловый источник.
 *
 * Три места, где реализация AVIO-колбэков обычно ломается молча, и как это
 * решено здесь:
 *
 * 1. EOF. Колбэк `read_packet` обязан вернуть `AVERROR_EOF`, а не 0: ноль
 *    FFmpeg трактует как «попробуй ещё раз» и в некоторых демуксерах уходит в
 *    бесконечный цикл. Поэтому 0 от источника здесь конвертируется в AVERROR_EOF.
 *
 * 2. `AVSEEK_SIZE`. FFmpeg спрашивает размер тем же колбэком, что и seek, и
 *    может добавить к `whence` флаг `AVSEEK_FORCE`. Сравнение `whence ==
 *    AVSEEK_SIZE` без снятия флага иногда даёт seek на 0x10000 вместо ответа о
 *    размере — а это «файл повреждён» на ровном месте.
 *
 * 3. Флаг `seekable`. Если источник не перематывается, а `AVIOContext::seekable`
 *    остался равным `AVIO_SEEKABLE_NORMAL`, mov-демуксер попытается прыгнуть за
 *    `moov` и получит ошибку вместо того, чтобы честно сказать «нужен seek».
 */
#include "io_source.h"

#include <cstring>

#include "ddd_log.h"
#include "ff_include.h"

namespace ddd {
namespace {

int AvioRead(void *opaque, uint8_t *buf, int buf_size) {
    auto *src = static_cast<IoSource *>(opaque);
    const int n = src->Read(buf, buf_size);
    if (n == 0) return AVERROR_EOF;  // см. п.1 в заголовке файла
    return n;
}

int64_t AvioSeek(void *opaque, int64_t offset, int whence) {
    auto *src = static_cast<IoSource *>(opaque);

    // См. п.2: AVSEEK_FORCE — это флаг поверх whence, его надо снять до сравнения.
    const int w = whence & ~AVSEEK_FORCE;

    if (w == AVSEEK_SIZE) {
        const int64_t size = src->Size();
        return size >= 0 ? size : AVERROR(ENOSYS);
    }
    if (!src->Seekable()) return AVERROR(EPIPE);
    return src->Seek(offset, w);
}

}  // namespace

AVIOContext *MakeAvio(IoSource *source, int buffer_size) {
    if (source == nullptr || buffer_size <= 0) return nullptr;

    auto *buffer = static_cast<unsigned char *>(av_malloc(static_cast<size_t>(buffer_size)));
    if (buffer == nullptr) {
        DDD_LOGE("io: не хватило памяти на буфер AVIO (%d Б)", buffer_size);
        return nullptr;
    }

    AVIOContext *ctx = avio_alloc_context(buffer, buffer_size, /*write_flag=*/0, source,
                                          AvioRead, /*write_packet=*/nullptr, AvioSeek);
    if (ctx == nullptr) {
        av_free(buffer);
        DDD_LOGE("io: avio_alloc_context вернул null");
        return nullptr;
    }

    // См. п.3: демуксер обязан знать правду о перематываемости.
    ctx->seekable = source->Seekable() ? AVIO_SEEKABLE_NORMAL : 0;

    DDD_LOGI("io: источник '%s' подключён (буфер %d Б, seekable=%d, размер=%lld)",
             source->Name(), buffer_size, ctx->seekable, static_cast<long long>(source->Size()));
    return ctx;
}

void FreeAvio(AVIOContext **ctx) {
    if (ctx == nullptr || *ctx == nullptr) return;
    // Буфер мог быть заменён FFmpeg на свой при увеличении: освобождать надо тот,
    // что лежит в контексте сейчас, а не тот, что передавался в alloc.
    av_freep(&(*ctx)->buffer);
    avio_context_free(ctx);
}

// ───────────────────────────── FileIoSource ─────────────────────────────

FileIoSource::FileIoSource(FILE *f, int64_t size, const char *path) : f_(f), size_(size) {
    snprintf(name_, sizeof name_, "file:%s", path ? path : "?");
}

FileIoSource *FileIoSource::Open(const char *path) {
    if (path == nullptr) return nullptr;
    FILE *f = fopen(path, "rb");
    if (f == nullptr) {
        DDD_LOGE("io: не открылся файл %s", path);
        return nullptr;
    }
    int64_t size = -1;
    if (fseeko(f, 0, SEEK_END) == 0) {
        size = static_cast<int64_t>(ftello(f));
        fseeko(f, 0, SEEK_SET);
    }
    return new FileIoSource(f, size, path);
}

FileIoSource::~FileIoSource() {
    if (f_ != nullptr) fclose(f_);
}

int FileIoSource::Read(uint8_t *buf, int size) {
    if (f_ == nullptr) return AVERROR(EBADF);
    const size_t n = fread(buf, 1, static_cast<size_t>(size), f_);
    if (n == 0) return ferror(f_) ? AVERROR(EIO) : 0;
    return static_cast<int>(n);
}

int64_t FileIoSource::Seek(int64_t offset, int whence) {
    if (f_ == nullptr) return AVERROR(EBADF);
    if (fseeko(f_, static_cast<off_t>(offset), whence) != 0) return AVERROR(EINVAL);
    return static_cast<int64_t>(ftello(f_));
}

int64_t FileIoSource::Size() { return size_; }

}  // namespace ddd
