/*
 * video_renderer.h — вывод декодированного кадра на GL-поверхность.
 *
 * Единственный путь вывода в движке (см. UNIFIED-ENGINE.md §4): и плоский
 * SurfaceView, и VR-слой, и снимок кадра идут через него. Шаг 4 закрыл
 * 8-битный SDR, шаг 5 — 10/12/16 бит; шаг 6 добавляет тонмаппинг, шаг 11 —
 * проекции. Поэтому здесь разведены «загрузка кадра в текстуры» и «рисование»:
 * менять предстоит первое, а геометрия, поворот и вписывание в окно остаются.
 *
 * Почему конверсия YUV→RGB своя, а не `swscale` в RGBA с последующей заливкой:
 *  - swscale на 4K это ~30 мс на кадр на CPU, то есть заведомый провал 24 fps;
 *  - 10-битный путь шага 5 всё равно требует шейдера (P010 в RGBA8 не влезает);
 *  - тонмаппинг шага 6 обязан работать по яркости ДО матрицы, а не после.
 *
 * Проверяемость: рендерер не знает про EGL и не создаёт контекст. Ему нужен
 * лишь текущий GL-контекст в вызывающем потоке, поэтому один и тот же код
 * рисует и в окно, и в pbuffer, откуда тест читает пиксели `glReadPixels`.
 */
#pragma once

#include "tone_map.h"

#include <cstdint>

namespace ddd {

struct DoviFrameMapping;

/**
 * Раскладка кадра в памяти. Подмножество `AVPixelFormat`, ровно то, что отдают
 * наши два источника кадров: `AMediaCodec` (шаг 5) и libavcodec (шаг 7).
 *
 * 10/12/16-битные форматы перечислены по осям «глубина × прореживание», потому
 * что в 4XVR это были семь отдельных функций `convertdatayuv`
 * (`ConvertData_YUV444P10LE_10Bit` и родня): там на каждую комбинацию
 * приходилась своя NEON-упаковка. Здесь упаковки нет вовсе — 16-битные
 * плоскости уходят в текстуру как есть, а глубина и прореживание становятся
 * двумя числами в [FormatInfo]. Это и есть переписанные семь функций: работы у
 * CPU не осталось, осталось описание формата.
 */
enum class FramePixelFormat : int {
    /** Три плоскости 8 бит: планарный выход libavcodec для H.264/HEVC 8 бит. */
    kYuv420p = 0,
    /** Y + перемежённые UV, 8 бит: `COLOR_FormatYUV420SemiPlanar` у MediaCodec. */
    kNv12 = 1,
    /** То же, но порядок VU: формат Adreno на части устройств. */
    kNv21 = 2,
    /** Планарные 4:2:0, значащие биты в младших разрядах 16-битного слова. */
    kYuv420p10le = 3,
    kYuv420p12le = 4,
    kYuv420p16le = 5,
    /** Планарные 4:2:2 — прореживание только по горизонтали. */
    kYuv422p10le = 6,
    kYuv422p12le = 7,
    kYuv422p16le = 8,
    /** Планарные 4:4:4 — цветность в полном разрешении. */
    kYuv444p10le = 9,
    kYuv444p12le = 10,
    kYuv444p16le = 11,
    /**
     * `COLOR_FormatYUVP010` у MediaCodec (он же `AV_PIX_FMT_P010LE`): Y +
     * перемежённые UV по 16 бит, но **значащие 10 бит в старших разрядах**.
     * Спутать с `kYuv420p10le` — получить картинку в 64 раза темнее, поэтому
     * выравнивание вынесено отдельным полем [FormatInfo::msb_aligned], а не
     * зашито в «10 бит».
     */
    kP010 = 12
};

/**
 * Свойства формата: всё, что нужно и загрузке в текстуры, и шейдеру.
 *
 * Вынесено в таблицу (`DescribeFormat`), а не в `switch` по месту: развилок по
 * формату четыре (размер текстур, internal format, множитель в шейдере, флаги),
 * и рассыпанные по коду они расходятся при добавлении формата.
 */
struct FormatInfo {
    /** Значащих бит на отсчёт: 8, 10, 12 или 16. */
    int bit_depth = 8;
    /** Прореживание цветности: 4:2:0 → (2,2), 4:2:2 → (2,1), 4:4:4 → (1,1). */
    int sub_x = 2;
    int sub_y = 2;
    /** Y отдельно, UV перемежены в одной плоскости. */
    bool semiplanar = false;
    /** Порядок VU вместо UV (NV21). */
    bool swap_uv = false;
    /** Значащие биты в старших разрядах 16-битного слова (P010). */
    bool msb_aligned = false;

