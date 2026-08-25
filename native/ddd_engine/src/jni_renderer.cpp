/*
 * jni_renderer.cpp — граница Kotlin ↔ native для шага 4 (вывод кадра).
 *
 * Все методы, кроме [NativeReference], обязаны вызываться из ОДНОГО потока —
 * того, где рендерер создан. Это не прихоть API, а свойство EGL: контекст
 * текущий ровно в одном потоке, и GL-вызов из чужого потока молча не делает
 * ничего. Чтобы ошибка не выглядела как «чёрный экран без причин», каждый вход
 * заново делает контекст текущим и жалуется в лог, если это не удалось.
 *
 * Кадр приходит из Kotlin массивами байт намеренно: так тест генерирует кадр и
 * отдаёт ОДНИ И ТЕ ЖЕ байты в GL и в эталон swscale, а ошибка генератора не может
 * вычесться сама из себя. Рабочий путь другой и живёт здесь же —
 * [UploadFrameToRenderer]: кадр из буфера `AMediaCodec` (шаг 5) идёт в текстуру
 * внутри native, без `byte[]` и без второго копирования.
 */
#include "jni_renderer.h"

#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <ctime>
#include <vector>

#include "ddd_log.h"
#include "dovi_rpu_parser.h"
#include "egl_context.h"
#include "gl_render_target.h"
#include "gl_util.h"
#include "jni_util.h"
#include "render_reference.h"
#include "tone_map.h"
#include "video_renderer.h"

#include <GLES3/gl3.h>

namespace ddd {

namespace {

constexpr const char *kNativeRendererClass = "top/rootu/dddplayer/engine/NativeRenderer";

struct RendererHandle {
    ANativeWindow *window = nullptr;
    EglContext *egl = nullptr;
    VideoRenderer *renderer = nullptr;
    /** Offscreen-цель, если вывод переключён с поверхности EGL; иначе nullptr. */
    GlRenderTarget *target = nullptr;

