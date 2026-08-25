/*
 * jni_engine.cpp — граница Kotlin ↔ native для шага 3 (демукс и пробинг).
 *
 * Регистрация методов сделана через `RegisterNatives`, а не через имена вида
 * `Java_top_rootu_dddplayer_...`. Причины две:
 *
 *  1. Расхождение подписи с Kotlin становится ошибкой при `System.loadLibrary`,
 *     а не «UnsatisfiedLinkError на первом вызове через полчаса игры». Весь
 *     контракт при этом виден в одном месте — таблице [kMethods] ниже.
 *  2. Переименование пакета не превращается в правку двадцати имён функций.
 *
 * Пакетов данных здесь нет намеренно: декодирование остаётся в native (шаги 5 и
 * 7), и гонять пакеты в JVM и обратно было бы копированием ради копирования.
 * Kotlin получает от движка модель (`EngineTracks`, `EngineColorInfo`) и
 * состояние буфера — то, что ему действительно нужно для UI.
 */
#include <jni.h>

#include <cinttypes>
#include <cstring>
#include <string>
#include <vector>

#include "ddd_log.h"
#include "demux_session.h"
#include "ff_include.h"
#include "hdr_static_info.h"
#include "jni_decoder.h"
#include "jni_audio_decoder.h"
#include "jni_io_source.h"
#include "jni_renderer.h"
#include "jni_util.h"
#include "probe.h"

namespace {

constexpr const char *kNativeDemuxerClass = "top/rootu/dddplayer/engine/NativeDemuxer";

/** Раскладка массива статистики. Дублируется константами в `NativeDemuxer.kt`. */
enum StatsSlot : int {
    kStatBufferedPositionMs = 0,
    kStatQueueStartMs = 1,
    kStatBufferedDurationMs = 2,
    kStatQueuedBytes = 3,
    kStatQueuedPackets = 4,
    kStatEof = 5,
    kStatReadErrors = 6,
    kStatSeeks = 7,
    kStatPacketsRead = 8,
    kStatSlotCount = 9
};

ddd::DemuxSession *Session(jlong handle) {
    return reinterpret_cast<ddd::DemuxSession *>(handle);
}

/**
 * Перекладывает лог FFmpeg в logcat.
 *
 * Без этого сообщения вида «moov atom not found» уходят в stderr, которого у
 * приложения на Android нет, — и причина «файл не открылся» пропадает.
 */
void AvLogToLogcat(void *avcl, int level, const char *fmt, va_list vl) {
    if (level > av_log_get_level()) return;

    char line[1024] = {0};
    int print_prefix = 1;
    av_log_format_line2(avcl, level, fmt, vl, line, sizeof line, &print_prefix);

    // Хвостовой перевод строки logcat добавит сам; двойной даёт пустые строки.
    size_t len = strlen(line);
    while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) line[--len] = '\0';
    if (len == 0) return;

    int prio = ANDROID_LOG_DEBUG;
    if (level <= AV_LOG_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level <= AV_LOG_WARNING) prio = ANDROID_LOG_WARN;
    else if (level <= AV_LOG_INFO) prio = ANDROID_LOG_INFO;
    __android_log_write(prio, "DddFFmpeg", line);
}

// ───────────────────────────── ProbeSink ─────────────────────────────

/**
 * Кэш методов Java-интерфейса `ProbeSink`.
 *
 * Выбран колбэк, а не сборка Kotlin-объектов из native: конструировать
 * `data class` через JNI — это ручной `NewObject` с позиционными аргументами,
 * где добавление поля в Kotlin ломает native молча (подпись меняется, а
 * `GetMethodID` возвращает null уже в рантайме). Колбэк с примитивами
 * проверяется при загрузке библиотеки и читается без сверки порядка полей.
 */
struct SinkMethods {
    jmethodID container = nullptr;
    jmethodID video = nullptr;
    jmethodID audio = nullptr;
    jmethodID subtitle = nullptr;
    jmethodID color = nullptr;
    jmethodID geometry = nullptr;
    jmethodID best = nullptr;

