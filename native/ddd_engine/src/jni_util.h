/*
 * jni_util.h — минимальная обвязка JNI: JavaVM, привязка потоков, исключения.
 *
 * Отдельный файл нужен из-за одной особенности: поток демукса создаётся
 * нативным кодом (`std::thread` в `demux_session.cpp`), а читает он байты через
 * Java-объект `EngineIo`. Значит этот поток обязан быть привязан к JVM, иначе
 * первый же `CallIntMethod` из него — падение с «JNI called with pending
 * exception» или сразу SIGSEGV.
 *
 * Привязка сделана лениво и с автоматическим отсоединением через объект
 * `thread_local`: `AttachCurrentThread` на первом обращении, `DetachCurrentThread`
 * в деструкторе при выходе из потока. Забытый detach — это утечка потока в JVM
 * (поток остаётся в списке живых, GC не может его пройти), и проявляется она не
 * сразу, а после десятков открытий-закрытий файлов. Деструкторы `thread_local`
 * в bionic работают с API 23 — ровно наш `minSdk`.
 */
#pragma once

#include <jni.h>

#include <string>

namespace ddd {

/** Запоминает JavaVM. Вызывается один раз из `JNI_OnLoad`. */
void SetJavaVm(JavaVM *vm);

JavaVM *JavaVm();

/**
 * JNIEnv текущего потока, при необходимости привязывая поток к JVM.
 *
 * @return nullptr, если `JNI_OnLoad` не выполнялся или привязка не удалась.
 *         Вызывающий обязан проверить: возвращать `AVERROR(EIO)` в колбэке AVIO
 *         честнее, чем разыменовывать null.
 */
JNIEnv *Env();

/**
 * Снимает и логирует незакрытое Java-исключение.
 *
 * Проверять исключение обязательно после КАЖДОГО вызова в Java: JNI не
 * прерывает исполнение при исключении, а следующий JNI-вызов с висящим
 * исключением — фатальная ошибка ART, и в логе видно уже не причину, а её
 * последствие.
 *
 * @return true, если исключение было (и оно снято).
 */
bool ClearPendingException(JNIEnv *env, const char *what);

/** Java-строка → UTF-8; пустая строка для nullptr. */
std::string ToUtf8(JNIEnv *env, jstring s);

/**
 * UTF-8 → Java-строка. Возвращает nullptr для nullptr и для пустой строки,
 * потому что на стороне Kotlin поля вида `mime` объявлены как `String?`, и
 * «нет значения» должно приходить как null, а не как "".
 */
jstring ToJString(JNIEnv *env, const char *s);

}  // namespace ddd
