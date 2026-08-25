/*
 * jni_renderer.h — регистрация нативных методов рендерера.
 *
 * Отдельно от jni_engine.cpp, чтобы граница «демукс/пробинг» и граница
 * «вывод» не расползались по одному файлу: у них разный жизненный цикл и разные
 * требования к потоку (EGL-контекст принадлежит одному потоку, демукс — нет).
 */
#pragma once

#include <jni.h>

#include "video_renderer.h"

namespace ddd {

struct DoviFrameMapping;

/**
 * Регистрирует методы `NativeRenderer` через `RegisterNatives`.
 * Вызывается из `JNI_OnLoad`.
 */
bool RegisterRendererNatives(JNIEnv *env);

/**
 * Заливает кадр в текстуры рендерера по хэндлу `NativeRenderer`, минуя JVM.
 *
 * Это рабочий путь шага 5, а не удобство: кадр из буфера `AMediaCodec` — это до
 * 25 МБ на 4K, и прогонять их через `byte[]` значило бы копировать кадр дважды
 * (в JVM и обратно) на каждый кадр. Здесь оба хэндла живут в native, и заливка
 * идёт прямо из буфера декодера.
 *
 * Хэндл проверяется, а контекст EGL делается текущим — как и в остальных
 * методах рендерера, потому что вызывающий поток здесь тот же, что и в тесте.
 *
 * @param handle хэндл `NativeRenderer` (тот же `long`, что в Kotlin).
 * @return false — хэндл нулевой, контекст не стал текущим или заливка не удалась.
 */
bool UploadFrameToRenderer(jlong handle, const FrameDesc &frame,
                           const DoviFrameMapping *dovi_mapping = nullptr);

}  // namespace ddd
