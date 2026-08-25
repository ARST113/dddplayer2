/*
 * jni_decoder.cpp — граница Kotlin ↔ native для декодера видео (шаг 5).
 *
 * Хэндл держит DecodeSession* + текущий DecodedFrame. Открытым держится ровно
 * один кадр: AMediaCodec сам ограничивает число незакрытых выходных буферов
 * (обычно ~5), и удерживать их дольше одной заливки в текстуру незачем.
 *
 * Рабочий путь: nextFrame → uploadToRenderer → releaseFrame, всё в одном потоке.
 * Тестовый путь тот же, плюс copyPlane — чтобы те же байты можно было проверить
 * независимо от GL.
 *
 * Регистрация через RegisterNatives, как и у рендерера: расхождение подписи
 * становится ошибкой при System.loadLibrary, а не UnsatisfiedLinkError через
 * полчаса воспроизведения.
 */
#include "jni_decoder.h"

#include <jni.h>
#include <android/native_window_jni.h>

#include <cstdint>
#include <string>

#include "ddd_log.h"
#include "decode_session.h"
#include "demux_session.h"
#include "jni_renderer.h"
#include "jni_util.h"

namespace ddd {

namespace {

constexpr const char *kDecoderClass = "top/rootu/dddplayer/engine/NativeVideoDecoder";

/** Результаты nextFrame: порядок совпадает с NativeVideoDecoder.Step в Kotlin. */
enum StepOrdinal : jint {
    kStepFrame = 0,
    kStepAgain = 1,
    kStepEos = 2,
    kStepError = 3,
};

struct DecoderHandle {
    DecodeSession *session = nullptr;
    ANativeWindow *window = nullptr;
    /** Текущий кадр: MediaCodec index либо AImage owner. */
    DecodedFrame frame;

    DecoderHandle() { frame.index = -1; }

