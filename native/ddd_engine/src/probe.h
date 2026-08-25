/*
 * probe.h — разбор открытого контейнера в модель дорожек движка.
 *
 * Это native-половина того, что в Kotlin становится `EngineTracks`,
 * `EngineVideoFormat` и `EngineColorInfo`. Здесь же собирается `hdr-static-info`:
 * метаданные HDR обязаны появиться на этом этапе, потому что HW-декодеру их надо
 * отдать в `configure`, то есть до первого кадра, а не после.
 *
 * Границы этапа: пробинг ничего не декодирует. Всё, что здесь есть, взято из
 * `AVCodecParameters` и side data контейнера.
 *
 * Именно поэтому HDR10+ здесь всегда false: его метаданные лежат в SEI каждого
 * кадра (`AV_FRAME_DATA_DYNAMIC_HDR_PLUS`), а не в заголовке потока, и появятся
 * на шаге 6 вместе с динамическим тонмаппингом. Статический HDR10, HLG и
 * конфигурация Dolby Vision, наоборот, живут в контейнере и доступны сразу.
 */
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "hdr_static_info.h"

struct AVFormatContext;
struct AVStream;

namespace ddd {

/**
 * Значения повторяют порядок `enum class StereoLayout` в `EngineGeometry.kt`:
 * по JNI передаётся ordinal, поэтому порядок — часть контракта.
 */
enum class StereoLayout : int {
    kMono = 0,
    kSideBySide = 1,
    kOverUnder = 2,
    kMvHevc = 3,
    kSsif = 4
};

/** Порядок повторяет `enum class Projection` в `EngineGeometry.kt`. */
enum class Projection : int {
    kFlat = 0,
    kCurved = 1,
    kEquirect180 = 2,
    kEquirect360 = 3,
    kFisheye = 4,
    kCubemap = 5
};

struct ProbeContainer {
    std::string format;     ///< короткое имя демуксера: `mov,mp4,m4a,...`
    std::string long_name;
    int64_t duration_us = 0;  ///< 0 для live/неизвестной длительности
    int64_t bitrate = 0;
    bool seekable = false;
};

struct ProbeVideoTrack {
    int stream_index = -1;
    int width = 0;
    int height = 0;
    int sar_num = 0;  ///< 0 — квадратный пиксель не заявлен
    int sar_den = 1;
    float frame_rate = 0.f;
    int bitrate = 0;
    std::string codec;
    std::string mime;      ///< пусто — HW-пути нет, только libavcodec
    int profile = 0;
    int level = 0;
    int rotation = 0;      ///< 0/90/180/270 из display matrix
    int bit_depth = 8;
    bool is_default = false;
};

struct ProbeAudioTrack {
    int stream_index = -1;
    std::string codec;
    std::string profile;     ///< `DTS-HD MA`, `TrueHD`: важно для подписи дорожки
    int channels = 0;
    int sample_rate = 0;
    int bitrate = 0;
    std::string language;
    std::string title;
    bool is_default = false;
    bool is_forced = false;
};

struct ProbeSubtitleTrack {
    int stream_index = -1;
    std::string codec;
    std::string language;
    std::string title;
    bool is_default = false;
    bool is_forced = false;
    /** PGS/SUP/VobSub — картинка, а не текст: рендерится как отдельная текстура. */
    bool is_bitmap = false;
};

struct ProbeColorInfo {
    int color_standard = 0;
    int color_transfer = 0;
    int color_range = 0;
    int bit_depth = 8;
    int dolby_profile = 0;
    /**
     * Поток, в котором найдена конфигурация Dolby Vision; -1, если её нет.
     *
     * Нужен отдельно от [dolby_profile], потому что у профиля 7 (двухслойный
     * BL+EL) бокс `dvvC` лежит на ВТОРОМ видеопотоке — в MP4 это отдельная
     * дорожка enhancement layer. Если смотреть только основной поток, файл
     * выглядит как обычный HDR10, и признак DV теряется молча.
     */
    int dolby_stream_index = -1;
    bool has_hdr10_plus = false;
    bool has_static_info = false;
    uint8_t static_info[kHdrStaticInfoSize] = {0};
};

struct ProbeResult {
    ProbeContainer container;
    std::vector<ProbeVideoTrack> video;
    std::vector<ProbeAudioTrack> audio;
    std::vector<ProbeSubtitleTrack> subtitle;

    /** Цвет основного видеопотока ([best_video_index]). */
    ProbeColorInfo color;

    StereoLayout stereo = StereoLayout::kMono;
    Projection projection = Projection::kFlat;

    /** `av_find_best_stream` для видео; -1, если видео нет (аудиофайл). */
    int best_video_index = -1;
    int best_audio_index = -1;
    int best_subtitle_index = -1;
};

/**
 * Разбирает уже открытый контекст. `avformat_find_stream_info` вызывается внутри.
 *
 * Пропускаются:
 *  - `AVMEDIA_TYPE_DATA` и `AVMEDIA_TYPE_ATTACHMENT` — у них нет декодера по
 *    определению. В MP4 с камеры Pixel таких потоков три (motion photo, гироскоп),
 *    и без фильтра в меню дорожек появились бы пустые строки (проверено на шаге 2);
 *  - видеопотоки с `AV_DISPOSITION_ATTACHED_PIC` — это обложка альбома, а не видео.
 *
 * @return false, если контейнер не удалось разобрать.
 */
bool Probe(AVFormatContext *fc, ProbeResult *out);

}  // namespace ddd