    ~RendererHandle() {
        // Порядок: сначала GL-объекты (пока контекст жив), потом контекст, потом
        // окно. Обратный порядок — это удаление текстур без контекста, то есть
        // тихая утечка видеопамяти на каждое пересоздание поверхности.
        delete renderer;
        delete target;
        delete egl;
        if (window != nullptr) ANativeWindow_release(window);
    }
};

RendererHandle *Handle(jlong handle) { return reinterpret_cast<RendererHandle *>(handle); }

/** Хэндл с уже сделанным текущим контекстом; nullptr, если что-то не так. */
RendererHandle *Current(jlong handle, const char *what) {
    RendererHandle *h = Handle(handle);
    if (h == nullptr || h->egl == nullptr) {
        DDD_LOGE("renderer: %s с нулевым хэндлом", what);
        return nullptr;
    }
    if (!h->egl->MakeCurrent()) {
        DDD_LOGE("renderer: %s — контекст не стал текущим", what);
        return nullptr;
    }
    return h;
}

/**
 * Делает активную цель рендера текущей и отдаёт её размер.
 *
 * Привязка сделана здесь, а не один раз при переключении цели: между `draw` и
 * `readPixels` вызывающий может сделать что угодно (в тестах — посчитать эталон,
 * в плеере — нарисовать субтитры), и полагаться на то, что framebuffer остался
 * привязанным, значит читать пиксели из чужого буфера при первом же изменении.
 */
void BindTarget(RendererHandle *h, int *width, int *height) {
    if (h->target != nullptr) {
        h->target->Bind();
        *width = h->target->width();
        *height = h->target->height();
        return;
    }
    GlRenderTarget::Unbind();
    h->egl->RefreshSize();
    *width = h->egl->width();
    *height = h->egl->height();
}

int64_t MonotonicNs() {
    timespec ts = {};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

/**
 * Собирает [FrameDesc] из Java-массивов. Массивы остаются закреплёнными до
 * вызова [Release].
 */
class BorrowedFrame {
public:
    BorrowedFrame(JNIEnv *env) : env_(env) {}

    ~BorrowedFrame() {
        for (int i = 0; i < 3; ++i) {
            if (elements_[i] != nullptr) {
                // JNI_ABORT: входные данные не менялись, копировать обратно нечего.
                env_->ReleaseByteArrayElements(arrays_[i], elements_[i], JNI_ABORT);
            }
        }
    }

    BorrowedFrame(const BorrowedFrame &) = delete;
    BorrowedFrame &operator=(const BorrowedFrame &) = delete;

    bool Borrow(int index, jbyteArray array, int stride) {
        if (array == nullptr) return true;  // допустимо для плоскости V у NV12
        elements_[index] = env_->GetByteArrayElements(array, nullptr);
        if (elements_[index] == nullptr) {
            ClearPendingException(env_, "GetByteArrayElements(plane)");
            return false;
        }
        arrays_[index] = array;
        desc_.plane[index] = reinterpret_cast<const uint8_t *>(elements_[index]);
        desc_.stride[index] = stride;
        return true;
    }

    FrameDesc &desc() { return desc_; }

private:
    JNIEnv *env_;
    jbyteArray arrays_[3] = {nullptr, nullptr, nullptr};
    jbyte *elements_[3] = {nullptr, nullptr, nullptr};
    FrameDesc desc_;
};

// ───────────────────────────── нативные методы ─────────────────────────────

/**
 * @param surface `Surface` для оконного вывода; null — offscreen pbuffer
 *                [width]×[height] (так проверяется цвет: из pbuffer пиксели
 *                читаются, из SurfaceFlinger — нет).
 * @return хэндл или 0.
 */
jlong NativeCreate(JNIEnv *env, jobject, jobject surface, jint width, jint height) {
    RendererHandle *h = new RendererHandle();

    if (surface != nullptr) {
        h->window = ANativeWindow_fromSurface(env, surface);
        if (h->window == nullptr) {
            DDD_LOGE("renderer: ANativeWindow_fromSurface вернул null");
            delete h;
            return 0;
        }
    }

    h->egl = EglContext::Create(h->window, width, height);
    if (h->egl == nullptr) {
        delete h;
        return 0;
    }

    h->renderer = VideoRenderer::Create();
    if (h->renderer == nullptr) {
        delete h;
        return 0;
    }
    return reinterpret_cast<jlong>(h);
}

void NativeRelease(JNIEnv *, jobject, jlong handle) {
    RendererHandle *h = Handle(handle);
    if (h == nullptr) return;
    // Контекст надо сделать текущим, иначе деструктор рендерера удалит текстуры
    // «в пустоту»: без текущего контекста glDelete* не делает ничего.
    if (h->egl != nullptr) h->egl->MakeCurrent();
    delete h;
}

jboolean NativeSurfaceSize(JNIEnv *env, jobject, jlong handle, jintArray out) {
    RendererHandle *h = Handle(handle);
    if (h == nullptr || h->egl == nullptr || out == nullptr) return JNI_FALSE;
    if (env->GetArrayLength(out) < 2) return JNI_FALSE;
    h->egl->RefreshSize();
    jint values[2] = {h->egl->width(), h->egl->height()};
    env->SetIntArrayRegion(out, 0, 2, values);
    return JNI_TRUE;
}

jstring NativeGlInfo(JNIEnv *env, jobject, jlong handle) {
    RendererHandle *h = Current(handle, "glInfo");
    if (h == nullptr) return nullptr;
    const GLubyte *renderer = glGetString(GL_RENDERER);
    char buf[256];
    snprintf(buf, sizeof buf, "%s / %s", h->egl->GlVersionString(),
             renderer != nullptr ? reinterpret_cast<const char *>(renderer) : "?");
    return env->NewStringUTF(buf);
}

void NativeSetChromaFilter(JNIEnv *, jobject, jlong handle, jboolean linear) {
    RendererHandle *h = Current(handle, "setChromaFilter");
    if (h == nullptr) return;
    h->renderer->SetChromaFilter(linear == JNI_TRUE);
}

void NativeSetPixelAspectRatio(JNIEnv *, jobject, jlong handle, jfloat par) {
    RendererHandle *h = Handle(handle);
    if (h == nullptr || h->renderer == nullptr) return;
    h->renderer->SetPixelAspectRatio(par);
}

void NativeSetForceBytePair(JNIEnv *, jobject, jlong handle, jboolean force) {
    RendererHandle *h = Current(handle, "setForceBytePair");
    if (h == nullptr) return;
    h->renderer->SetForceBytePair(force == JNI_TRUE);
}

/**
 * Параметры HDR-тонмаппинга (шаг 6).
 *
 * Плоским списком, а не объектом: поля читаются в native на каждый файл, и
 * `GetFieldID` по семи полям на границе JNI — это семь поисков по классу там, где
 * достаточно передать семь чисел.
 */
void NativeSetHdrParams(JNIEnv *, jobject, jlong handle, jint transfer, jfloat display_peak,
                        jfloat mastering_peak, jfloat max_cll, jfloat max_fall,
                        jfloat brightness, jfloat gamma, jboolean convert_gamut,
                        jboolean match_four_xvr) {
    RendererHandle *h = Handle(handle);
    if (h == nullptr || h->renderer == nullptr) return;

    HdrParams p;
    p.transfer = static_cast<ColorTransfer>(transfer);
    p.display_peak_nits = display_peak;
    p.mastering_peak_nits = mastering_peak;
    p.max_cll_nits = max_cll;
    p.max_fall_nits = max_fall;
    p.brightness = brightness;
    p.display_gamma = gamma;
    p.convert_gamut = convert_gamut == JNI_TRUE;
    p.match_four_xvr = match_four_xvr == JNI_TRUE;
    h->renderer->SetHdrParams(p);
}

/**
 * Эталон тон-кривой на CPU для тех же параметров.
 *
 * Не «вторая реализация той же формулы ради сравнения» — такое совпадение не
 * доказывает ничего. Смысл в разделении причин отказа: если CPU и GPU
 * расходятся, виноват шейдер, драйвер или точность; если они совпадают, но не
 * сходятся с числами, посчитанными по ITU-R вручную (это делает Kotlin), —
 * виновата формула. Без этого разделения «картинка не та» неотличимо от
 * «математика не та».
 *
 * @param rgb_in  входные PQ/HLG-коды, кратно 3.
 * @param rgb_out результат в тех же единицах, что и у шейдера: [0,1] с гаммой.
 */
jboolean NativeToneMapReference(JNIEnv *env, jobject, jint transfer, jfloat display_peak,
                                jfloat mastering_peak, jfloat max_cll, jfloat max_fall,
                                jfloat brightness, jfloat gamma, jboolean convert_gamut,
                                jboolean match_four_xvr,
                                jfloatArray rgb_in, jfloatArray rgb_out) {
    if (rgb_in == nullptr || rgb_out == nullptr) return JNI_FALSE;
    const jsize n = env->GetArrayLength(rgb_in);
    if (n <= 0 || n % 3 != 0 || env->GetArrayLength(rgb_out) < n) return JNI_FALSE;

    HdrParams p;
    p.transfer = static_cast<ColorTransfer>(transfer);
    p.display_peak_nits = display_peak;
    p.mastering_peak_nits = mastering_peak;
    p.max_cll_nits = max_cll;
    p.max_fall_nits = max_fall;
    p.brightness = brightness;
    p.display_gamma = gamma;
    p.convert_gamut = convert_gamut == JNI_TRUE;
    p.match_four_xvr = match_four_xvr == JNI_TRUE;

    std::vector<float> in(static_cast<size_t>(n));
    env->GetFloatArrayRegion(rgb_in, 0, n, in.data());
    std::vector<float> out(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i += 3) {
        const double src[3] = {in[i], in[i + 1], in[i + 2]};
        double dst[3];
        ToneMapPixel(p, src, dst);
        out[i] = static_cast<float>(dst[0]);
        out[i + 1] = static_cast<float>(dst[1]);
        out[i + 2] = static_cast<float>(dst[2]);
    }
    env->SetFloatArrayRegion(rgb_out, 0, n, out.data());
    return JNI_TRUE;
}

/**
 * PQ-код яркости в кд/м² (и обратно при [inverse]).
 *
 * Отдельный вход, потому что тест обязан задавать яркость в НИТАХ: «пиковый
 * белый 1000 нит» — это утверждение о физике, а «код 0.7518» — уже результат
 * применения той самой функции, которую тест проверяет.
 */
jfloat NativePqTransfer(JNIEnv *, jobject, jfloat value, jboolean inverse) {
    if (inverse == JNI_TRUE) return static_cast<jfloat>(PqOetf(value / 10000.0));
    return static_cast<jfloat>(PqEotf(value) * 10000.0);
}

jint NativeUploadPath(JNIEnv *, jobject, jlong handle) {
    RendererHandle *h = Handle(handle);
    if (h == nullptr || h->renderer == nullptr) return -1;
    return static_cast<jint>(h->renderer->upload_path());
}

jboolean NativeHasNorm16(JNIEnv *, jobject, jlong handle) {
    RendererHandle *h = Handle(handle);
    if (h == nullptr || h->renderer == nullptr) return JNI_FALSE;
    return h->renderer->has_norm16() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Переключает вывод в offscreen-цель заданной точности.
 *
 * @param width <= 0 — вернуться в поверхность EGL и освободить цель.
 * @param ten_bit `GL_RGB10_A2` вместо `GL_RGBA8`.
 */
jboolean NativeSetRenderTarget(JNIEnv *, jobject, jlong handle, jint width, jint height,
                               jboolean ten_bit) {
    RendererHandle *h = Current(handle, "setRenderTarget");
    if (h == nullptr) return JNI_FALSE;

    delete h->target;
    h->target = nullptr;
    if (width <= 0 || height <= 0) {
        GlRenderTarget::Unbind();
        return JNI_TRUE;
    }

    h->target = GlRenderTarget::Create(width, height, ten_bit == JNI_TRUE);
    return h->target != nullptr ? JNI_TRUE : JNI_FALSE;
}

jboolean NativeUploadFrame(JNIEnv *env, jobject, jlong handle, jbyteArray p0, jint s0, jbyteArray p1,
                           jint s1, jbyteArray p2, jint s2, jint width, jint height, jint format,
                           jint standard, jboolean full_range) {
    RendererHandle *h = Current(handle, "uploadFrame");
    if (h == nullptr) return JNI_FALSE;

    BorrowedFrame borrowed(env);
    if (!borrowed.Borrow(0, p0, s0) || !borrowed.Borrow(1, p1, s1) || !borrowed.Borrow(2, p2, s2)) {
        return JNI_FALSE;
    }
    FrameDesc &d = borrowed.desc();
    d.width = width;
    d.height = height;
    d.format = static_cast<FramePixelFormat>(format);
    d.standard = static_cast<ColorStandard>(standard);
    d.full_range = full_range == JNI_TRUE;

    return h->renderer->UploadFrame(d) ? JNI_TRUE : JNI_FALSE;
}

jboolean NativeDraw(JNIEnv *, jobject, jlong handle, jint rotation, jint mode) {
    RendererHandle *h = Current(handle, "draw");
    if (h == nullptr) return JNI_FALSE;
    int w = 0;
    int h_px = 0;
    BindTarget(h, &w, &h_px);
    return h->renderer->Draw(w, h_px, rotation, static_cast<ScaleMode>(mode)) ? JNI_TRUE : JNI_FALSE;
}

jboolean NativeSwap(JNIEnv *, jobject, jlong handle) {
    RendererHandle *h = Current(handle, "swap");
    if (h == nullptr) return JNI_FALSE;
    return h->egl->SwapBuffers() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Читает поверхность в RGBA, сверху вниз.
 *
 * `glReadPixels` отдаёт строки СНИЗУ ВВЕРХ (начало координат GL — левый нижний
 * угол), а кадр и все Java-битмапы — сверху вниз. Переворот делается здесь, а не
 * в вызывающем: иначе он неизбежно окажется сделан дважды в одном месте и ни
 * разу в другом.
 */
jboolean NativeReadPixels(JNIEnv *env, jobject, jlong handle, jbyteArray out) {
    RendererHandle *h = Current(handle, "readPixels");
    if (h == nullptr || out == nullptr) return JNI_FALSE;

    int w = 0;
    int h_px = 0;
    BindTarget(h, &w, &h_px);
    const jsize need = static_cast<jsize>(w) * h_px * 4;
    if (env->GetArrayLength(out) < need) {
        DDD_LOGE("renderer: буферу readPixels нужно %d байт", static_cast<int>(need));
        return JNI_FALSE;
    }

    std::vector<uint8_t> flipped(static_cast<size_t>(need));
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, w, h_px, GL_RGBA, GL_UNSIGNED_BYTE, flipped.data());
    if (!CheckGlError("glReadPixels")) return JNI_FALSE;

    const size_t row = static_cast<size_t>(w) * 4;
    std::vector<uint8_t> top_down(static_cast<size_t>(need));
    for (int y = 0; y < h_px; ++y) {
        memcpy(top_down.data() + static_cast<size_t>(y) * row,
               flipped.data() + static_cast<size_t>(h_px - 1 - y) * row, row);
    }
    env->SetByteArrayRegion(out, 0, need, reinterpret_cast<const jbyte *>(top_down.data()));
    return JNI_TRUE;
}

/**
 * Читает цель рендера в упакованные слова `2_10_10_10_REV`, сверху вниз.
 *
 * Только для offscreen-цели: у поверхности EGL точность 8 бит, и чтение из неё
 * «десятибитным» методом создавало бы ровно ту иллюзию, которую шаг 5 обязан
 * опровергнуть. Поэтому без цели — честный отказ.
 */
jboolean NativeReadPacked10(JNIEnv *env, jobject, jlong handle, jintArray out) {
    RendererHandle *h = Current(handle, "readPacked10");
    if (h == nullptr || out == nullptr) return JNI_FALSE;
    if (h->target == nullptr) {
        DDD_LOGE("renderer: readPacked10 без offscreen-цели");
        return JNI_FALSE;
    }

    const jsize need = static_cast<jsize>(h->target->width()) * h->target->height();
    if (env->GetArrayLength(out) < need) {
        DDD_LOGE("renderer: буферу readPacked10 нужно %d слов", static_cast<int>(need));
        return JNI_FALSE;
    }

    std::vector<uint32_t> packed(static_cast<size_t>(need));
    if (!h->target->ReadPacked10(packed.data())) return JNI_FALSE;
    // reinterpret_cast, а не поэлементное копирование: jint — это int32_t, и
    // старший бит альфы в 2_10_10_10_REV сделал бы значение отрицательным при
    // «безопасном» приведении со знаком. Kotlin разбирает слово масками.
    env->SetIntArrayRegion(out, 0, need, reinterpret_cast<const jint *>(packed.data()));
    return JNI_TRUE;
}

/** Эталон swscale для тех же байт. GL-контекст не нужен — метод чистый. */
jboolean NativeReference(JNIEnv *env, jobject, jbyteArray p0, jint s0, jbyteArray p1, jint s1,
                         jbyteArray p2, jint s2, jint width, jint height, jint format, jint standard,
                         jboolean full_range, jbyteArray out) {
    if (out == nullptr) return JNI_FALSE;
    const jsize need = static_cast<jsize>(width) * height * 4;
    if (env->GetArrayLength(out) < need) return JNI_FALSE;

    BorrowedFrame borrowed(env);
    if (!borrowed.Borrow(0, p0, s0) || !borrowed.Borrow(1, p1, s1) || !borrowed.Borrow(2, p2, s2)) {
        return JNI_FALSE;
    }
    FrameDesc &d = borrowed.desc();
    d.width = width;
    d.height = height;
    d.format = static_cast<FramePixelFormat>(format);
    d.standard = static_cast<ColorStandard>(standard);
    d.full_range = full_range == JNI_TRUE;

    std::vector<uint8_t> rgba(static_cast<size_t>(need));
    if (!SwscaleReference(d, rgba.data())) return JNI_FALSE;
    env->SetByteArrayRegion(out, 0, need, reinterpret_cast<const jbyte *>(rgba.data()));
    return JNI_TRUE;
}

/**
 * Эталон swscale для 10/12/16-битных кадров: RGBA64LE, 16 бит на канал.
 *
 * Отдельно от [NativeReference] не ради типа буфера, а потому что 8-битный
 * эталон на 10-битном кадре сам стал бы горлышком точности: сравнение показало
 * бы «совпадает» при любой потере младших разрядов в шейдере.
 *
 * @param out `width * height * 4` значений short (R,G,B,A), сверху вниз.
 */
jboolean NativeReference16(JNIEnv *env, jobject, jbyteArray p0, jint s0, jbyteArray p1, jint s1,
                           jbyteArray p2, jint s2, jint width, jint height, jint format,
                           jint standard, jboolean full_range, jshortArray out) {
    if (out == nullptr) return JNI_FALSE;
    const jsize need = static_cast<jsize>(width) * height * 4;
    if (env->GetArrayLength(out) < need) return JNI_FALSE;

    BorrowedFrame borrowed(env);
    if (!borrowed.Borrow(0, p0, s0) || !borrowed.Borrow(1, p1, s1) || !borrowed.Borrow(2, p2, s2)) {
        return JNI_FALSE;
    }
    FrameDesc &d = borrowed.desc();
    d.width = width;
    d.height = height;
    d.format = static_cast<FramePixelFormat>(format);
    d.standard = static_cast<ColorStandard>(standard);
    d.full_range = full_range == JNI_TRUE;

    std::vector<uint16_t> rgba64(static_cast<size_t>(need));
    if (!SwscaleReference16(d, rgba64.data())) return JNI_FALSE;
    env->SetShortArrayRegion(out, 0, need, reinterpret_cast<const jshort *>(rgba64.data()));
    return JNI_TRUE;
}

/**
 * Замер скорости вывода: заливка текстур + рисование + `glFinish`.
 *
 * Кадр генерируется в native и не ходит через JVM — иначе замер мерил бы
 * копирование массивов, а не GL. `glFinish` после каждого кадра обязателен:
 * без него драйвер накапливает команды, и «0.1 мс на кадр 4K» окажется временем
 * записи в очередь, а не отрисовки.
 *
 * @return суммарное время в наносекундах; -1 при ошибке.
 */
jlong NativeBenchmark(JNIEnv *, jobject, jlong handle, jint width, jint height, jint frames,
                      jint format) {
    RendererHandle *h = Current(handle, "benchmark");
    if (h == nullptr || frames <= 0) return -1;

    SyntheticFrame frame = SyntheticFrame::MakeNoise(width, height,
                                                     static_cast<FramePixelFormat>(format),
                                                     ColorStandard::kBt709, false, 0x5eed);

    int view_w = 0;
    int view_h = 0;
    BindTarget(h, &view_w, &view_h);

    // Прогрев: первая заливка включает создание текстур и компиляцию шейдера
    // драйвером, и попадать этим в среднее нельзя.
    if (!h->renderer->UploadFrame(frame.desc())) return -1;
    h->renderer->Draw(view_w, view_h, 0, ScaleMode::kFit);
    glFinish();

    const int64_t start = MonotonicNs();
    for (int i = 0; i < frames; ++i) {
        frame.Touch(static_cast<uint32_t>(i));
        if (!h->renderer->UploadFrame(frame.desc())) return -1;
        if (!h->renderer->Draw(view_w, view_h, 0, ScaleMode::kFit)) return -1;
        glFinish();
    }
    return MonotonicNs() - start;
}

const JNINativeMethod kMethods[] = {
    {"nativeCreate", "(Landroid/view/Surface;II)J", reinterpret_cast<void *>(NativeCreate)},
    {"nativeRelease", "(J)V", reinterpret_cast<void *>(NativeRelease)},
    {"nativeSurfaceSize", "(J[I)Z", reinterpret_cast<void *>(NativeSurfaceSize)},
    {"nativeGlInfo", "(J)Ljava/lang/String;", reinterpret_cast<void *>(NativeGlInfo)},
    {"nativeSetChromaFilter", "(JZ)V", reinterpret_cast<void *>(NativeSetChromaFilter)},
    {"nativeSetPixelAspectRatio", "(JF)V", reinterpret_cast<void *>(NativeSetPixelAspectRatio)},
    {"nativeSetForceBytePair", "(JZ)V", reinterpret_cast<void *>(NativeSetForceBytePair)},
    {"nativeUploadPath", "(J)I", reinterpret_cast<void *>(NativeUploadPath)},
    {"nativeHasNorm16", "(J)Z", reinterpret_cast<void *>(NativeHasNorm16)},
    {"nativeSetRenderTarget", "(JIIZ)Z", reinterpret_cast<void *>(NativeSetRenderTarget)},
    {"nativeUploadFrame", "(J[BI[BI[BIIIIIZ)Z", reinterpret_cast<void *>(NativeUploadFrame)},
    {"nativeDraw", "(JII)Z", reinterpret_cast<void *>(NativeDraw)},
    {"nativeSwap", "(J)Z", reinterpret_cast<void *>(NativeSwap)},
    {"nativeReadPixels", "(J[B)Z", reinterpret_cast<void *>(NativeReadPixels)},
    {"nativeReadPacked10", "(J[I)Z", reinterpret_cast<void *>(NativeReadPacked10)},
    {"nativeReference", "([BI[BI[BIIIIIZ[B)Z", reinterpret_cast<void *>(NativeReference)},
    {"nativeReference16", "([BI[BI[BIIIIIZ[S)Z", reinterpret_cast<void *>(NativeReference16)},
    {"nativeBenchmark", "(JIIII)J", reinterpret_cast<void *>(NativeBenchmark)},
    {"nativeSetHdrParams", "(JIFFFFFFZZ)V", reinterpret_cast<void *>(NativeSetHdrParams)},
    {"nativeToneMapReference", "(IFFFFFFZZ[F[F)Z",
     reinterpret_cast<void *>(NativeToneMapReference)},
    {"nativePqTransfer", "(FZ)F", reinterpret_cast<void *>(NativePqTransfer)},
};

}  // namespace

bool UploadFrameToRenderer(jlong handle, const FrameDesc &frame,
                           const DoviFrameMapping *dovi_mapping) {
    RendererHandle *h = Current(handle, "uploadFrame(native)");
    if (h == nullptr) return false;
    h->renderer->SetDolbyMapping(dovi_mapping);
    return h->renderer->UploadFrame(frame);
}

bool RegisterRendererNatives(JNIEnv *env) {
    jclass cls = env->FindClass(kNativeRendererClass);
    if (cls == nullptr) {
        DDD_LOGE("jni: класс %s не найден", kNativeRendererClass);
        return false;
    }
    const jint r = env->RegisterNatives(cls, kMethods,
                                        static_cast<jint>(sizeof kMethods / sizeof kMethods[0]));
    env->DeleteLocalRef(cls);
    if (r != JNI_OK) {
        DDD_LOGE("jni: RegisterNatives(NativeRenderer) вернул %d", static_cast<int>(r));
        return false;
    }
    return true;
}

}  // namespace ddd
