/*
 * hdr_static_info.cpp — реализация сборки 25 байт `hdr-static-info`.
 *
 * Раскладка, единицы и обе грабли описаны в `hdr_static_info.h`. Здесь только
 * арифметика, и она проверяется [SelfTestHdrStaticInfo] побайтово: посчитать
 * эти байты «примерно» нельзя — ошибка даёт не чёрный экран, а слегка неверный
 * цвет, который потом полдня списывают на тонмаппинг.
 *
 * Логика перенесена из `native/tests/ffmpeg_smoke.c`, где она уже прошла проверку
 * на Pixel 6 (шаг 2, см. UNIFIED-ENGINE.md §6.1) — переносится вместе с тестом,
 * а не переписывается заново.
 */
#include "hdr_static_info.h"

#include <cmath>
#include <cstring>

#include "ddd_log.h"
#include "ff_include.h"

namespace ddd {
namespace {

/** uint16 little-endian с клампом: CTA-861.3 других вариантов не предусматривает. */
void Put16Le(uint8_t *p, long value) {
    if (value < 0) value = 0;
    if (value > 0xFFFF) value = 0xFFFF;
    p[0] = static_cast<uint8_t>(value & 0xFF);
    p[1] = static_cast<uint8_t>((value >> 8) & 0xFF);
}

}  // namespace

bool BuildHdrStaticInfo(const AVMasteringDisplayMetadata *mastering,
                        const AVContentLightMetadata *light,
                        uint8_t out[kHdrStaticInfoSize]) {
    std::memset(out, 0, kHdrStaticInfoSize);
    bool any = false;

    if (mastering != nullptr && mastering->has_primaries) {
        // Порядок в AVMasteringDisplayMetadata — R,G,B (FFmpeg уже переставил его
        // из G,B,R, как лежит в HEVC SEI и в MP4-боксе `mdcv`). CTA-861.3 ждёт
        // тоже R,G,B, поэтому здесь прямое копирование без перестановки.
        for (int i = 0; i < 3; ++i) {
            Put16Le(out + 1 + i * 4 + 0,
                    std::lround(av_q2d(mastering->display_primaries[i][0]) * 50000));
            Put16Le(out + 1 + i * 4 + 2,
                    std::lround(av_q2d(mastering->display_primaries[i][1]) * 50000));
        }
        Put16Le(out + 13, std::lround(av_q2d(mastering->white_point[0]) * 50000));
        Put16Le(out + 15, std::lround(av_q2d(mastering->white_point[1]) * 50000));
        any = true;
    }

    if (mastering != nullptr && mastering->has_luminance) {
        Put16Le(out + 17, std::lround(av_q2d(mastering->max_luminance)));
        // Единицы 0.0001 кд/м² — именно здесь ExoPlayer теряет min-яркость.
        Put16Le(out + 19, std::lround(av_q2d(mastering->min_luminance) * 10000));
        any = true;
    }

    if (light != nullptr) {
        Put16Le(out + 21, light->MaxCLL);
        Put16Le(out + 23, light->MaxFALL);
        any = true;
    }

    return any;
}

bool SelfTestHdrStaticInfo() {
    AVMasteringDisplayMetadata m;
    std::memset(&m, 0, sizeof m);
    m.has_primaries = 1;
    m.has_luminance = 1;
    // BT.2020: R .708/.292, G .170/.797, B .131/.046; белая точка D65.
    m.display_primaries[0][0] = AVRational{708, 1000};
    m.display_primaries[0][1] = AVRational{292, 1000};
    m.display_primaries[1][0] = AVRational{170, 1000};
    m.display_primaries[1][1] = AVRational{797, 1000};
    m.display_primaries[2][0] = AVRational{131, 1000};
    m.display_primaries[2][1] = AVRational{46, 1000};
    m.white_point[0] = AVRational{3127, 10000};
    m.white_point[1] = AVRational{3290, 10000};
    m.max_luminance = AVRational{1000, 1};
    m.min_luminance = AVRational{5, 1000};  // 0.005 кд/м²

    AVContentLightMetadata l;
    std::memset(&l, 0, sizeof l);
    l.MaxCLL = 1000;
    l.MaxFALL = 400;

    uint8_t got[kHdrStaticInfoSize];
    const bool any = BuildHdrStaticInfo(&m, &l, got);

    static const uint8_t kWant[kHdrStaticInfoSize] = {
        0x00,                    // тип дескриптора
        0x48, 0x8a, 0x08, 0x39,  // R: 35400 / 14600
        0x34, 0x21, 0xaa, 0x9b,  // G:  8500 / 39850
        0x96, 0x19, 0xfc, 0x08,  // B:  6550 /  2300
        0x13, 0x3d, 0x42, 0x40,  // W: 15635 / 16450
        0xe8, 0x03,              // max 1000 кд/м²
        0x32, 0x00,              // min 50 × 0.0001 кд/м²
        0xe8, 0x03,              // maxCLL 1000
        0x90, 0x01               // maxFALL 400
    };

    const bool equal = std::memcmp(got, kWant, kHdrStaticInfoSize) == 0;
    if (!any || !equal) {
        char hex[kHdrStaticInfoSize * 2 + 1] = {0};
        for (int i = 0; i < kHdrStaticInfoSize; ++i) snprintf(hex + i * 2, 3, "%02x", got[i]);
        DDD_LOGE("hdr-static-info: самотест провален, получено %s", hex);
        for (int i = 0; i < kHdrStaticInfoSize; ++i) {
            if (got[i] != kWant[i])
                DDD_LOGE("  байт %2d: %02x вместо %02x", i, got[i], kWant[i]);
        }
        return false;
    }

    DDD_LOGI("hdr-static-info: самотест пройден (25 Б совпали побайтово)");
    return true;
}

}  // namespace ddd