    bool sixteen_bit() const { return bit_depth > 8; }
    /**
     * Множитель, приводящий выборку текстуры к нормализованному коду [0,1].
     *
     * Текстура отдаёт `code / 65535` (или `code << (16-depth) / 65535` для
     * P010), а матрице конверсии нужен `code / (2^depth - 1)`.
     *
     * Границы ограниченного диапазона при этом НЕ инвариантны к глубине, хотя
     * выглядят таковыми: 16/255 = 0.062745, а 64/1023 = 0.062561. Причина в том,
     * что по ITU-R границы масштабируются как 2^(n-8), а нормировка идёт на
     * 2^n − 1. Поэтому `BuildColorMatrix` принимает глубину, а не считает её
     * равной восьми (по цветности разница доходит до 2.7 LSB десятибитного кода).
     */
    float sample_scale() const {
        if (bit_depth <= 8) return 1.f;
        const int max_code = (1 << bit_depth) - 1;
        const int stored = msb_aligned ? (max_code << (16 - bit_depth)) : max_code;
        return 65535.f / static_cast<float>(stored);
    }
};

FormatInfo DescribeFormat(FramePixelFormat format);

/** Матрица конверсии. Значения совпадают с `EngineColorInfo.ColorStandard`. */
enum class ColorStandard : int {
    kBt601 = 0,
    kBt709 = 1,
    kBt2020 = 2
};

/**
 * Описание кадра для загрузки. Данные не копируются и должны жить до конца
 * вызова [UploadFrame].
 */
struct FrameDesc {
    const uint8_t *plane[3] = {nullptr, nullptr, nullptr};
    /**
     * Байт на строку каждой плоскости.
     *
     * Это не `width`: декодеры выравнивают строки (libavcodec на 32/64 байта,
     * MediaCodec — как захочет драйвер). Загрузка без учёта stride даёт
     * знаменитую «косую» картинку со сдвигом строк, поэтому здесь он
     * обязателен, а не опционален.
     */
    int stride[3] = {0, 0, 0};
    int width = 0;
    int height = 0;
    FramePixelFormat format = FramePixelFormat::kYuv420p;
    ColorStandard standard = ColorStandard::kBt709;
    /** true — full range (JPEG/PC, 0..255); false — limited (TV, 16..235). */
    bool full_range = false;
};

/** Как вписывать кадр в окно. */
enum class ScaleMode : int {
    /** Целиком, с чёрными полями: пропорции сохранены. */
    kFit = 0,
    /** Заполнить окно, обрезав лишнее: пропорции сохранены. */
    kFill = 1,
    /** Растянуть на всё окно: пропорции не сохранены. */
    kStretch = 2
};

/** Как 16-битные плоскости попадают в текстуру. */
enum class UploadPath : int {
    /** 8 бит: `GL_R8`/`GL_RG8`, без пересчёта. */
    kByte = 0,
    /**
     * `GL_R16_EXT`/`GL_RG16_EXT` из `GL_EXT_texture_norm16`: 16-битные плоскости
     * уходят в текстуру как есть, фильтрация цветности работает.
     */
    kNorm16 = 1,
    /**
     * Когда `GL_EXT_texture_norm16` нет: те же байты залиты как
     * `GL_RG8`/`GL_RGBA8` и склеены обратно в шейдере. CPU так же не участвует,
     * но фильтровать нельзя (интерполяция младшего байта на границе даёт
     * мусор), поэтому цветность принудительно `GL_NEAREST`.
     *
     * Название «резерв» осталось от первоначального предположения и не отражает
     * действительность: на Pixel 6 (Mali-G78, драйвер r54p1) расширения нет, и
     * этот путь — единственный. То есть по умолчанию 10-битная цветность 4:2:0
     * размножается повтором, а не интерполяцией; на градиентах это заметно, и
     * шаг 6 обязан либо найти фильтруемый формат, либо делать билинейную выборку
     * цветности вручную (четыре отсчёта — склейка байт от фильтрации не
     * страдает, страдает только фильтрация ДО склейки).
     *
     * Это осознанная замена подходу 4XVR: там резервный путь упаковывал YUV в
     * `RGB10_A2` на CPU (`ConvertData_*_10Bit`), то есть писал 4 байта на
     * пиксель — на 4K это 33 МБ на кадр только на запись, ~8 мс memcpy. Замер
     * этого пути на 4K: 6.98 мс на кадр вместе с отрисовкой и `glFinish`.
     */
    kBytePair = 2
};

class VideoRenderer {
public:
    /**
     * Собирает шейдеры и создаёт текстуры. Требует текущего GL-контекста.
     * @return nullptr, если шейдер не собрался (лог компиляции уйдёт в logcat).
     */
    static VideoRenderer *Create();

