/*
 * ddd_log.h — логирование движка.
 *
 * Под Android идёт в logcat с тегом `DddEngine`; при сборке нативного теста
 * (`DDD_HOST_TEST`) — в stderr, чтобы ту же логику можно было гонять отдельным
 * бинарём без Java-слоя. Именно так проверялся шаг 2: если ошибка видна в
 * простом бинаре, её не нужно искать через три слоя Gradle/JNI.
 */
#pragma once

#include <cstdio>

#ifndef DDD_HOST_TEST
#include <android/log.h>
#define DDD_TAG "DddEngine"
#define DDD_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, DDD_TAG, __VA_ARGS__)
#define DDD_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  DDD_TAG, __VA_ARGS__)
#define DDD_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  DDD_TAG, __VA_ARGS__)
#define DDD_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, DDD_TAG, __VA_ARGS__)
#else
#define DDD_LOG_STDERR(lvl, ...)              \
    do {                                      \
        fprintf(stderr, "[%s] ", lvl);        \
        fprintf(stderr, __VA_ARGS__);         \
        fputc('\n', stderr);                  \
    } while (0)
#define DDD_LOGE(...) DDD_LOG_STDERR("E", __VA_ARGS__)
#define DDD_LOGW(...) DDD_LOG_STDERR("W", __VA_ARGS__)
#define DDD_LOGI(...) DDD_LOG_STDERR("I", __VA_ARGS__)
#define DDD_LOGD(...) DDD_LOG_STDERR("D", __VA_ARGS__)
#endif