    bool Resolve(JNIEnv *env, jobject sink) {
        jclass cls = env->GetObjectClass(sink);
        if (cls == nullptr) {
            ddd::ClearPendingException(env, "GetObjectClass(ProbeSink)");
            return false;
        }
        container = env->GetMethodID(cls, "container",
                                    "(Ljava/lang/String;Ljava/lang/String;JJZ)V");
        video = env->GetMethodID(
            cls, "video", "(IIIIIFILjava/lang/String;Ljava/lang/String;IIIIZ)V");
        audio = env->GetMethodID(
            cls, "audio",
            "(ILjava/lang/String;Ljava/lang/String;IIILjava/lang/String;Ljava/lang/String;ZZ)V");
        subtitle = env->GetMethodID(
            cls, "subtitle", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZZ)V");
        color = env->GetMethodID(cls, "color", "(IIIIIIZ[B)V");
        geometry = env->GetMethodID(cls, "geometry", "(II)V");
        best = env->GetMethodID(cls, "best", "(III)V");
        env->DeleteLocalRef(cls);

        if (container == nullptr || video == nullptr || audio == nullptr || subtitle == nullptr ||
            color == nullptr || geometry == nullptr || best == nullptr) {
            ddd::ClearPendingException(env, "GetMethodID(ProbeSink)");
            DDD_LOGE("jni: объект не реализует ProbeSink целиком");
            return false;
        }
        return true;
    }
};

/** Освобождает локальные ссылки сразу: их лимит в кадре — всего 512. */
struct LocalRef {
    JNIEnv *env;
    jobject ref;
    LocalRef(JNIEnv *e, jobject r) : env(e), ref(r) {}
    ~LocalRef() {
        if (ref != nullptr) env->DeleteLocalRef(ref);
    }
    LocalRef(const LocalRef &) = delete;
    LocalRef &operator=(const LocalRef &) = delete;
};

// ───────────────────────────── нативные методы ─────────────────────────────

jstring NativeVersion(JNIEnv *env, jobject) {
    char buf[256];
    snprintf(buf, sizeof buf, "ddd_engine/step3 ffmpeg %s (libavformat %u.%u.%u)",
             av_version_info(), LIBAVFORMAT_VERSION_MAJOR, LIBAVFORMAT_VERSION_MINOR,
             LIBAVFORMAT_VERSION_MICRO);
    return env->NewStringUTF(buf);
}

jboolean NativeSelfTest(JNIEnv *, jobject) {
    return ddd::SelfTestHdrStaticInfo() ? JNI_TRUE : JNI_FALSE;
}

void NativeSetVerboseLogs(JNIEnv *, jobject, jboolean verbose) {
    av_log_set_level(verbose == JNI_TRUE ? AV_LOG_VERBOSE : AV_LOG_WARNING);
}

jstring NativeErrorString(JNIEnv *env, jobject, jint code) {
    char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(code, buf, sizeof buf);
    return env->NewStringUTF(buf);
}

/**
 * @param url     URL для FFmpeg; используется только когда [io] == null.
 * @param io      Java-источник байт (`EngineIo`); имеет приоритет над [url].
 * @param options плоский массив `ключ, значение, ключ, значение…`.
 * @param error_out массив минимум из 1 int: сюда пишется код `AVERROR`.
 * @return хэндл сессии или 0.
 */