    ~VideoRenderer();

    VideoRenderer(const VideoRenderer &) = delete;
    VideoRenderer &operator=(const VideoRenderer &) = delete;

    /** Загружает кадр в текстуры. Рисования не делает. */
    bool UploadFrame(const FrameDesc &frame);

    /** Обновляет динамические DV LUT текущего кадра; nullptr включает HDR10 fallback. */
    void SetDolbyMapping(const DoviFrameMapping *mapping);

    /**
     * Параметры HDR-тонмаппинга (шаг 6).
     *
     * Задаются на файл, а не на кадр: mastering display и maxCLL описывают весь
     * поток. По умолчанию `kSdr` — то есть путь шага 4 бит-в-бит, пока источник
     * не объявил PQ или HLG.
     */
    void SetHdrParams(const HdrParams &params);

    const HdrParams &hdr_params() const { return hdr_; }

    /**
     * Рисует последний загруженный кадр в текущий framebuffer.
     *
     * @param viewport_w/h размер поверхности в пикселях.
     * @param rotation     0/90/180/270 из display matrix контейнера.
     * @param mode         вписывание.
     * @return false, если кадр ещё не загружен.
     */
    bool Draw(int viewport_w, int viewport_h, int rotation, ScaleMode mode);

    /**
     * Пиксельные пропорции (`sar_num/sar_den`); по умолчанию 1.0.
     *
     * Отдельно от размеров кадра, потому что анаморфное видео (DVD 720×576 с
     * SAR 64:45) при честном 1:1 выглядит сплющенным, и это не «так снято».
     */
    void SetPixelAspectRatio(float par);

    /**
     * Фильтр интерполяции цветности: true (по умолчанию) — линейный, false —
     * ближайший сосед.
     *
     * Настройка нужна для проверяемости. В 4:2:0 на каждый блок 2×2 приходится
     * один отсчёт цветности, и способ его размножения — это выбор, а не
     * арифметика: линейный фильтр смешивает соседние блоки, `SWS_POINT` в
     * swscale их повторяет. Сверять шейдер с эталоном имеет смысл только при
     * одинаковом способе, поэтому тест цвета ставит здесь false и получает
     * побитово сравнимый результат, а воспроизведение остаётся на линейном —
     * он заметно лучше на градиентах и лицах.
     */
    void SetChromaFilter(bool linear);

