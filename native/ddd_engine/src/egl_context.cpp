/*
 * egl_context.cpp — реализация EGL-контекста (см. egl_context.h).
 */
#include "egl_context.h"

#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/native_window.h>

#include "ddd_log.h"

namespace ddd {

const char *EglErrorString(EGLint error) {
    switch (error) {
        case EGL_SUCCESS: return "EGL_SUCCESS";
        case EGL_NOT_INITIALIZED: return "EGL_NOT_INITIALIZED";
        case EGL_BAD_ACCESS: return "EGL_BAD_ACCESS";
        case EGL_BAD_ALLOC: return "EGL_BAD_ALLOC";
        case EGL_BAD_ATTRIBUTE: return "EGL_BAD_ATTRIBUTE";
        case EGL_BAD_CONTEXT: return "EGL_BAD_CONTEXT";
        case EGL_BAD_CONFIG: return "EGL_BAD_CONFIG";
        case EGL_BAD_CURRENT_SURFACE: return "EGL_BAD_CURRENT_SURFACE";
        case EGL_BAD_DISPLAY: return "EGL_BAD_DISPLAY";
        case EGL_BAD_SURFACE: return "EGL_BAD_SURFACE";
        case EGL_BAD_MATCH: return "EGL_BAD_MATCH";
        case EGL_BAD_PARAMETER: return "EGL_BAD_PARAMETER";
        case EGL_BAD_NATIVE_PIXMAP: return "EGL_BAD_NATIVE_PIXMAP";
        case EGL_BAD_NATIVE_WINDOW: return "EGL_BAD_NATIVE_WINDOW";
        case EGL_CONTEXT_LOST: return "EGL_CONTEXT_LOST";
        default: return "EGL_UNKNOWN";
    }
}

namespace {

/** Логирует ошибку EGL и сбрасывает её: eglGetError залипает до следующего чтения. */
bool EglFail(const char *what) {
    const EGLint err = eglGetError();
    DDD_LOGE("egl: %s — %s (0x%04x)", what, EglErrorString(err), static_cast<unsigned>(err));
    return false;
}

}  // namespace

EglContext *EglContext::Create(ANativeWindow *window, int width, int height) {
    EglContext *self = new EglContext();
    self->is_window_ = window != nullptr;

    self->display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (self->display_ == EGL_NO_DISPLAY) {
        EglFail("eglGetDisplay");
        delete self;
        return nullptr;
    }

    EGLint major = 0, minor = 0;
    if (eglInitialize(self->display_, &major, &minor) != EGL_TRUE) {
        EglFail("eglInitialize");
        delete self;
        return nullptr;
    }

    // EGL_SURFACE_TYPE обязан совпадать с тем, что мы потом создаём: конфиг с
    // одним лишь EGL_WINDOW_BIT не годится для pbuffer, и наоборот — ошибка
    // всплывёт только на eglCreate*Surface как BAD_MATCH.
    //
    // 8 бит на канал здесь сознательно: шаг 4 — SDR-путь. Для HDR на шаге 6
    // понадобится EGL_GL_COLORSPACE_BT2020_PQ_EXT и 10-битный конфиг
    // (EGL_RED_SIZE 10), и это будет отдельная ветка выбора конфига, а не правка
    // этой: SDR-путь должен продолжать работать на устройствах без HDR-вывода.
    const EGLint config_attribs[] = {
        EGL_SURFACE_TYPE, self->is_window_ ? EGL_WINDOW_BIT : EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 0,
        EGL_STENCIL_SIZE, 0,
        EGL_NONE
    };

    EGLint num_configs = 0;
    if (eglChooseConfig(self->display_, config_attribs, &self->config_, 1, &num_configs) != EGL_TRUE ||
        num_configs < 1) {
        EglFail("eglChooseConfig(ES3)");
        eglTerminate(self->display_);
        self->display_ = EGL_NO_DISPLAY;
        delete self;
        return nullptr;
    }

    const EGLint context_attribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    self->context_ = eglCreateContext(self->display_, self->config_, EGL_NO_CONTEXT, context_attribs);
    if (self->context_ == EGL_NO_CONTEXT) {
        EglFail("eglCreateContext");
        delete self;
        return nullptr;
    }

    if (self->is_window_) {
        self->surface_ = eglCreateWindowSurface(self->display_, self->config_, window, nullptr);
        if (self->surface_ == EGL_NO_SURFACE) {
            EglFail("eglCreateWindowSurface");
            delete self;
            return nullptr;
        }
    } else {
        if (width <= 0 || height <= 0) {
            DDD_LOGE("egl: pbuffer требует размера, получено %dx%d", width, height);
            delete self;
            return nullptr;
        }
        const EGLint pbuffer_attribs[] = {EGL_WIDTH, width, EGL_HEIGHT, height, EGL_NONE};
        self->surface_ = eglCreatePbufferSurface(self->display_, self->config_, pbuffer_attribs);
        if (self->surface_ == EGL_NO_SURFACE) {
            EglFail("eglCreatePbufferSurface");
            delete self;
            return nullptr;
        }
    }

    if (!self->MakeCurrent()) {
        delete self;
        return nullptr;
    }
    self->RefreshSize();

    DDD_LOGI("egl: %s %dx%d, EGL %d.%d, %s", self->is_window_ ? "окно" : "pbuffer", self->width_,
             self->height_, major, minor, self->GlVersionString());
    return self;
}

EglContext::~EglContext() {
    if (display_ == EGL_NO_DISPLAY) return;

    // Порядок важен: сначала отвязать, потом удалять. Удаление текущей
    // поверхности откладывается драйвером до отвязки, и на части устройств
    // это утечка до конца процесса.
    eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (surface_ != EGL_NO_SURFACE) eglDestroySurface(display_, surface_);
    if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
    eglTerminate(display_);

    surface_ = EGL_NO_SURFACE;
    context_ = EGL_NO_CONTEXT;
    display_ = EGL_NO_DISPLAY;
}

bool EglContext::MakeCurrent() {
    if (eglMakeCurrent(display_, surface_, surface_, context_) != EGL_TRUE) {
        return EglFail("eglMakeCurrent");
    }
    return true;
}

void EglContext::ReleaseCurrent() {
    eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
}

bool EglContext::SwapBuffers() {
    if (!is_window_) return true;
    if (eglSwapBuffers(display_, surface_) != EGL_TRUE) {
        // EGL_BAD_SURFACE здесь — обычное дело: окно уже уничтожено (SurfaceView
        // сняли), а рендер-поток ещё не узнал. Это не повод падать.
        const EGLint err = eglGetError();
        DDD_LOGW("egl: eglSwapBuffers — %s", EglErrorString(err));
        return false;
    }
    return true;
}

void EglContext::RefreshSize() {
    EGLint w = 0, h = 0;
    eglQuerySurface(display_, surface_, EGL_WIDTH, &w);
    eglQuerySurface(display_, surface_, EGL_HEIGHT, &h);
    if (w > 0 && h > 0) {
        width_ = w;
        height_ = h;
    }
}

const char *EglContext::GlVersionString() const {
    const GLubyte *v = glGetString(GL_VERSION);
    return v != nullptr ? reinterpret_cast<const char *>(v) : "(GL_VERSION недоступен)";
}

}  // namespace ddd