jlong NativeOpen(JNIEnv *env, jobject, jstring url, jobject io, jobjectArray options,
                 jintArray error_out) {
    const auto report = [&](int code) {
        if (error_out != nullptr && env->GetArrayLength(error_out) > 0) {
            jint v = code;
            env->SetIntArrayRegion(error_out, 0, 1, &v);
        }
    };

    ddd::IoSource *source = nullptr;
    if (io != nullptr) {
        source = ddd::JavaIoSource::Create(env, io);
        if (source == nullptr) {
            report(AVERROR(EINVAL));
            return 0;
        }
    }

    const std::string url_utf8 = ddd::ToUtf8(env, url);
    if (source == nullptr && url_utf8.empty()) {
        DDD_LOGE("jni: open без url и без EngineIo");
        report(AVERROR(EINVAL));
        return 0;
    }

    ddd::DemuxSession::Options opts;
    if (options != nullptr) {
        const jsize n = env->GetArrayLength(options);
        // Непарный хвост игнорируем: это ошибка вызывающего, но терять из-за неё
        // уже собранные опции (user_agent, headers) незачем.
        for (jsize i = 0; i + 1 < n; i += 2) {
            LocalRef k(env, env->GetObjectArrayElement(options, i));
            LocalRef v(env, env->GetObjectArrayElement(options, i + 1));
            const std::string key = ddd::ToUtf8(env, static_cast<jstring>(k.ref));
            if (key.empty()) continue;
            opts.emplace_back(key, ddd::ToUtf8(env, static_cast<jstring>(v.ref)));
        }
        if (n % 2 != 0) DDD_LOGW("jni: в options непарное число элементов (%d)", static_cast<int>(n));
    }

    int av_error = 0;
    ddd::DemuxSession *session = ddd::DemuxSession::Open(
        source != nullptr ? nullptr : url_utf8.c_str(), source, opts, &av_error);
    if (session == nullptr) {
        // Источник уже удалён внутри Open (он забирает владение сразу), кроме
        // случая, когда до его передачи дело не дошло, — а сюда мы попадаем
        // только после передачи.
        report(av_error != 0 ? av_error : AVERROR_UNKNOWN);
        return 0;
    }
    report(0);
    return reinterpret_cast<jlong>(session);
}

jboolean NativeProbe(JNIEnv *env, jobject, jlong handle, jobject sink) {
    ddd::DemuxSession *session = Session(handle);
    if (session == nullptr || sink == nullptr) return JNI_FALSE;

    SinkMethods m;
    if (!m.Resolve(env, sink)) return JNI_FALSE;

    const ddd::ProbeResult &p = session->probe();

    {
        LocalRef format(env, ddd::ToJString(env, p.container.format.c_str()));
        LocalRef long_name(env, ddd::ToJString(env, p.container.long_name.c_str()));
        env->CallVoidMethod(sink, m.container, static_cast<jstring>(format.ref),
                            static_cast<jstring>(long_name.ref),
                            static_cast<jlong>(p.container.duration_us),
                            static_cast<jlong>(p.container.bitrate),
                            p.container.seekable ? JNI_TRUE : JNI_FALSE);
        if (ddd::ClearPendingException(env, "ProbeSink.container")) return JNI_FALSE;
    }

    for (const auto &v : p.video) {
        LocalRef codec(env, ddd::ToJString(env, v.codec.c_str()));
        LocalRef mime(env, ddd::ToJString(env, v.mime.c_str()));
        env->CallVoidMethod(sink, m.video, v.stream_index, v.width, v.height, v.sar_num, v.sar_den,
                            v.frame_rate, v.bitrate, static_cast<jstring>(codec.ref),
                            static_cast<jstring>(mime.ref), v.profile, v.level, v.rotation,
                            v.bit_depth, v.is_default ? JNI_TRUE : JNI_FALSE);
        if (ddd::ClearPendingException(env, "ProbeSink.video")) return JNI_FALSE;
    }

    for (const auto &a : p.audio) {
        LocalRef codec(env, ddd::ToJString(env, a.codec.c_str()));
        LocalRef profile(env, ddd::ToJString(env, a.profile.c_str()));
        LocalRef language(env, ddd::ToJString(env, a.language.c_str()));
        LocalRef title(env, ddd::ToJString(env, a.title.c_str()));
        env->CallVoidMethod(sink, m.audio, a.stream_index, static_cast<jstring>(codec.ref),
                            static_cast<jstring>(profile.ref), a.channels, a.sample_rate, a.bitrate,
                            static_cast<jstring>(language.ref), static_cast<jstring>(title.ref),
                            a.is_default ? JNI_TRUE : JNI_FALSE, a.is_forced ? JNI_TRUE : JNI_FALSE);
        if (ddd::ClearPendingException(env, "ProbeSink.audio")) return JNI_FALSE;
    }

    for (const auto &s : p.subtitle) {
        LocalRef codec(env, ddd::ToJString(env, s.codec.c_str()));
        LocalRef language(env, ddd::ToJString(env, s.language.c_str()));
        LocalRef title(env, ddd::ToJString(env, s.title.c_str()));
        env->CallVoidMethod(sink, m.subtitle, s.stream_index, static_cast<jstring>(codec.ref),
                            static_cast<jstring>(language.ref), static_cast<jstring>(title.ref),
                            s.is_default ? JNI_TRUE : JNI_FALSE, s.is_forced ? JNI_TRUE : JNI_FALSE,
                            s.is_bitmap ? JNI_TRUE : JNI_FALSE);
        if (ddd::ClearPendingException(env, "ProbeSink.subtitle")) return JNI_FALSE;
    }

    {
        // Пустой блок не отдаётся вовсе: nullptr на стороне Kotlin означает «HDR
        // метаданных нет», а массив из 25 нулей означал бы «пиковая яркость 0».
        jbyteArray static_info = nullptr;
        if (p.color.has_static_info) {
            static_info = env->NewByteArray(ddd::kHdrStaticInfoSize);
            if (static_info == nullptr) {
                ddd::ClearPendingException(env, "NewByteArray(hdrStaticInfo)");
                return JNI_FALSE;
            }
            env->SetByteArrayRegion(static_info, 0, ddd::kHdrStaticInfoSize,
                                    reinterpret_cast<const jbyte *>(p.color.static_info));
        }
        LocalRef guard(env, static_info);
        env->CallVoidMethod(sink, m.color, p.color.color_standard, p.color.color_transfer,
                            p.color.color_range, p.color.bit_depth, p.color.dolby_profile,
                            p.color.dolby_stream_index,
                            p.color.has_hdr10_plus ? JNI_TRUE : JNI_FALSE, static_info);
        if (ddd::ClearPendingException(env, "ProbeSink.color")) return JNI_FALSE;
    }

    env->CallVoidMethod(sink, m.geometry, static_cast<jint>(p.stereo),
                        static_cast<jint>(p.projection));
    if (ddd::ClearPendingException(env, "ProbeSink.geometry")) return JNI_FALSE;

    env->CallVoidMethod(sink, m.best, p.best_video_index, p.best_audio_index,
                        p.best_subtitle_index);
    if (ddd::ClearPendingException(env, "ProbeSink.best")) return JNI_FALSE;

    return JNI_TRUE;
}

