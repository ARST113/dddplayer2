/*
 * gl_render_target.cpp — реализация (см. gl_render_target.h).
 */
#include "gl_render_target.h"

#include <GLES3/gl3.h>

#include <vector>

#include "ddd_log.h"
#include "gl_util.h"

namespace ddd {

GlRenderTarget *GlRenderTarget::Create(int width, int height, bool ten_bit) {
    if (width <= 0 || height <= 0) {
        DDD_LOGE("fbo: неверный размер %dx%d", width, height);
        return nullptr;
    }

    GlRenderTarget *self = new GlRenderTarget();
    self->width_ = width;
    self->height_ = height;
    self->ten_bit_ = ten_bit;

    glGenTextures(1, &self->texture_);
    glBindTexture(GL_TEXTURE_2D, self->texture_);
    // GL_RGB10_A2 — color-renderable в ядре ES 3.0, расширения не нужно.
    glTexStorage2D(GL_TEXTURE_2D, 1, ten_bit ? GL_RGB10_A2 : GL_RGBA8, width, height);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);

    glGenFramebuffers(1, &self->fbo_);
    glBindFramebuffer(GL_FRAMEBUFFER, self->fbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, self->texture_, 0);

    const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        DDD_LOGE("fbo: неполный framebuffer 0x%x (%dx%d, %s)", status, width, height,
                 ten_bit ? "RGB10_A2" : "RGBA8");
        delete self;
        return nullptr;
    }
    if (!CheckGlError("GlRenderTarget::Create")) {
        delete self;
        return nullptr;
    }
    return self;
}

GlRenderTarget::~GlRenderTarget() {
    if (fbo_ != 0) glDeleteFramebuffers(1, &fbo_);
    if (texture_ != 0) glDeleteTextures(1, &texture_);
}

void GlRenderTarget::Bind() const { glBindFramebuffer(GL_FRAMEBUFFER, fbo_); }

void GlRenderTarget::Unbind() { glBindFramebuffer(GL_FRAMEBUFFER, 0); }

bool GlRenderTarget::ReadPacked10(uint32_t *out) const {
    if (out == nullptr) return false;

    const size_t pixels = static_cast<size_t>(width_) * static_cast<size_t>(height_);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);

    std::vector<uint32_t> raw(pixels);
    if (ten_bit_) {
        // Из ES 3.0 гарантирована только пара GL_RGBA + GL_UNSIGNED_BYTE, а
        // вторая пара — на выбор реализации, и узнать её можно лишь запросом.
        // Читать 10-битную цель через GL_UNSIGNED_BYTE бессмысленно: это ровно
        // та потеря двух битов, которую здесь и надо поймать. Поэтому если
        // реализация не предлагает 2_10_10_10_REV — честная ошибка, а не тихое
        // чтение восьми бит.
        GLint read_format = 0;
        GLint read_type = 0;
        glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_FORMAT, &read_format);
        glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_TYPE, &read_type);
        if (read_type != GL_UNSIGNED_INT_2_10_10_10_REV || read_format != GL_RGBA) {
            DDD_LOGE("fbo: GPU не отдаёт RGB10_A2 напрямую (формат 0x%x, тип 0x%x)",
                     static_cast<unsigned>(read_format), static_cast<unsigned>(read_type));
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            return false;
        }
        glReadPixels(0, 0, width_, height_, GL_RGBA, GL_UNSIGNED_INT_2_10_10_10_REV, raw.data());
    } else {
        std::vector<uint8_t> bytes(pixels * 4);
        glReadPixels(0, 0, width_, height_, GL_RGBA, GL_UNSIGNED_BYTE, bytes.data());
        for (size_t i = 0; i < pixels; ++i) {
            // Монотонное расширение 8→10: c<<2 | c>>6. Оно сохраняет и 0, и
            // максимум (255 → 1023), и порядок, поэтому подсчёт различных
            // уровней остаётся честным сравнением с 10-битной целью.
            const uint32_t r = bytes[i * 4 + 0];
            const uint32_t g = bytes[i * 4 + 1];
            const uint32_t b = bytes[i * 4 + 2];
            const uint32_t r10 = (r << 2) | (r >> 6);
            const uint32_t g10 = (g << 2) | (g >> 6);
            const uint32_t b10 = (b << 2) | (b >> 6);
            raw[i] = r10 | (g10 << 10) | (b10 << 20) | (3u << 30);
        }
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    // glReadPixels отдаёт строки снизу вверх. Переворот сделан здесь, один раз:
    // у вызывающего он неизбежно окажется сделан дважды в одном месте и ни разу
    // в другом.
    const size_t row = static_cast<size_t>(width_);
    for (int y = 0; y < height_; ++y) {
        const uint32_t *src = raw.data() + static_cast<size_t>(height_ - 1 - y) * row;
        for (size_t x = 0; x < row; ++x) out[static_cast<size_t>(y) * row + x] = src[x];
    }

    return CheckGlError("ReadPacked10");
}

}  // namespace ddd
