/*
 * media_format_map.h — перевод констант FFmpeg в домен `android.media.MediaFormat`.
 *
 * Зачем отдельный файл: этими значениями пользуются два разных этапа. Пробинг
 * (шаг 3) отдаёт их в Kotlin как `EngineColorInfo`, а HW-декодер (шаг 5) кладёт
 * их же в `AMediaFormat` перед `AMediaCodec_configure`. Если бы перевод жил в
 * одном из двух мест, второе рано или поздно получило бы «почти те же» значения —
 * а расхождение между тем, что показано в панели, и тем, что ушло в декодер,
 * отлаживается крайне неприятно.
 *
 * Числа константы `MediaFormat` заданы здесь литералами и продублированы в
 * `EngineColorInfo.kt`: NDK-заголовок `media/NdkMediaFormat.h` их не объявляет,
 * а тянуть Java-константы в native ради шести чисел — лишняя зависимость.
 */
#pragma once

extern "C" {
#include <libavcodec/codec_id.h>
#include <libavutil/pixfmt.h>
}

namespace ddd {

// MediaFormat.KEY_COLOR_STANDARD
constexpr int kColorStandardUnspecified = 0;
constexpr int kColorStandardBt709 = 1;
constexpr int kColorStandardBt601Pal = 2;
constexpr int kColorStandardBt601Ntsc = 4;
constexpr int kColorStandardBt2020 = 6;

// MediaFormat.KEY_COLOR_TRANSFER
constexpr int kColorTransferUnspecified = 0;
constexpr int kColorTransferLinear = 1;
constexpr int kColorTransferSdrVideo = 3;
constexpr int kColorTransferSt2084 = 6;
constexpr int kColorTransferHlg = 7;

// MediaFormat.KEY_COLOR_RANGE
constexpr int kColorRangeUnspecified = 0;
constexpr int kColorRangeFull = 1;
constexpr int kColorRangeLimited = 2;

/**
 * Цветовой стандарт.
 *
 * `MediaFormat` объединяет в одном ключе то, что у FFmpeg разнесено на
 * `color_primaries` (гамут) и `color_space` (матрица). Приоритет отдан
 * праймериз: именно они определяют гамут, а значит и матрицу пересчёта в
 * шейдере тонмаппинга (шаг 6). Матрица используется как запасной вариант —
 * у части файлов праймериз не указаны, а матрица есть.
 */
int ColorStandardFromFf(AVColorPrimaries primaries, AVColorSpace space);

/** Передаточная характеристика. PQ и HLG различаются — от этого зависит шейдер. */
int ColorTransferFromFf(AVColorTransferCharacteristic trc);

int ColorRangeFromFf(AVColorRange range);

/**
 * MIME для `MediaCodec`.
 *
 * @return строковый литерал или nullptr, если для кодека нет MIME в Android.
 *         nullptr — не ошибка: это означает «HW-пути нет, играем через
 *         libavcodec» (шаг 7). Так честнее, чем подставлять похожий MIME:
 *         именно подстановка `video/dolby-vision` → `video/hevc` в
 *         `PlayerManager.kt:228` и приводит к выброшенному RPU и неверным
 *         цветам на DV-файлах.
 */
const char *MimeFromCodecId(AVCodecID id);

}  // namespace ddd