jlong NativeDurationMs(JNIEnv *, jobject, jlong handle) {
    ddd::DemuxSession *session = Session(handle);
    return session != nullptr ? session->duration_ms() : 0;
}

jboolean NativeSelectStreams(JNIEnv *, jobject, jlong handle, jint video, jint audio,
                             jint subtitle) {
    ddd::DemuxSession *session = Session(handle);
    return session != nullptr && session->SelectStreams(video, audio, subtitle) ? JNI_TRUE
                                                                               : JNI_FALSE;
}

jboolean NativeStart(JNIEnv *, jobject, jlong handle) {
    ddd::DemuxSession *session = Session(handle);
    return session != nullptr && session->Start() ? JNI_TRUE : JNI_FALSE;
}

void NativeStop(JNIEnv *, jobject, jlong handle) {
    ddd::DemuxSession *session = Session(handle);
    if (session != nullptr) session->Stop();
}

jboolean NativeSeek(JNIEnv *, jobject, jlong handle, jlong position_ms) {
    ddd::DemuxSession *session = Session(handle);
    return session != nullptr && session->Seek(position_ms) ? JNI_TRUE : JNI_FALSE;
}

jboolean NativeStats(JNIEnv *env, jobject, jlong handle, jlongArray out) {
    ddd::DemuxSession *session = Session(handle);
    if (session == nullptr || out == nullptr) return JNI_FALSE;
    if (env->GetArrayLength(out) < kStatSlotCount) {
        DDD_LOGE("jni: массиву статистики нужно %d элементов", kStatSlotCount);
        return JNI_FALSE;
    }

    const ddd::DemuxSession::Stats s = session->GetStats();
    jlong values[kStatSlotCount];
    values[kStatBufferedPositionMs] = s.buffered_position_ms;
    values[kStatQueueStartMs] = s.queue_start_ms;
    values[kStatBufferedDurationMs] = s.buffered_duration_ms;
    values[kStatQueuedBytes] = s.queued_bytes;
    values[kStatQueuedPackets] = s.queued_packets;
    values[kStatEof] = s.eof ? 1 : 0;
    values[kStatReadErrors] = s.read_errors;
    values[kStatSeeks] = s.seeks;
    values[kStatPacketsRead] = session->packets_read();
    env->SetLongArrayRegion(out, 0, kStatSlotCount, values);
    return JNI_TRUE;
}

