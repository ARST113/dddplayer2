#include "jni_audio_decoder.h"

#include <cstdint>
#include <string>

#include "audio_decoder.h"
#include "ddd_log.h"
#include "demux_session.h"

namespace ddd {
namespace {

constexpr const char *kAudioDecoderClass =
    "top/rootu/dddplayer/engine/NativeAudioDecoder";

AudioDecodeSession *Session(jlong handle) {
    return reinterpret_cast<AudioDecodeSession *>(handle);
}

jlong NativeCreate(JNIEnv *env, jobject, jlong demux_handle, jint stream_index,
                   jint sample_rate, jint channels, jobjectArray error_out) {
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
    AudioDecodeSession *session = AudioDecodeSession::Create(
        demux, stream_index, sample_rate, channels, &why);
    return session != nullptr ? reinterpret_cast<jlong>(session) : fail(why);
}

void NativeRelease(JNIEnv *, jobject, jlong handle) { delete Session(handle); }

jint NativeNextPcm(JNIEnv *env, jobject, jlong handle, jobject buffer,
                   jint max_frames, jint timeout_ms, jlongArray result) {
    AudioDecodeSession *session = Session(handle);
    if (session == nullptr || buffer == nullptr || result == nullptr ||
        env->GetArrayLength(result) < 2) return 3;
    auto *out = static_cast<float *>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    const int64_t need = static_cast<int64_t>(max_frames) * session->channels() * sizeof(float);
    if (out == nullptr || capacity < need) {
        DDD_LOGE("audio: нужен direct ByteBuffer минимум %lld байт, есть %lld",
                 static_cast<long long>(need), static_cast<long long>(capacity));
        return 3;
    }
    AudioChunk chunk;
    const AudioDecodeSession::Step step = session->Next(
        out, max_frames, timeout_ms, &chunk);
    const jlong values[2] = {chunk.pts_us, chunk.frames};
    env->SetLongArrayRegion(result, 0, 2, values);
    return static_cast<jint>(step);
}

jboolean NativeFlush(JNIEnv *, jobject, jlong handle) {
    AudioDecodeSession *session = Session(handle);
    return session != nullptr && session->Flush() ? JNI_TRUE : JNI_FALSE;
}

jint NativeSampleRate(JNIEnv *, jobject, jlong handle) {
    AudioDecodeSession *session = Session(handle);
    return session != nullptr ? session->sample_rate() : 0;
}

jint NativeChannels(JNIEnv *, jobject, jlong handle) {
    AudioDecodeSession *session = Session(handle);
    return session != nullptr ? session->channels() : 0;
}

jint NativeInputSampleRate(JNIEnv *, jobject, jlong handle) {
    AudioDecodeSession *session = Session(handle);
    return session != nullptr ? session->input_sample_rate() : 0;
}

jint NativeInputChannels(JNIEnv *, jobject, jlong handle) {
    AudioDecodeSession *session = Session(handle);
    return session != nullptr ? session->input_channels() : 0;
}

jstring NativeDecoderName(JNIEnv *env, jobject, jlong handle) {
    AudioDecodeSession *session = Session(handle);
    return env->NewStringUTF(session != nullptr ? session->decoder_name().c_str() : "");
}

jlong NativePacketsIn(JNIEnv *, jobject, jlong handle) {
    AudioDecodeSession *session = Session(handle);
    return session != nullptr ? session->packets_in() : 0;
}

jlong NativeFramesOut(JNIEnv *, jobject, jlong handle) {
    AudioDecodeSession *session = Session(handle);
    return session != nullptr ? session->sample_frames_out() : 0;
}

const JNINativeMethod kMethods[] = {
    {"nativeCreate", "(JIII[Ljava/lang/String;)J", reinterpret_cast<void *>(NativeCreate)},
    {"nativeRelease", "(J)V", reinterpret_cast<void *>(NativeRelease)},
    {"nativeNextPcm", "(JLjava/nio/ByteBuffer;II[J)I", reinterpret_cast<void *>(NativeNextPcm)},
    {"nativeFlush", "(J)Z", reinterpret_cast<void *>(NativeFlush)},
    {"nativeSampleRate", "(J)I", reinterpret_cast<void *>(NativeSampleRate)},
    {"nativeChannels", "(J)I", reinterpret_cast<void *>(NativeChannels)},
    {"nativeInputSampleRate", "(J)I", reinterpret_cast<void *>(NativeInputSampleRate)},
    {"nativeInputChannels", "(J)I", reinterpret_cast<void *>(NativeInputChannels)},
    {"nativeDecoderName", "(J)Ljava/lang/String;", reinterpret_cast<void *>(NativeDecoderName)},
    {"nativePacketsIn", "(J)J", reinterpret_cast<void *>(NativePacketsIn)},
    {"nativeFramesOut", "(J)J", reinterpret_cast<void *>(NativeFramesOut)},
};

}  // namespace

bool RegisterAudioDecoderNatives(JNIEnv *env) {
    jclass cls = env->FindClass(kAudioDecoderClass);
    if (cls == nullptr) {
        DDD_LOGE("jni: класс %s не найден", kAudioDecoderClass);
        return false;
    }
    const jint rc = env->RegisterNatives(
        cls, kMethods, static_cast<jint>(sizeof kMethods / sizeof kMethods[0]));
    env->DeleteLocalRef(cls);
    if (rc != JNI_OK) {
        DDD_LOGE("jni: RegisterNatives(NativeAudioDecoder) вернул %d", rc);
        return false;
    }
    return true;
}

}  // namespace ddd
