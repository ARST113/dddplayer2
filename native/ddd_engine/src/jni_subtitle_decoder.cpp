#include "jni_subtitle_decoder.h"

#include <string>

#include "ddd_log.h"
#include "demux_session.h"
#include "subtitle_decoder.h"

namespace ddd {
namespace {

constexpr const char *kSubtitleDecoderClass =
    "top/rootu/dddplayer/engine/NativeSubtitleDecoder";

SubtitleDecodeSession *Session(jlong handle) {
    return reinterpret_cast<SubtitleDecodeSession *>(handle);
}

jlong NativeCreate(JNIEnv *env, jobject, jlong demux_handle, jint stream_index,
                   jobjectArray error_out) {
    auto fail = [&](const std::string &why) -> jlong {
        if (error_out != nullptr && env->GetArrayLength(error_out) > 0) {
            jstring value = env->NewStringUTF(why.c_str());
            env->SetObjectArrayElement(error_out, 0, value);
            if (value != nullptr) env->DeleteLocalRef(value);
        }
        return 0;
    };
    auto *demux = reinterpret_cast<DemuxSession *>(demux_handle);
    if (demux == nullptr) return fail("нулевой хэндл демукса");
    std::string why;
    SubtitleDecodeSession *session = SubtitleDecodeSession::Create(
        demux, stream_index, &why);
    return session != nullptr ? reinterpret_cast<jlong>(session) : fail(why);
}

void NativeRelease(JNIEnv *, jobject, jlong handle) { delete Session(handle); }

jstring NativeNextCue(JNIEnv *env, jobject, jlong handle, jint timeout_ms,
                      jlongArray result) {
    SubtitleDecodeSession *session = Session(handle);
    if (session == nullptr || result == nullptr || env->GetArrayLength(result) < 3) {
        return nullptr;
    }
    SubtitleCue cue;
    const SubtitleDecodeSession::Step step = session->Next(timeout_ms, &cue);
    const jlong values[3] = {static_cast<jlong>(step), cue.start_us, cue.end_us};
    env->SetLongArrayRegion(result, 0, 3, values);
    return step == SubtitleDecodeSession::Step::kCue
        ? env->NewStringUTF(cue.text.c_str())
        : nullptr;
}

jboolean NativeFlush(JNIEnv *, jobject, jlong handle) {
    SubtitleDecodeSession *session = Session(handle);
    return session != nullptr && session->Flush() ? JNI_TRUE : JNI_FALSE;
}

jstring NativeDecoderName(JNIEnv *env, jobject, jlong handle) {
    SubtitleDecodeSession *session = Session(handle);
    return env->NewStringUTF(session != nullptr ? session->decoder_name().c_str() : "");
}

const JNINativeMethod kMethods[] = {
    {"nativeCreate", "(JI[Ljava/lang/String;)J", reinterpret_cast<void *>(NativeCreate)},
    {"nativeRelease", "(J)V", reinterpret_cast<void *>(NativeRelease)},
    {"nativeNextCue", "(JI[J)Ljava/lang/String;", reinterpret_cast<void *>(NativeNextCue)},
    {"nativeFlush", "(J)Z", reinterpret_cast<void *>(NativeFlush)},
    {"nativeDecoderName", "(J)Ljava/lang/String;", reinterpret_cast<void *>(NativeDecoderName)},
};

}  // namespace

bool RegisterSubtitleDecoderNatives(JNIEnv *env) {
    jclass cls = env->FindClass(kSubtitleDecoderClass);
    if (cls == nullptr) {
        DDD_LOGE("jni: класс %s не найден", kSubtitleDecoderClass);
        return false;
    }
    const jint rc = env->RegisterNatives(
        cls, kMethods, static_cast<jint>(sizeof kMethods / sizeof kMethods[0]));
    env->DeleteLocalRef(cls);
    if (rc != JNI_OK) {
        DDD_LOGE("jni: RegisterNatives(NativeSubtitleDecoder) вернул %d", rc);
        return false;
    }
    return true;
}

}  // namespace ddd
