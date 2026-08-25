/*
 * render_reference.h — эталонная конверсия через swscale и кадр для замеров.
 *
 * Зачем эталон. Критерий шага 4 — «цвет как у Media3», а сверить это с самой
 * Media3 нечем: её GL-конвейер рисует в SurfaceFlinger, откуда пиксели обратно не
 * читаются. Поэтому эталоном взят swscale — та же арифметика BT.601/709/2020, что
 * и в шейдере ExoPlayer, и в отличие от него доступная побайтово.
 *
 * Чего здесь НЕТ и почему. Синтетические кадры для сравнения генерирует Kotlin
 * (см. NativeRenderTest / NativeRender10Test) и передаёт ОДНИ И ТЕ ЖЕ массивы в
 * GL и в эталон. Если бы кадр для обеих сторон готовился здесь, ошибка генератора
 * вычиталась бы сама из себя, и тест проходил бы на неверных данных. Нативный
 * генератор остался только для замера скорости, где кадр не должен ходить через
 * JNI, — и там его содержимое не имеет значения вовсе.
 */
#pragma once

#include <cstdint>
#include <vector>

#include "video_renderer.h"

namespace ddd {

/**
 * Кадр в памяти вместе с описанием. Плоскости лежат в одном буфере подряд;
 * указатели раздаются через [desc], а не хранятся, чтобы перемещение объекта
 * не оставляло висячих указателей.
 */
class SyntheticFrame {
public:
    FrameDesc desc() const;

    int width() const { return width_; }
    int height() const { return height_; }
    const std::vector<uint8_t> &bytes() const { return data_; }

    /**
     * Кадр заданного размера и формата, заполненный псевдослучайно, — для замера
     * скорости.
     *
     * Содержимое не имеет значения, важно лишь чтобы драйвер не мог его «узнать»
     * и пропустить заливку. Для 16-битных форматов случайны и младшие разряды:
     * заливка от этого не зависит, а вот «подозрительно ровные» данные драйвер
     * теоретически мог бы сжать.
     */
    static SyntheticFrame MakeNoise(int width, int height, FramePixelFormat format,
                                    ColorStandard standard, bool full_range, uint32_t seed);

    /** Меняет один байт яркости: заставляет драйвер честно перезалить текстуру. */
    void Touch(uint32_t counter);

private:
    /** Выделяет плоскости под формат и размер; содержимое не заполняет. */
    static SyntheticFrame Allocate(int width, int height, FramePixelFormat format,
                                   ColorStandard standard, bool full_range);

    std::vector<uint8_t> data_;
    size_t offset_[3] = {0, 0, 0};
    int stride_[3] = {0, 0, 0};
    int width_ = 0;
    int height_ = 0;
    FramePixelFormat format_ = FramePixelFormat::kYuv420p;
    ColorStandard standard_ = ColorStandard::kBt709;
    bool full_range_ = false;
};

/**
 * Эталонная конверсия 8-битного YUV→RGBA через swscale.
 *
 * @param out_rgba буфер `width * height * 4`, порядок R,G,B,A, сверху вниз.
 * @return false, если кадр 16-битный (нужен [SwscaleReference16]) или swscale не
 *         смог создать контекст для такой пары форматов.
 */
bool SwscaleReference(const FrameDesc &frame, uint8_t *out_rgba);

/**
 * То же для 10/12/16-битных кадров: выход RGBA64LE, 16 бит на канал.
 *
 * Отдельная функция, а не флаг, потому что и тип буфера другой, и сравнивать
 * результат надо в других единицах. Через 8-битный эталон 10-битный путь
 * проверить нельзя в принципе: эталон сам стал бы горлышком точности, и
 * сравнение показало бы «совпадает» при любой потере младших разрядов.
 *
 * @param out_rgba64 буфер `width * height * 4` слов uint16 (R,G,B,A), сверху вниз.
 */
bool SwscaleReference16(const FrameDesc &frame, uint16_t *out_rgba64);

}  // namespace ddd