    ~DecoderHandle() {
        // Порядок важен: буфер принадлежит декодеру, и отдать его надо ДО того,
        // как декодер будет удалён, иначе это возврат буфера мёртвому объекту.
        if ((frame.index >= 0 || frame.image_owner != nullptr) && session != nullptr) {
            session->ReleaseFrame(frame);
        }
        delete session;
        if (window != nullptr) ANativeWindow_release(window);
    }
};

DecoderHandle *Handle(jlong handle) { return reinterpret_cast<DecoderHandle *>(handle); }

bool HasFrame(const DecoderHandle *h) {
    return h != nullptr && (h->frame.index >= 0 || h->frame.image_owner != nullptr);
}

void ClearFrame(DecoderHandle *h) {
    if (h != nullptr) h->frame = DecodedFrame();
}

/** Хэндл с живой сессией; nullptr и жалоба в лог, если что-то не так. */
DecoderHandle *Live(jlong handle, const char *what) {
    DecoderHandle *h = Handle(handle);
    if (h == nullptr || h->session == nullptr) {
        DDD_LOGE("decoder: %s с нулевым хэндлом", what);
        return nullptr;
    }
    return h;
}

/**
 * Геометрия плоскости текущего кадра: значащих байт в строке и строк.
 *
 * Значащих, а НЕ `stride * rows`: указатель плоскости уже сдвинут на кроп
 * (`FillPlanes` в hw_decoder.cpp), и копирование полного stride из последней
 * строки читает за конец буфера на `crop_left * bps` байт. Такой тест падал бы
 * не на десяти битах, а SIGSEGV внутри JNI — и выглядел бы как «баг платформы».
 */
void PlaneGeometry(const DecoderOutput &o, int index, int *row_bytes, int *rows) {
    const FormatInfo info = DescribeFormat(o.format);
    const int bps = info.sixteen_bit() ? 2 : 1;
    if (index == 0) {
        *row_bytes = o.width * bps;
        *rows = o.height;
        return;
    }
    // Округление вверх, а не вниз: у кадра с нечётной шириной (бывает после
    // кропа) последняя пара цветности всё равно занимает целый отсчёт.
    const int samples = (o.width + info.sub_x - 1) / info.sub_x;
    *row_bytes = info.semiplanar ? samples * 2 * bps : samples * bps;
    *rows = (o.height + info.sub_y - 1) / info.sub_y;
}

// ───────────────────────────── жизненный цикл ─────────────────────────────

/**
 * Создаёт декодер для видеопотока уже открытого демукса.
 *
 * @param demuxHandle   хэндл `NativeDemuxer`.
 * @param streamIndex   индекс видеопотока; -1 — лучший по пробингу.
 * @param preferTenBit  запросить у декодера 10 бит (`COLOR_FormatYUVP010`).
 * @param allowEscalate разрешить замену на c2.android.* при 8-битном ответе.
 * @param sendHdr       передать декодеру `hdr-static-info` из контейнера.
 * @param codecName     конкретный компонент вместо лестницы; null — лестница.
 * @param errorOut      `String[1]`; при отказе в [0] ложится причина.
 * @return хэндл или 0.
 */
jlong NativeCreate(JNIEnv *env, jobject, jlong demuxHandle, jint streamIndex,
                   jboolean preferTenBit, jboolean allowEscalate, jboolean sendHdr,
                   jboolean forceSoftware,
                   jstring codecName, jobject surface, jobjectArray errorOut) {
    auto fail = [&](const char *why) -> jlong {
        if (errorOut != nullptr && env->GetArrayLength(errorOut) > 0) {
            jstring s = env->NewStringUTF(why);
            env->SetObjectArrayElement(errorOut, 0, s);
            if (s != nullptr) env->DeleteLocalRef(s);
        }
        return 0;
    };

    DemuxSession *demux = reinterpret_cast<DemuxSession *>(demuxHandle);
    if (demux == nullptr) return fail("нулевой хэндл демукса");

    // Имя компонента копируется в std::string: DecodeSession хранит указатель и
    // переиспользует его при эскалации, а jstring живёт только до конца вызова.
    const std::string name = codecName != nullptr ? ToUtf8(env, codecName) : std::string();

    DecodeSessionConfig cfg;
    cfg.stream_index = static_cast<int>(streamIndex);
    cfg.prefer_ten_bit = preferTenBit == JNI_TRUE;
    cfg.allow_software_escalation = allowEscalate == JNI_TRUE;
    cfg.send_hdr_static_info = sendHdr == JNI_TRUE;
    cfg.force_software = forceSoftware == JNI_TRUE;
    cfg.codec_name = name.empty() ? nullptr : name.c_str();
    ANativeWindow *window = surface != nullptr ? ANativeWindow_fromSurface(env, surface) : nullptr;
    cfg.surface = window;

    std::string why;
    DecodeSession *session = DecodeSession::Create(demux, cfg, &why);
    if (session == nullptr) {
        if (window != nullptr) ANativeWindow_release(window);
        return fail(why.c_str());
    }

    DecoderHandle *h = new DecoderHandle();
    h->session = session;
    h->window = window;
    return reinterpret_cast<jlong>(h);
}

void NativeRelease(JNIEnv *, jobject, jlong handle) {
    delete Handle(handle);
}

/**
 * Один шаг насоса: докормить декодер и попробовать вынуть кадр.
 *
 * @return `Step.ordinal()`: FRAME=0, AGAIN=1, EOS=2, ERROR=3.
 */
jint NativeNextFrame(JNIEnv *, jobject, jlong handle, jint timeoutMs) {
    DecoderHandle *h = Live(handle, "nextFrame");
    if (h == nullptr) return kStepError;

    // Предыдущий кадр обязан быть отпущен вызывающим. Молча отпустить его здесь
    // означало бы, что кадр, на который Kotlin ещё держит ссылку, вернулся
    // декодеру и переписался следующим — то есть тихая порча данных в тесте.
    if (HasFrame(h)) {
        DDD_LOGE("decoder: nextFrame вызван без releaseFrame предыдущего кадра");
        return kStepError;
    }

    switch (h->session->NextFrame(&h->frame, static_cast<int>(timeoutMs))) {
        case DecodeSession::Step::kFrame: return kStepFrame;
        case DecodeSession::Step::kAgain: return kStepAgain;
        case DecodeSession::Step::kEos:   return kStepEos;
        default:                          return kStepError;
    }
}

void NativeReleaseFrame(JNIEnv *, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    if (h == nullptr || h->session == nullptr || !HasFrame(h)) return;
    h->session->ReleaseFrame(h->frame);
    ClearFrame(h);
}

jboolean NativeFlush(JNIEnv *, jobject, jlong handle) {
    DecoderHandle *h = Live(handle, "flush");
    if (h == nullptr) return JNI_FALSE;
    if (HasFrame(h)) {
        h->session->ReleaseFrame(h->frame);
        ClearFrame(h);
    }
    return h->session->Flush() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Рабочий путь шага 5: кадр из буфера декодера заливается прямо в текстуры
 * рендерера, без копирования через JVM.
 *
 * @param rendererHandle хэндл `NativeRenderer`.
 * @return false — нет текущего кадра, нет рендерера или GL-ошибка (см. logcat).
 */
jboolean NativeUploadToRenderer(JNIEnv *, jobject, jlong handle, jlong rendererHandle) {
    DecoderHandle *h = Handle(handle);
    if (!HasFrame(h)) {
        DDD_LOGE("decoder: uploadToRenderer — нет текущего кадра");
        return JNI_FALSE;
    }
    // Profile 8.1 already carries an HDR10-compatible BT.2020/PQ base layer.
    // The current experimental RPU reshaping path turns even a neutral limited-
    // range black P010 frame green on Pixel (Y=65, U/V=512 in 10-bit code
    // values). Until that mapper is validated against a Dolby reference, render
    // the compatible base layer through the regular HDR/PQ path. Decoder,
    // renderer and tone mapper remain the same unified engine.
    return UploadFrameToRenderer(rendererHandle, h->frame.frame, nullptr)
               ? JNI_TRUE
               : JNI_FALSE;
}

jboolean NativeRenderToSurface(JNIEnv *, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    if (h == nullptr || h->session == nullptr || h->frame.index < 0) return JNI_FALSE;
    if (!h->session->RenderFrame(h->frame)) return JNI_FALSE;
    ClearFrame(h);
    return JNI_TRUE;
}

jboolean NativeSurfaceOutput(JNIEnv *, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    return h != nullptr && h->session != nullptr && h->session->surface_output() ? JNI_TRUE
                                                                                : JNI_FALSE;
}

// ─────────────────── сырые байты кадра (только для тестов) ───────────────────

/** Значащих байт в строке плоскости; 0 — плоскости нет. */
jint NativePlaneRowBytes(JNIEnv *, jobject, jlong handle, jint planeIndex) {
    DecoderHandle *h = Handle(handle);
    if (h == nullptr || h->session == nullptr || !HasFrame(h)) return 0;
    if (planeIndex < 0 || planeIndex > 2 || h->frame.frame.plane[planeIndex] == nullptr) return 0;
    int row_bytes = 0;
    int rows = 0;
    PlaneGeometry(h->session->output(), planeIndex, &row_bytes, &rows);
    return row_bytes;
}

/** Строк в плоскости; 0 — плоскости нет. */
jint NativePlaneRows(JNIEnv *, jobject, jlong handle, jint planeIndex) {
    DecoderHandle *h = Handle(handle);
    if (h == nullptr || h->session == nullptr || !HasFrame(h)) return 0;
    if (planeIndex < 0 || planeIndex > 2 || h->frame.frame.plane[planeIndex] == nullptr) return 0;
    int row_bytes = 0;
    int rows = 0;
    PlaneGeometry(h->session->output(), planeIndex, &row_bytes, &rows);
    return rows;
}

/**
 * Копирует плоскость текущего кадра ПЛОТНО: `rowBytes * rows`, без stride.
 *
 * Нужно тесту: `uploadToRenderer` показывает результат через GL, а чтобы
 * доказать, что 10 бит настоящие (MSB-выравнивание, коды не кратны 4), нужны
 * сырые байты декодера. Плотная упаковка убирает stride из уравнения: тест
 * сравнивает байты с тем, что вылезло из текстуры, а не подгоняет раскладку.
 *
 * @param planeIndex 0=Y, 1=UV (P010/NV12) либо U, 2=V.
 * @return скопировано байт, 0 если плоскости нет, -1 при ошибке.
 */
jint NativeCopyPlane(JNIEnv *env, jobject, jlong handle, jint planeIndex, jbyteArray out) {
    DecoderHandle *h = Handle(handle);
    if (h == nullptr || h->session == nullptr || !HasFrame(h)) return -1;
    if (planeIndex < 0 || planeIndex > 2 || out == nullptr) return -1;

    const FrameDesc &f = h->frame.frame;
    if (f.plane[planeIndex] == nullptr) return 0;  // у P010 плоскости V нет

    int row_bytes = 0;
    int rows = 0;
    PlaneGeometry(h->session->output(), planeIndex, &row_bytes, &rows);
    const jint need = row_bytes * rows;
    if (env->GetArrayLength(out) < need) {
        DDD_LOGE("decoder: copyPlane[%d] — буферу нужно %d байт",
                 static_cast<int>(planeIndex), static_cast<int>(need));
        return -1;
    }

    const int stride = f.stride[planeIndex];
    for (int y = 0; y < rows; ++y) {
        env->SetByteArrayRegion(
            out, y * row_bytes, row_bytes,
            reinterpret_cast<const jbyte *>(f.plane[planeIndex] +
                                            static_cast<size_t>(y) * static_cast<size_t>(stride)));
    }
    return need;
}

/** PTS текущего кадра в микросекундах; 0, если кадра нет. */
jlong NativeFramePtsUs(JNIEnv *, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    if (!HasFrame(h)) return 0;
    return static_cast<jlong>(h->frame.pts_us);
}

// ─────────────── состояние декодера (диагностика и проверки) ───────────────

/** Сырой `KEY_COLOR_FORMAT`, который вернул декодер; -1 при ошибке. */
jint NativeColorFormat(JNIEnv *, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    if (h == nullptr || h->session == nullptr) return -1;
    return static_cast<jint>(h->session->output().color_format);
}

/** `FramePixelFormat.ordinal()` — то же, что принимает NativeRenderer. */
jint NativePixelFormat(JNIEnv *, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    if (h == nullptr || h->session == nullptr) return -1;
    return static_cast<jint>(h->session->output().format);
}

jint NativeOutputWidth(JNIEnv *, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    return (h != nullptr && h->session != nullptr) ? h->session->output().width : 0;
}

jint NativeOutputHeight(JNIEnv *, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    return (h != nullptr && h->session != nullptr) ? h->session->output().height : 0;
}

/** stride декодера — для диагностики; copyPlane уже отдаёт плотные строки. */
jint NativeStride(JNIEnv *, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    return (h != nullptr && h->session != nullptr) ? h->session->output().stride : 0;
}

jint NativeSliceHeight(JNIEnv *, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    return (h != nullptr && h->session != nullptr) ? h->session->output().slice_height : 0;
}

/** true — stride и slice-height пришли от декодера; false — выведены из ширины. */
jboolean NativeStrideReported(JNIEnv *, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    return (h != nullptr && h->session != nullptr && h->session->output().stride_reported)
               ? JNI_TRUE
               : JNI_FALSE;
}

/** Имя реально работающего компонента — для баг-репортов и UI. */
jstring NativeDecoderName(JNIEnv *env, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    if (h == nullptr || h->session == nullptr) return env->NewStringUTF("");
    return env->NewStringUTF(h->session->decoder_name().c_str());
}

/** 1/2/3 — ступень лестницы, на которой декодер поднялся. */
jint NativeRung(JNIEnv *, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    return (h != nullptr && h->session != nullptr) ? h->session->rung() : 0;
}

/**
 * Описание эскалации или пустая строка.
 *
 * Непустая строка значит: аппаратный декодер не отдал 10 бит и был заменён. Это
 * ровно тот случай, который тест обязан показать в логе, а не сгладить.
 */
jstring NativeEscalation(JNIEnv *env, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    if (h == nullptr || h->session == nullptr) return env->NewStringUTF("");
    return env->NewStringUTF(h->session->escalation().c_str());
}

jint NativeStreamBitDepth(JNIEnv *, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    return (h != nullptr && h->session != nullptr) ? h->session->stream_bit_depth() : 0;
}

jboolean NativeTenBitRequested(JNIEnv *, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    return (h != nullptr && h->session != nullptr && h->session->ten_bit_requested()) ? JNI_TRUE
                                                                                      : JNI_FALSE;
}

jboolean NativeTenBitOutput(JNIEnv *, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    return (h != nullptr && h->session != nullptr && h->session->ten_bit_output()) ? JNI_TRUE
                                                                                  : JNI_FALSE;
}

/** `ColorStandard.ordinal()` из пробинга контейнера. */
jint NativeStandard(JNIEnv *, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    if (h == nullptr || h->session == nullptr) return -1;
    return static_cast<jint>(h->session->standard());
}

jboolean NativeFullRange(JNIEnv *, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    return (h != nullptr && h->session != nullptr && h->session->full_range()) ? JNI_TRUE
                                                                              : JNI_FALSE;
}

jlong NativeFramesOut(JNIEnv *, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    return (h != nullptr && h->session != nullptr) ? static_cast<jlong>(h->session->frames_out())
                                                   : 0;
}

jlong NativePacketsIn(JNIEnv *, jobject, jlong handle) {
    DecoderHandle *h = Handle(handle);
    return (h != nullptr && h->session != nullptr) ? static_cast<jlong>(h->session->packets_in())
                                                   : 0;
}

const JNINativeMethod kMethods[] = {
    {"nativeCreate", "(JIZZZZLjava/lang/String;Landroid/view/Surface;[Ljava/lang/String;)J",
     reinterpret_cast<void *>(NativeCreate)},
    {"nativeRelease", "(J)V", reinterpret_cast<void *>(NativeRelease)},
    {"nativeNextFrame", "(JI)I", reinterpret_cast<void *>(NativeNextFrame)},
    {"nativeReleaseFrame", "(J)V", reinterpret_cast<void *>(NativeReleaseFrame)},
    {"nativeFlush", "(J)Z", reinterpret_cast<void *>(NativeFlush)},
    {"nativeUploadToRenderer", "(JJ)Z", reinterpret_cast<void *>(NativeUploadToRenderer)},
    {"nativeRenderToSurface", "(J)Z", reinterpret_cast<void *>(NativeRenderToSurface)},
    {"nativeSurfaceOutput", "(J)Z", reinterpret_cast<void *>(NativeSurfaceOutput)},
    {"nativePlaneRowBytes", "(JI)I", reinterpret_cast<void *>(NativePlaneRowBytes)},
    {"nativePlaneRows", "(JI)I", reinterpret_cast<void *>(NativePlaneRows)},
    {"nativeCopyPlane", "(JI[B)I", reinterpret_cast<void *>(NativeCopyPlane)},
    {"nativeFramePtsUs", "(J)J", reinterpret_cast<void *>(NativeFramePtsUs)},
    {"nativeColorFormat", "(J)I", reinterpret_cast<void *>(NativeColorFormat)},
    {"nativePixelFormat", "(J)I", reinterpret_cast<void *>(NativePixelFormat)},
    {"nativeOutputWidth", "(J)I", reinterpret_cast<void *>(NativeOutputWidth)},
    {"nativeOutputHeight", "(J)I", reinterpret_cast<void *>(NativeOutputHeight)},
    {"nativeStride", "(J)I", reinterpret_cast<void *>(NativeStride)},
    {"nativeSliceHeight", "(J)I", reinterpret_cast<void *>(NativeSliceHeight)},
    {"nativeStrideReported", "(J)Z", reinterpret_cast<void *>(NativeStrideReported)},
    {"nativeDecoderName", "(J)Ljava/lang/String;", reinterpret_cast<void *>(NativeDecoderName)},
    {"nativeRung", "(J)I", reinterpret_cast<void *>(NativeRung)},
    {"nativeEscalation", "(J)Ljava/lang/String;", reinterpret_cast<void *>(NativeEscalation)},
    {"nativeStreamBitDepth", "(J)I", reinterpret_cast<void *>(NativeStreamBitDepth)},
    {"nativeTenBitRequested", "(J)Z", reinterpret_cast<void *>(NativeTenBitRequested)},
    {"nativeTenBitOutput", "(J)Z", reinterpret_cast<void *>(NativeTenBitOutput)},
    {"nativeStandard", "(J)I", reinterpret_cast<void *>(NativeStandard)},
    {"nativeFullRange", "(J)Z", reinterpret_cast<void *>(NativeFullRange)},
    {"nativeFramesOut", "(J)J", reinterpret_cast<void *>(NativeFramesOut)},
    {"nativePacketsIn", "(J)J", reinterpret_cast<void *>(NativePacketsIn)},
};

}  // namespace

bool RegisterDecoderNatives(JNIEnv *env) {
    jclass cls = env->FindClass(kDecoderClass);
    if (cls == nullptr) {
        DDD_LOGE("jni: класс %s не найден", kDecoderClass);
        return false;
    }
    const jint r = env->RegisterNatives(cls, kMethods,
                                        static_cast<jint>(sizeof kMethods / sizeof kMethods[0]));
    env->DeleteLocalRef(cls);
    if (r != JNI_OK) {
        DDD_LOGE("jni: RegisterNatives(NativeVideoDecoder) вернул %d", static_cast<int>(r));
        return false;
    }
    return true;
}

}  // namespace ddd
