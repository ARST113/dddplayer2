/*
 * jni_util.cpp — реализация. См. jni_util.h.
 */
#include "jni_util.h"

#include "ddd_log.h"

namespace ddd {
namespace {

JavaVM *g_vm = nullptr;

/**
 * Отсоединяет поток от JVM при его завершении.
 *
 * Именно объект, а не вызов detach в конце `ThreadBody`: detach нужен и в том
 * случае, когда поток выходит по исключению или через break из середины цикла.
 */
struct ThreadDetacher {
    bool attached = false;

    ~ThreadDetacher() {
        if (attached && g_vm != nullptr) {
            g_vm->DetachCurrentThread();
            attached = false;
        }
    }
};

thread_local ThreadDetacher t_detacher;

}  // namespace

void SetJavaVm(JavaVM *vm) { g_vm = vm; }

JavaVM *JavaVm() { return g_vm; }

JNIEnv *Env() {
    if (g_vm == nullptr) {
        DDD_LOGE("jni: JavaVM не задана (JNI_OnLoad не выполнялся?)");
        return nullptr;
    }

    JNIEnv *env = nullptr;
    const jint r = g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (r == JNI_OK && env != nullptr) return env;

    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
        DDD_LOGE("jni: AttachCurrentThread не удался");
        return nullptr;
    }
    t_detacher.attached = true;
    return env;
}

bool ClearPendingException(JNIEnv *env, const char *what) {
    if (env == nullptr || env->ExceptionCheck() == JNI_FALSE) return false;

    // ExceptionDescribe пишет stack trace в logcat — без него от IOException
    // остаётся только «read вернул -5», и искать причину негде.
    DDD_LOGE("jni: исключение в %s", what != nullptr ? what : "?");
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

std::string ToUtf8(JNIEnv *env, jstring s) {
    if (env == nullptr || s == nullptr) return std::string();
    const char *chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) {
        env->ExceptionClear();  // OOM при выделении буфера
        return std::string();
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring ToJString(JNIEnv *env, const char *s) {
    if (env == nullptr || s == nullptr || *s == '\0') return nullptr;
    return env->NewStringUTF(s);
}

}  // namespace ddd
