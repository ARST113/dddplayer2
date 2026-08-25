/*
 * jni_decoder.h — регистрация нативных методов декодера видео.
 *
 * Отдельно от jni_engine.cpp и jni_renderer.cpp по той же причине, что и они друг
 * от друга: у декодера свой жизненный цикл (он живёт внутри сессии демукса, но
 * переживает пересоздание при эскалации) и своё требование к потоку — все вызовы
 * из одного, того же, где создан.
 */
#pragma once

#include <jni.h>

namespace ddd {

/**
 * Регистрирует методы `NativeVideoDecoder` через `RegisterNatives`.
 * Вызывается из `JNI_OnLoad` после регистрации рендерера.
 */
bool RegisterDecoderNatives(JNIEnv *env);

}  // namespace ddd