void NativeClose(JNIEnv *, jobject, jlong handle) {
    ddd::DemuxSession *session = Session(handle);
    // Деструктор останавливает поток демукса, закрывает демуксер, освобождает
    // AVIOContext и вызывает EngineIo.close() — в этом порядке.
    delete session;
}

const JNINativeMethod kMethods[] = {
    {"nativeVersion", "()Ljava/lang/String;", reinterpret_cast<void *>(NativeVersion)},
    {"nativeSelfTest", "()Z", reinterpret_cast<void *>(NativeSelfTest)},
    {"nativeSetVerboseLogs", "(Z)V", reinterpret_cast<void *>(NativeSetVerboseLogs)},
    {"nativeErrorString", "(I)Ljava/lang/String;", reinterpret_cast<void *>(NativeErrorString)},
    {"nativeOpen",
     "(Ljava/lang/String;Ltop/rootu/dddplayer/engine/EngineIo;[Ljava/lang/String;[I)J",
     reinterpret_cast<void *>(NativeOpen)},
    {"nativeProbe", "(JLtop/rootu/dddplayer/engine/ProbeSink;)Z",
     reinterpret_cast<void *>(NativeProbe)},
    {"nativeDurationMs", "(J)J", reinterpret_cast<void *>(NativeDurationMs)},
    {"nativeSelectStreams", "(JIII)Z", reinterpret_cast<void *>(NativeSelectStreams)},
    {"nativeStart", "(J)Z", reinterpret_cast<void *>(NativeStart)},
    {"nativeStop", "(J)V", reinterpret_cast<void *>(NativeStop)},
    {"nativeSeek", "(JJ)Z", reinterpret_cast<void *>(NativeSeek)},
    {"nativeStats", "(J[J)Z", reinterpret_cast<void *>(NativeStats)},
    {"nativeClose", "(J)V", reinterpret_cast<void *>(NativeClose)},
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }

    ddd::SetJavaVm(vm);

    // Нужно libavcodec для MediaCodec-декодеров и для `content://` через
    // AVIO-протокол `android_content`; ставится здесь, потому что позже уже
    // может быть поздно — FFmpeg кэширует отсутствие VM.
    av_jni_set_java_vm(vm, nullptr);

    av_log_set_level(AV_LOG_WARNING);
    av_log_set_callback(AvLogToLogcat);

    jclass cls = env->FindClass(kNativeDemuxerClass);
    if (cls == nullptr) {
        DDD_LOGE("jni: класс %s не найден", kNativeDemuxerClass);
        return JNI_ERR;
    }
    const jint r = env->RegisterNatives(cls, kMethods,
                                        static_cast<jint>(sizeof kMethods / sizeof kMethods[0]));
    env->DeleteLocalRef(cls);
    if (r != JNI_OK) {
        // Сюда попадаем при расхождении подписи с Kotlin: ART уже написал в
        // logcat, какой именно метод не нашёлся.
        DDD_LOGE("jni: RegisterNatives вернул %d", static_cast<int>(r));
        return JNI_ERR;
    }

    if (!ddd::RegisterRendererNatives(env)) return JNI_ERR;
    if (!ddd::RegisterDecoderNatives(env)) return JNI_ERR;
    if (!ddd::RegisterAudioDecoderNatives(env)) return JNI_ERR;

    DDD_LOGI("jni: ddd_engine загружен, ffmpeg %s", av_version_info());
    return JNI_VERSION_1_6;
}
