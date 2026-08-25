/*
 * egl_context.h — EGL-контекст движка: окно из `Surface` либо offscreen pbuffer.
 *
 * Зачем pbuffer, если плееру нужно окно. Проверить цвет на настоящем
 * `SurfaceView` нечем: в инструментальном тесте нет ни дисплея, ни компоновщика,
 * а даже с ними прочитать обратно то, что ушло в SurfaceFlinger, нельзя. Поэтому
 * тот же самый конвейер (тот же контекст, та же программа, те же текстуры)
 * умеет рисовать в pbuffer, откуда `glReadPixels` отдаёт пиксели, — и цвет
 * сверяется с эталоном swscale побайтово. Оконный путь при этом не остаётся
 * непроверенным: тест отдаёт `Surface` от `ImageReader`, то есть настоящий
 * `ANativeWindow`, и читает кадр уже с его стороны.
 *
 * GLES **3.0**, а не 2.0, выбран сразу: 10-битный путь шага 5 требует
 * `GL_R16`/`GL_RG16`/`GL_RGB10_A2`, которых в ES2 нет вовсе, а переписывать
 * загрузку текстур дважды незачем. ES3 есть с Android 4.3, наш minSdk 23.
 */
#pragma once

#include <EGL/egl.h>

struct ANativeWindow;

namespace ddd {

class EglContext {
public:
    /**
     * Создаёт контекст и поверхность.
     *
     * @param window окно из `ANativeWindow_fromSurface`; nullptr — offscreen
     *               pbuffer размером [width]×[height]. Владение окном НЕ
     *               передаётся: его освобождает вызывающий после удаления
     *               контекста.
     * @param width/height размер pbuffer; для окна игнорируются (берутся из окна).
     * @return контекст, уже сделанный текущим в вызывающем потоке, либо nullptr.
     */
    static EglContext *Create(ANativeWindow *window, int width, int height);

    ~EglContext();

    EglContext(const EglContext &) = delete;
    EglContext &operator=(const EglContext &) = delete;

    /**
     * Делает контекст текущим в ВЫЗЫВАЮЩЕМ потоке.
     *
     * EGL-контекст принадлежит одному потоку за раз, и это главный источник
     * «чёрного экрана без единой ошибки»: GL-вызов из потока, где контекст не
     * текущий, молча ничего не делает. Поэтому рендер-поток обязан вызвать это
     * у себя, а не полагаться на то, что контекст создавался в нём же.
     */
    bool MakeCurrent();

    /** Отвязывает контекст от текущего потока. */
    void ReleaseCurrent();

    /** Для окна — показать кадр; для pbuffer — no-op (рисунок остаётся в буфере). */
    bool SwapBuffers();

    /** Перечитывает размер поверхности: окно меняет его при повороте экрана. */
    void RefreshSize();

    int width() const { return width_; }
    int height() const { return height_; }
    bool is_window() const { return is_window_; }

    /** Строка вида `OpenGL ES 3.0 (Adreno …)` для логов и диагностики. */
    const char *GlVersionString() const;

private:
    EglContext() = default;

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;
    EGLConfig config_ = nullptr;
    int width_ = 0;
    int height_ = 0;
    bool is_window_ = false;
};

/** Расшифровка `eglGetError` в текст: числа 0x300x наизусть не помнит никто. */
const char *EglErrorString(EGLint error);

}  // namespace ddd