    /**
     * Запретить `GL_EXT_texture_norm16` и уйти на путь [kBytePair].
     *
     * Нужно для проверяемости: без переключателя выполнялся бы только тот путь,
     * который выбрал GPU, а второй впервые запустился бы у пользователя. Замер
     * показал, что на Pixel 6 расширения нет вовсе, так что по факту
     * переключатель сейчас нужен для обратного случая — на GPU, где расширение
     * есть, он даёт сравнить оба пути на одном кадре.
     */
    void SetForceBytePair(bool force);

    /** Каким путём залит последний кадр. */
    UploadPath upload_path() const { return upload_path_; }

    /** Есть ли `GL_EXT_texture_norm16` в этом контексте. */
    bool has_norm16() const { return has_norm16_; }

    int frame_width() const { return frame_width_; }
    int frame_height() const { return frame_height_; }

private:
    VideoRenderer() = default;

    bool BuildProgram();
    void EnsureTextures(const FrameDesc &frame);
    /**
     * Заливает одну плоскость с учётом stride.
     *
     * @param channels 1 (Y, U, V по отдельности) или 2 (перемежённые UV).
     * @param sixteen  16-битные отсчёты; выбор между [kNorm16] и [kBytePair]
     *                 делается по [upload_path_].
     */
    void UploadPlane(int index, const uint8_t *data, int stride, int width, int height, int channels,
                     bool sixteen);

    unsigned program_ = 0;
    unsigned vbo_ = 0;
    unsigned vao_ = 0;
    unsigned texture_[3] = {0, 0, 0};
    unsigned dovi_texture_2d_[3] = {0, 0, 0};
    unsigned dovi_texture_3d_[3] = {0, 0, 0};

    int u_transform_ = -1;
    int u_color_matrix_ = -1;
    int u_color_offset_ = -1;
    int u_is_semiplanar_ = -1;
    int u_swap_uv_ = -1;
    int u_sample_scale_ = -1;
    int u_sample_mode_ = -1;
    int u_transfer_ = -1;
    int u_tone_map_mode_ = -1;
    int u_hdr_curve_ = -1;
    int u_hdr_display_ = -1;
    int u_gamut_ = -1;
    int u_tex_y_ = -1;
    int u_tex_u_ = -1;
    int u_tex_v_ = -1;
    int u_dovi_active_ = -1;
    int u_dovi_kind_ = -1;
    int u_dovi_nonlinear_ = -1;
    int u_dovi_offset_ = -1;
    int u_dovi_linear_ = -1;
    int u_dovi_tex_2d_[3] = {-1, -1, -1};
    int u_dovi_tex_3d_[3] = {-1, -1, -1};

    int frame_width_ = 0;
    int frame_height_ = 0;
    FramePixelFormat frame_format_ = FramePixelFormat::kYuv420p;
    FormatInfo frame_info_;
    ColorStandard frame_standard_ = ColorStandard::kBt709;
    bool frame_full_range_ = false;
    /** Ширина, реально залитая в текстуру: нужна, чтобы не менять её размер зря. */
    int texture_width_[3] = {0, 0, 0};
    int texture_height_[3] = {0, 0, 0};
    float par_ = 1.f;
    bool chroma_linear_ = true;
    bool has_frame_ = false;
    bool has_norm16_ = false;
    bool force_byte_pair_ = false;
    bool dovi_active_ = false;
    uint64_t dovi_hash_ = 0;
    int dovi_kind_[3] = {0, 0, 0};
    float dovi_nonlinear_[9] = {1.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 1.f};
    float dovi_offset_[3] = {0.f, 0.f, 0.f};
    float dovi_linear_[9] = {1.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 1.f};

    HdrParams hdr_;
    ToneMapUniforms tone_;
    UploadPath upload_path_ = UploadPath::kByte;
};

}  // namespace ddd
