/*
 * render_reference.cpp — реализация (см. render_reference.h).
 */
#include "render_reference.h"

extern "C" {
#include <libswscale/swscale.h>
}

#include <cstring>

#include "ddd_log.h"
#include "ff_include.h"

namespace ddd {

namespace {

inline int DivUp(int v, int d) { return (v + d - 1) / d; }

/** Выравнивание строк на 64 байта: так же, как это делает libavcodec. */
inline int AlignStride(int bytes) { return (bytes + 63) & ~63; }

AVPixelFormat ToAvPixelFormat(FramePixelFormat format) {
    switch (format) {
        case FramePixelFormat::kNv12: return AV_PIX_FMT_NV12;
        case FramePixelFormat::kNv21: return AV_PIX_FMT_NV21;
        case FramePixelFormat::kYuv420p10le: return AV_PIX_FMT_YUV420P10LE;
        case FramePixelFormat::kYuv420p12le: return AV_PIX_FMT_YUV420P12LE;
        case FramePixelFormat::kYuv420p16le: return AV_PIX_FMT_YUV420P16LE;
        case FramePixelFormat::kYuv422p10le: return AV_PIX_FMT_YUV422P10LE;
        case FramePixelFormat::kYuv422p12le: return AV_PIX_FMT_YUV422P12LE;
        case FramePixelFormat::kYuv422p16le: return AV_PIX_FMT_YUV422P16LE;
        case FramePixelFormat::kYuv444p10le: return AV_PIX_FMT_YUV444P10LE;
        case FramePixelFormat::kYuv444p12le: return AV_PIX_FMT_YUV444P12LE;
        case FramePixelFormat::kYuv444p16le: return AV_PIX_FMT_YUV444P16LE;
        case FramePixelFormat::kP010: return AV_PIX_FMT_P010LE;
        case FramePixelFormat::kYuv420p:
        default: return AV_PIX_FMT_YUV420P;
    }
}

int ToSwsColorspace(ColorStandard standard) {
    switch (standard) {
        case ColorStandard::kBt601: return SWS_CS_ITU601;
        case ColorStandard::kBt2020: return SWS_CS_BT2020;
        case ColorStandard::kBt709:
        default: return SWS_CS_ITU709;
    }
}

}  // namespace

FrameDesc SyntheticFrame::desc() const {
    FrameDesc d;
    d.width = width_;
    d.height = height_;
    d.format = format_;
    d.standard = standard_;
    d.full_range = full_range_;
    for (int i = 0; i < 3; ++i) {
        d.stride[i] = stride_[i];
        d.plane[i] = stride_[i] > 0 ? data_.data() + offset_[i] : nullptr;
    }
    return d;
}

SyntheticFrame SyntheticFrame::Allocate(int width, int height, FramePixelFormat format,
                                        ColorStandard standard, bool full_range) {
    SyntheticFrame f;
    f.width_ = width;
    f.height_ = height;
    f.format_ = format;
    f.standard_ = standard;
    f.full_range_ = full_range;

    const FormatInfo info = DescribeFormat(format);
    const int bytes = info.sixteen_bit() ? 2 : 1;
    const int chroma_w = DivUp(width, info.sub_x);
    const int chroma_h = DivUp(height, info.sub_y);

    f.stride_[0] = AlignStride(width * bytes);
    f.stride_[1] = AlignStride(chroma_w * (info.semiplanar ? 2 : 1) * bytes);
    f.stride_[2] = info.semiplanar ? 0 : AlignStride(chroma_w * bytes);

    const size_t y_size = static_cast<size_t>(f.stride_[0]) * height;
    const size_t u_size = static_cast<size_t>(f.stride_[1]) * chroma_h;
    const size_t v_size = static_cast<size_t>(f.stride_[2]) * chroma_h;

    f.offset_[0] = 0;
    f.offset_[1] = y_size;
    f.offset_[2] = y_size + u_size;
    f.data_.assign(y_size + u_size + v_size, 0);
    return f;
}

SyntheticFrame SyntheticFrame::MakeNoise(int width, int height, FramePixelFormat format,
                                         ColorStandard standard, bool full_range, uint32_t seed) {
    SyntheticFrame f = Allocate(width, height, format, standard, full_range);
    // Линейный конгруэнтный генератор вместо rand(): нужен воспроизводимый шум
    // без зависимости от состояния libc, а качество случайности здесь не важно.
    // Заполняется весь буфер, включая выравнивающие хвосты строк: они в текстуру
    // не попадают, но и обнулять их незачем.
    uint32_t state = seed != 0 ? seed : 1u;
    for (size_t i = 0; i < f.data_.size(); ++i) {
        state = state * 1664525u + 1013904223u;
        f.data_[i] = static_cast<uint8_t>(state >> 24);
    }
    return f;
}

void SyntheticFrame::Touch(uint32_t counter) {
    if (data_.empty()) return;
    data_[counter % data_.size()] = static_cast<uint8_t>(counter);
}

bool SwscaleReference(const FrameDesc &frame, uint8_t *out_rgba) {
    if (frame.width <= 0 || frame.height <= 0 || out_rgba == nullptr) return false;
    if (DescribeFormat(frame.format).sixteen_bit()) {
        // Молча отдать 8 бит на 10-битном кадре было бы худшим вариантом: тест
        // сравнил бы GL с загрублённым эталоном и «прошёл».
        DDD_LOGE("sws: 8-битный эталон вызван для 16-битного кадра — нужен SwscaleReference16");
        return false;
    }

    // SWS_POINT: масштабирования здесь нет вообще (размеры совпадают), но флаг
    // фиксирует способ размножения цветности — повтор, а не интерполяция. Без
    // него swscale вправе выбрать интерполяцию, и эталон начнёт зависеть от
    // версии библиотеки.
    SwsContext *ctx = sws_getContext(frame.width, frame.height, ToAvPixelFormat(frame.format),
                                     frame.width, frame.height, AV_PIX_FMT_RGBA, SWS_POINT, nullptr,
                                     nullptr, nullptr);
    if (ctx == nullptr) {
        DDD_LOGE("sws: не создан контекст %dx%d", frame.width, frame.height);
        return false;
    }

    const int *coefs = sws_getCoefficients(ToSwsColorspace(frame.standard));
    // dst_range = 1: RGB на выходе всегда полного диапазона. Именно это
    // соответствует тому, что видно на экране, и тому, что делает GL-шейдер.
    if (sws_setColorspaceDetails(ctx, coefs, frame.full_range ? 1 : 0,
                                 sws_getCoefficients(SWS_CS_DEFAULT), 1, 0, 1 << 16, 1 << 16) < 0) {
        DDD_LOGW("sws: sws_setColorspaceDetails не принят — эталон может разойтись");
    }

    const uint8_t *src[4] = {frame.plane[0], frame.plane[1], frame.plane[2], nullptr};
    const int src_stride[4] = {frame.stride[0], frame.stride[1], frame.stride[2], 0};
    uint8_t *dst[4] = {out_rgba, nullptr, nullptr, nullptr};
    const int dst_stride[4] = {frame.width * 4, 0, 0, 0};

    const int rows = sws_scale(ctx, src, src_stride, 0, frame.height, dst, dst_stride);
    sws_freeContext(ctx);

    if (rows != frame.height) {
        DDD_LOGE("sws: обработано %d строк из %d", rows, frame.height);
        return false;
    }
    return true;
}

bool SwscaleReference16(const FrameDesc &frame, uint16_t *out_rgba64) {
    if (frame.width <= 0 || frame.height <= 0 || out_rgba64 == nullptr) return false;

    const AVPixelFormat src_format = ToAvPixelFormat(frame.format);
    // Проверка до создания контекста: sws_getContext на неподдерживаемом входе
    // вернёт nullptr с невнятным сообщением, а здесь видно, какой именно формат
    // библиотека читать не умеет. P010 как ВХОД поддерживается не во всех
    // сборках — поэтому P010 сверяется не с эталоном, а с раскладкой
    // YUV420P10LE, собранной в тесте из тех же кодов.
    if (sws_isSupportedInput(src_format) == 0) {
        DDD_LOGE("sws: формат %d не поддерживается на входе", static_cast<int>(src_format));
        return false;
    }

    // RGBA64LE, а не RGBA: 10 бит на входе не помещаются в 8-битный выход, и
    // эталон стал бы грубее проверяемого. Порядок байт зафиксирован (LE), чтобы
    // сравнение не зависело от хоста.
    SwsContext *ctx =
            sws_getContext(frame.width, frame.height, src_format, frame.width, frame.height,
                           AV_PIX_FMT_RGBA64LE, SWS_POINT, nullptr, nullptr, nullptr);
    if (ctx == nullptr) {
        DDD_LOGE("sws16: не создан контекст %dx%d формат %d", frame.width, frame.height,
                 static_cast<int>(src_format));
        return false;
    }

    const int *coefs = sws_getCoefficients(ToSwsColorspace(frame.standard));
    if (sws_setColorspaceDetails(ctx, coefs, frame.full_range ? 1 : 0,
                                 sws_getCoefficients(SWS_CS_DEFAULT), 1, 0, 1 << 16, 1 << 16) < 0) {
        DDD_LOGW("sws16: sws_setColorspaceDetails не принят — эталон может разойтись");
    }

    const uint8_t *src[4] = {frame.plane[0], frame.plane[1], frame.plane[2], nullptr};
    const int src_stride[4] = {frame.stride[0], frame.stride[1], frame.stride[2], 0};
    uint8_t *dst[4] = {reinterpret_cast<uint8_t *>(out_rgba64), nullptr, nullptr, nullptr};
    // 8 байт на пиксель: четыре канала по 16 бит.
    const int dst_stride[4] = {frame.width * 8, 0, 0, 0};

    const int rows = sws_scale(ctx, src, src_stride, 0, frame.height, dst, dst_stride);
    sws_freeContext(ctx);

    if (rows != frame.height) {
        DDD_LOGE("sws16: обработано %d строк из %d", rows, frame.height);
        return false;
    }
    return true;
}

}  // namespace ddd
