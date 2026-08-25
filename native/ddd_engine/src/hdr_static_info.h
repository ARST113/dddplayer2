/*
 * hdr_static_info.h — сборка 25 байт `MediaFormat.KEY_HDR_STATIC_INFO`.
 *
 * Это ядро HDR-пути. Без этого блока `MediaCodec` не знает пиковую яркость
 * мастеринга, maxCLL и maxFALL, а значит тонмаппинг (шаг 6) не может сжать
 * PQ в 500 нит гарнитуры иначе как «на глаз» — отсюда вымытая картинка,
 * с которой и начался проект.
 *
 * Раскладка — CTA-861.3, Static Metadata Descriptor Type 1, всё little-endian:
 *
 *   [0]      тип дескриптора, всегда 0
 *   [1..12]  праймериз R.x R.y G.x G.y B.x B.y   — единицы 0.00002 (×50000)
 *   [13..16] белая точка W.x W.y                  — единицы 0.00002
 *   [17..18] max display luminance                — кд/м²
 *   [19..20] min display luminance                — единицы 0.0001 кд/м²
 *   [21..22] maxCLL                               — кд/м²
 *   [23..24] maxFALL                              — кд/м²
 *
 * Две грабли, каждая из которых даёт неверный цвет молча:
 *
 * 1. ПОРЯДОК ПРАЙМЕРИЗ. В HEVC SEI `mastering_display_colour_volume` они лежат
 *    как G,B,R. FFmpeg нормализует их в R,G,B (`mastering_display_metadata.h`:
 *    «in r, g, b order»), CTA-861.3 тоже ждёт R,G,B. Значит из FFmpeg —
 *    прямое копирование, но при чтении SEI напрямую порядок надо переставлять.
 *
 * 2. ЕДИНИЦЫ МИНИМАЛЬНОЙ ЯРКОСТИ. CTA-861.3 требует 0.0001 кд/м².
 *    `MatroskaExtractor` из ExoPlayer кладёт значение в кд/м² как есть, поэтому
 *    типичные 0.005 нит превращаются в 0 — и min-яркость мастеринга теряется.
 */
#pragma once

#include <cstdint>

struct AVMasteringDisplayMetadata;
struct AVContentLightMetadata;

namespace ddd {

/** Размер `hdr-static-info` по CTA-861.3. Дублируется в `EngineColorInfo`. */
constexpr int kHdrStaticInfoSize = 25;

/**
 * Собирает блок из метаданных FFmpeg.
 *
 * @param mastering `AV_PKT_DATA_MASTERING_DISPLAY_METADATA`, может быть null.
 * @param light     `AV_PKT_DATA_CONTENT_LIGHT_LEVEL`, может быть null.
 * @param out       ровно [kHdrStaticInfoSize] байт, обнуляется целиком.
 * @return true, если хоть одно поле заполнено; false — блок пустой и его не
 *         надо отдавать в `MediaFormat` (пустой блок хуже отсутствующего:
 *         декодер поверит нулевой пиковой яркости).
 */
bool BuildHdrStaticInfo(const AVMasteringDisplayMetadata *mastering,
                        const AVContentLightMetadata *light,
                        uint8_t out[kHdrStaticInfoSize]);

/**
 * Самопроверка на каноническом HDR10 (BT.2020, D65, 1000/0.005 нит,
 * maxCLL 1000, maxFALL 400). Побайтово сверяется с посчитанным вручную
 * эталоном `00488a08393421aa9b9619fc08133d4240e8033200e8039001`.
 *
 * @return true — совпало.
 */
bool SelfTestHdrStaticInfo();

}  // namespace ddd
