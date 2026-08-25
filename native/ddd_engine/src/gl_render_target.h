/*
 * gl_render_target.h — offscreen-цель рендера, в том числе 10-битная.
 *
 * Зачем отдельно от EGL-поверхности. Проверить, что 10 бит доходят до текстуры,
 * через окно нельзя: поверхность окна на телефоне 8-битная, и любой градиент в
 * ней схлопнется в 256 уровней независимо от того, что было в текстуре. Значит
 * нужна цель рендера с точностью выше 8 бит на канал, из которой пиксели
 * читаются обратно, — а это FBO с `GL_RGB10_A2`, а не свойство EGL-конфига.
 *
 * Заодно это будущий шаг 6: тонмаппинг будет писать сюда же, а не в окно, чтобы
 * промежуточный результат не терял точность до вывода.
 */
#pragma once

#include <cstdint>

namespace ddd {

class GlRenderTarget {
public:
    /**
     * Создаёт FBO с текстурой-вложением. Требует текущего GL-контекста.
     *
     * @param ten_bit true — `GL_RGB10_A2`, false — `GL_RGBA8`. Восьмибитный
     *                вариант нужен не для вывода, а для сравнения: он
     *                показывает, что схлопывание градиента в 256 уровней даёт
     *                именно цель рендера, а не путь загрузки.
     * @return nullptr, если FBO не собрался (причина — в logcat).
     */
    static GlRenderTarget *Create(int width, int height, bool ten_bit);

    ~GlRenderTarget();

    GlRenderTarget(const GlRenderTarget &) = delete;
    GlRenderTarget &operator=(const GlRenderTarget &) = delete;

    /** Делает цель текущим framebuffer-ом. Viewport выставляет вызывающий. */
    void Bind() const;

    /** Возвращает рендер в framebuffer по умолчанию (окно или pbuffer). */
    static void Unbind();

    /**
     * Читает цель в упакованные слова `2_10_10_10_REV`: R в битах 0–9, G в
     * 10–19, B в 20–29, A в 30–31. Строки — **сверху вниз** (переворот
     * `glReadPixels` сделан здесь).
     *
     * Восьмибитная цель читается тем же методом с монотонным расширением
     * `c<<2 | c>>6`: монотонность важна, потому что тесты считают число
     * различных уровней, и любое немонотонное преобразование это число исказило
     * бы.
     *
     * @param out массив `width * height` слов.
     */
    bool ReadPacked10(uint32_t *out) const;

    int width() const { return width_; }
    int height() const { return height_; }
    bool ten_bit() const { return ten_bit_; }

private:
    GlRenderTarget() = default;

    unsigned fbo_ = 0;
    unsigned texture_ = 0;
    int width_ = 0;
    int height_ = 0;
    bool ten_bit_ = false;
};

}  // namespace ddd
