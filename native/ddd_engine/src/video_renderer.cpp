/*
 * video_renderer.cpp — реализация (см. video_renderer.h).
 */
#include "video_renderer.h"

#include "tone_map.h"
#include "dovi_rpu_parser.h"

#include <GLES3/gl3.h>

#include <cmath>
#include <cstring>

#include "ddd_log.h"
#include "gl_util.h"

// Из GL_EXT_texture_norm16. В GLES3/gl3.h их нет (это расширение, а не ядро ES 3),
// а тянуть сюда GLES2/gl2ext.h ради двух чисел — лишняя зависимость.
#ifndef GL_R16_EXT
#define GL_R16_EXT 0x822A
#endif
#ifndef GL_RG16_EXT
#define GL_RG16_EXT 0x822C
#endif

namespace ddd {

namespace {

constexpr const char *kVertexShader = R"(#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;
uniform mat4 uTransform;
out vec2 vTexCoord;
void main() {
    gl_Position = uTransform * vec4(aPosition, 0.0, 1.0);
    vTexCoord = aTexCoord;
}
)";

/*
 * highp, а не mediump: mediump на многих мобильных GPU — это fp16 с 10-битной
 * мантиссой, и после матрицы конверсии остаётся ~1 LSB шума на 8-битном выходе.
 * На 10-битном пути fp16 съел бы ровно те два бита, за которыми вся работа:
 * 1023 в fp16 представимо точно, а вот результат умножения на матрицу — уже нет.
 * Разницы в скорости на конверсии YUV нет: узкое место — выборка текстур.
 */
constexpr const char *kFragmentShader = R"(#version 300 es
precision highp float;
precision highp sampler3D;
uniform sampler2D uTexY;
uniform sampler2D uTexU;
uniform sampler2D uTexV;
uniform mat3 uColorMatrix;
uniform vec3 uColorOffset;
uniform float uSampleScale;
uniform int uSampleMode;
uniform int uSemiplanar;
uniform int uSwapUv;

// Per-frame Dolby Vision reshaping generated from the RPU.
uniform int uDoviActive;
uniform ivec3 uDoviKind;
uniform mat3 uDoviNonlinear;
uniform vec3 uDoviOffset;
uniform mat3 uDoviLinear;
uniform sampler2D uDoviI2d;
uniform sampler2D uDoviCt2d;
uniform sampler2D uDoviCp2d;
uniform sampler3D uDoviI3d;
uniform sampler3D uDoviCt3d;
uniform sampler3D uDoviCp3d;

// ─── HDR (шаг 6) ───
// 0 SDR, 1 PQ, 2 HLG. Ветвление по uniform: все фрагменты идут одной дорогой,
// расхождения варпа нет, и SDR-путь остаётся ровно таким, каким был на шаге 4.
uniform int uTransfer;
// 0 — стандартный BT.2390/HLG; 1 — HDR-модификатор 4XVR для DV.
uniform int uToneMapMode;
// x: PQ-код пика источника, y: PQ-код пика панели, z: колено сплайна BT.2390,
// w: нормировка светового потока на пик панели.
uniform vec4 uHdrCurve;
// x: регулятор яркости, y: системная гамма HLG минус 1, z: обратная гамма панели.
uniform vec3 uHdrDisplay;
// BT.2020 → BT.709; единичная, когда перевод не нужен.
uniform mat3 uGamut;

in vec2 vTexCoord;
out vec4 fragColor;

// Склейка 16-битного отсчёта из двух 8-битных каналов: младший байт в .r,
// старший в .g — так он и лежит в памяти little-endian декодера. Множители
// подобраны так, чтобы результат был точно code/65535, без накопления ошибки:
// 255/65535 * lo_norm восстанавливает младший байт, 65280/65535 — старший.
const vec2 kBytePair = vec2(255.0 / 65535.0, 65280.0 / 65535.0);

// Константы ST 2084 — дробями из стандарта: m2 = 2523/4096*128 = 78.84375 ровно.
// Записанное «78.84» сдвигает тени на несколько LSB.
const float kM1 = 2610.0 / 16384.0;
const float kM2 = 2523.0 / 4096.0 * 128.0;
const float kC1 = 3424.0 / 4096.0;
const float kC2 = 2413.0 / 4096.0 * 32.0;
const float kC3 = 2392.0 / 4096.0 * 32.0;

const float kHlgA = 0.17883277;
const float kHlgB = 0.28466892;
const float kHlgC = 0.55991073;

// Коэффициенты яркости BT.2020 — строка Y матрицы RGB→XYZ при D65.
const vec3 kLumaBt2020 = vec3(0.2627002, 0.67799807, 0.05930172);

/** PQ-код → световой поток, 1.0 = 10000 кд/м². */
vec3 pqEotf(vec3 code) {
    vec3 p = pow(max(code, 0.0), vec3(1.0 / kM2));
    vec3 num = max(p - kC1, 0.0);
    vec3 den = max(kC2 - kC3 * p, 1e-6);
    return pow(num / den, vec3(1.0 / kM1));
}

float fourXvrHable(float x) {
    const float A = 0.15;
    const float B = 0.50;
    const float C = 0.10;
    const float D = 0.20;
    const float E = 0.02;
    const float F = 0.30;
    return ((x * (A * x + C * B) + D * E) /
            (x * (A * x + B) + D * F)) - E / F;
}

vec3 fourXvrToneMap(vec3 pq) {
    // Ровно тот же порядок операций и те же коэффициенты, что в извлечённом
    // шейдере 4XVR. Его штатный HDR-регулятор 0.5 соответствует brightness=1.
    vec3 linear = pqEotf(clamp(pq, 0.0, 1.2));
    if (uDoviActive == 1) linear = uDoviLinear * linear;
    float lum = max(max(linear.r, linear.g), linear.b);
    float scale = fourXvrHable(lum) / max(lum, 1e-6);
    vec3 value = linear * scale * vec3(100.0, 93.0, 100.0) * 0.70;

    float slider = clamp(uHdrDisplay.x * 0.5, 0.0, 1.0);
    float saturationPower = 0.65 + 0.40 * slider;
    float gammaPower = 0.42 - 0.14 * slider;
    float smoothPower = 0.40 + 0.80 * max(slider - 0.5, 0.0);

    value = pow(max(value, 0.0), vec3(gammaPower));
    float vmax = max(value.r, max(value.g, value.b));
    value += max(value - vmax, vec3(-0.1)) * saturationPower;
    value = clamp(value, 0.0, 1.0);

    vec3 v2 = value * value;
    vec3 v3 = v2 * value;
    return clamp(v3 * (-2.0 * smoothPower) + v2 * (3.0 * smoothPower) +
                 value * (1.0 - smoothPower), 0.0, 1.0);
}

vec3 doviReshape(vec3 signal) {
    float i = uDoviKind.x == 2 ? texture(uDoviI3d, signal).r
                               : texture(uDoviI2d, vec2(signal.x, 0.5)).r;
    float ct = uDoviKind.y == 2 ? texture(uDoviCt3d, signal).r
                                : texture(uDoviCt2d, vec2(signal.y, 0.5)).r;
    float cp = uDoviKind.z == 2 ? texture(uDoviCp3d, signal).r
                                : texture(uDoviCp2d, vec2(signal.z, 0.5)).r;
    return vec3(i, ct, cp);
}

/** Обратная к pqEotf; нужна для одного скалярного значения — максимума каналов. */
float pqOetf(float luminance) {
    float p = pow(max(luminance, 0.0), kM1);
    return pow((kC1 + kC2 * p) / (1.0 + kC3 * p), kM2);
}

/**
 * BT.2390 EETF: сжатие в PQ-области кубическим сплайном Эрмита.
 *
 * Ниже колена — тождество: контент, который панель показывает без потерь,
 * проходит бит-в-бит. Именно это отличает кривую от Reinhard/Hable, сжимающих
 * весь диапазон и потому осветляющих то, что осветлять не просили.
 */
float eetf(float pq) {
    float srcMax = uHdrCurve.x;
    float ks = uHdrCurve.z;
    // Панель ярче контента: сжимать нечего.
    if (ks >= 1.0) return pq;

    float e1 = pq / max(srcMax, 1e-6);
    if (e1 < ks) return pq;

    float maxLum = uHdrCurve.y / max(srcMax, 1e-6);
    float t = (e1 - ks) / max(1.0 - ks, 1e-6);
    float t2 = t * t;
    float t3 = t2 * t;
    float e2 = (2.0 * t3 - 3.0 * t2 + 1.0) * ks +
               (t3 - 2.0 * t2 + t) * (1.0 - ks) +
               (-2.0 * t3 + 3.0 * t2) * maxLum;
    return e2 * srcMax;
}

void main() {
    // Все три плоскости выбираются безусловно: выборка внутри ветвления при
    // неоднородном управлении даёт неопределённый уровень детализации, а на
    // семиплоскостном кадре слот V — текстура 1x1, то есть попадание в кэш.
    vec4 sy = texture(uTexY, vTexCoord);
    vec4 s1 = texture(uTexU, vTexCoord);
    vec4 s2 = texture(uTexV, vTexCoord);

    float y;
    float u;
    float v;
    if (uSampleMode == 1) {
        y = dot(sy.rg, kBytePair);
        u = dot(s1.rg, kBytePair);
        v = (uSemiplanar == 1) ? dot(s1.ba, kBytePair) : dot(s2.rg, kBytePair);
    } else {
        y = sy.r;
        u = s1.r;
        v = (uSemiplanar == 1) ? s1.g : s2.r;
    }

    // Приведение к нормализованному коду глубины кадра: для 8 бит множитель 1.0,
    // для 10 бит в младших разрядах — 65535/1023, для P010 — 65535/65472.
    y *= uSampleScale;
    u *= uSampleScale;
    v *= uSampleScale;

    if (uSwapUv == 1) {
        float t = u;
        u = v;
        v = t;
    }
    vec3 signal = vec3(y, u, v);
    vec3 rgb = uDoviActive == 1
                   ? uDoviNonlinear * (doviReshape(clamp(signal, 0.0, 1.0)) - uDoviOffset)
                   : uColorMatrix * (signal - uColorOffset);

    // SDR: путь шага 4 без изменений — байт в байт, никакой лишней арифметики.
    if (uTransfer == 0) {
        fragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
        return;
    }

    if (uTransfer == 1 && uToneMapMode == 1) {
        fragColor = vec4(fourXvrToneMap(rgb), 1.0);
        return;
    }

    float brightness = uHdrDisplay.x;
    vec3 lin;
    if (uTransfer == 2) {
        // HLG: обратная OETF даёт СЦЕНУ, и её нужно провести через OOTF, иначе
        // картинка выйдет плоской. Системная гамма зависит от пика панели, то
        // есть HLG адаптивен к дисплею по построению — EETF ему не нужен.
        vec3 c = clamp(rgb, 0.0, 1.0);
        vec3 scene = mix((exp((c - kHlgC) / kHlgA) + kHlgB) / 12.0,
                         c * c / 3.0,
                         step(c, vec3(0.5)));
        float ys = max(dot(kLumaBt2020, scene), 1e-6);
        lin = scene * pow(ys, uHdrDisplay.y) * brightness;
    } else {
        // PQ абсолютен: код несёт яркость в кд/м², а не «яркость относительно
        // белого». Поэтому сначала — в световой поток, и только там кривая.
        lin = pqEotf(clamp(rgb, 0.0, 1.0)) * brightness;

        // Кривая считается по максимуму каналов, а множитель применяется ко всем
        // трём. Поканальная кривая тянула бы яркие цвета к серому — закат терял
        // бы насыщенность там, где она заметнее всего.
        float lmax = max(max(lin.r, lin.g), lin.b);
        if (lmax > 1e-6) {
            float mapped = pqEotf(vec3(eetf(pqOetf(lmax)))).r;
            lin *= mapped / lmax;
        }
        lin *= uHdrCurve.w;
    }

    // Гамут — в линейном свете и ДО отсечения: BT.2020 шире BT.709, цвета вне
    // гамута дают отрицательные компоненты, и отсечение раньше перевода испортило
    // бы в том числе те цвета, которые в BT.709 попадают.
    vec3 disp = clamp(uGamut * lin, 0.0, 1.0);

    // Обратная гамма панели. Композитор в ColorMode::NATIVE трактует кадр как
    // sRGB-подобный, поэтому кодировать обязаны мы — иначе линейный свет уйдёт
    // на экран как гамма-кодированный и даст провал в тенях.
    fragColor = vec4(pow(disp, vec3(uHdrDisplay.z)), 1.0);
}
)";

/**
 * Квад в NDC с текстурными координатами.
 *
 * Строка 0 кадра — верх изображения, а верх окна в NDC это y = +1, поэтому
 * t = 0 сопоставлено с y = +1. Перепутанный здесь знак даёт перевёрнутое
 * изображение — ошибку, которую невозможно не заметить, но легко «починить»
 * не там, где она сделана.
 */
constexpr float kQuad[] = {
    // x,     y,     s,    t
    -1.f,  1.f,  0.f, 0.f,
    -1.f, -1.f,  0.f, 1.f,
     1.f,  1.f,  1.f, 0.f,
     1.f, -1.f,  1.f, 1.f,
};

/** Коэффициенты яркости Kr, Kb по стандарту; Kg = 1 - Kr - Kb. */
void LumaCoefficients(ColorStandard standard, float *kr, float *kb) {
    switch (standard) {
        case ColorStandard::kBt601:
            *kr = 0.299f;
            *kb = 0.114f;
            break;
        case ColorStandard::kBt2020:
            *kr = 0.2627f;
            *kb = 0.0593f;
            break;
        case ColorStandard::kBt709:
        default:
            *kr = 0.2126f;
            *kb = 0.0722f;
            break;
    }
}

/**
 * Матрица YCbCr→RGB (column-major для GLSL) и смещение.
 *
 * В шейдере считается `rgb = M * (yuv - offset)`. Масштаб диапазона вложен в M,
 * а не передан отдельным uniform: одна матрица вместо матрицы и вектора — и, что
 * важнее, на шаге 6 в неё же войдёт матрица гамута BT.2020→BT.709, то есть
 * умножение всё равно должно быть одно.
 *
 * [bit_depth] обязателен, и это не мелочь. Соблазнительно считать, что в
 * нормализованных единицах глубина не участвует — «16/255 это то же, что
 * 64/1023». Это неверно: 16/255 = 0.062745, а 64/1023 = 0.062561. По ITU-R
 * границы ограниченного диапазона для глубины n равны 16·2^(n-8) и 235·2^(n-8),
 * а нормируется код на 2^n − 1, и 2^n − 1 ≠ (2^8 − 1)·2^(n-8). Расхождение
 * ничтожно по яркости (0.2 LSB десятибитного кода), но по цветности нейтраль
 * 128/255 против 512/1023 даёт 1.5 LSB, а после множителей матрицы — до 2.7 LSB
 * в красном и синем. То есть ровно тот порядок, который шаг 5 обязан различать.
 */
void BuildColorMatrix(ColorStandard standard, bool full_range, int bit_depth, float *m9,
                      float *offset3) {
    float kr = 0.f, kb = 0.f;
    LumaCoefficients(standard, &kr, &kb);
    const float kg = 1.f - kr - kb;

    // Каноническая обратная матрица при y ∈ [0,1], u,v ∈ [-0.5,0.5].
    const float r_v = 2.f * (1.f - kr);
    const float b_u = 2.f * (1.f - kb);
    const float g_u = -2.f * kb * (1.f - kb) / kg;
    const float g_v = -2.f * kr * (1.f - kr) / kg;

    // Границы диапазона в кодах глубины n: чёрный 16·2^(n-8), размах яркости
    // 219·2^(n-8), размах цветности 224·2^(n-8), нейтраль 128·2^(n-8) = 2^(n-1).
    // Нормируются на 2^n − 1, потому что именно так шейдер получает отсчёт из
    // текстуры (см. FormatInfo::sample_scale).
    const int depth = (bit_depth >= 8 && bit_depth <= 16) ? bit_depth : 8;
    const float step = static_cast<float>(1 << (depth - 8));
    const float max_code = static_cast<float>((1 << depth) - 1);

    float y_scale = 1.f, c_scale = 1.f;
    float y_offset = 0.f;
    if (!full_range) {
        y_offset = 16.f * step / max_code;
        y_scale = max_code / (219.f * step);
        c_scale = max_code / (224.f * step);
    }

    offset3[0] = y_offset;
    offset3[1] = 128.f * step / max_code;
    offset3[2] = 128.f * step / max_code;

    // Строки матрицы: R, G, B. Столбцы: y, u, v. Масштаб диапазона вложен в
    // соответствующий столбец.
    const float rows[3][3] = {
        {1.f * y_scale, 0.f * c_scale, r_v * c_scale},
        {1.f * y_scale, g_u * c_scale, g_v * c_scale},
        {1.f * y_scale, b_u * c_scale, 0.f * c_scale},
    };

    // GLSL mat3 — column-major: m9[col * 3 + row].
    for (int col = 0; col < 3; ++col) {
        for (int row = 0; row < 3; ++row) {
            m9[col * 3 + row] = rows[row][col];
        }
    }
}

/** Округление вверх при делении: для нечётных размеров кадра. */
inline int DivUp(int v, int d) { return (v + d - 1) / d; }

/**
 * Есть ли расширение в текущем контексте.
 *
 * Перебором `glGetStringi`, а не поиском подстроки в `glGetString(GL_EXTENSIONS)`:
 * в ядре ES 3 последний вызов вообще возвращает nullptr, и написанная «как в ES 2»
 * проверка молча решает, что расширений нет ни одного.
 */
bool HasGlExtension(const char *name) {
    GLint count = 0;
    glGetIntegerv(GL_NUM_EXTENSIONS, &count);
    for (GLint i = 0; i < count; ++i) {
        const char *ext = reinterpret_cast<const char *>(glGetStringi(GL_EXTENSIONS, static_cast<GLuint>(i)));
        if (ext != nullptr && strcmp(ext, name) == 0) return true;
    }
    return false;
}

}  // namespace

FormatInfo DescribeFormat(FramePixelFormat format) {
    FormatInfo info;
    switch (format) {
        case FramePixelFormat::kNv12:
            info.semiplanar = true;
            break;
        case FramePixelFormat::kNv21:
            info.semiplanar = true;
            info.swap_uv = true;
            break;
        case FramePixelFormat::kYuv420p10le:
            info.bit_depth = 10;
            break;
        case FramePixelFormat::kYuv420p12le:
            info.bit_depth = 12;
            break;
        case FramePixelFormat::kYuv420p16le:
            info.bit_depth = 16;
            break;
        case FramePixelFormat::kYuv422p10le:
            info.bit_depth = 10;
            info.sub_y = 1;
            break;
        case FramePixelFormat::kYuv422p12le:
            info.bit_depth = 12;
            info.sub_y = 1;
            break;
        case FramePixelFormat::kYuv422p16le:
            info.bit_depth = 16;
            info.sub_y = 1;
            break;
        case FramePixelFormat::kYuv444p10le:
            info.bit_depth = 10;
            info.sub_x = 1;
            info.sub_y = 1;
            break;
        case FramePixelFormat::kYuv444p12le:
            info.bit_depth = 12;
            info.sub_x = 1;
            info.sub_y = 1;
            break;
        case FramePixelFormat::kYuv444p16le:
            info.bit_depth = 16;
            info.sub_x = 1;
            info.sub_y = 1;
            break;
        case FramePixelFormat::kP010:
            info.bit_depth = 10;
            info.semiplanar = true;
            info.msb_aligned = true;
            break;
        case FramePixelFormat::kYuv420p:
        default:
            break;
    }
    return info;
}

VideoRenderer *VideoRenderer::Create() {
    VideoRenderer *self = new VideoRenderer();
    if (!self->BuildProgram()) {
        delete self;
        return nullptr;
    }
    return self;
}

bool VideoRenderer::BuildProgram() {
    program_ = BuildGlProgram(kVertexShader, kFragmentShader, "yuv2rgb");
    if (program_ == 0) return false;

    u_transform_ = glGetUniformLocation(program_, "uTransform");
    u_color_matrix_ = glGetUniformLocation(program_, "uColorMatrix");
    u_color_offset_ = glGetUniformLocation(program_, "uColorOffset");
    u_is_semiplanar_ = glGetUniformLocation(program_, "uSemiplanar");
    u_swap_uv_ = glGetUniformLocation(program_, "uSwapUv");
    u_sample_scale_ = glGetUniformLocation(program_, "uSampleScale");
    u_sample_mode_ = glGetUniformLocation(program_, "uSampleMode");
    u_transfer_ = glGetUniformLocation(program_, "uTransfer");
    u_tone_map_mode_ = glGetUniformLocation(program_, "uToneMapMode");
    u_hdr_curve_ = glGetUniformLocation(program_, "uHdrCurve");
    u_hdr_display_ = glGetUniformLocation(program_, "uHdrDisplay");
    u_gamut_ = glGetUniformLocation(program_, "uGamut");
    u_tex_y_ = glGetUniformLocation(program_, "uTexY");
    u_tex_u_ = glGetUniformLocation(program_, "uTexU");
    u_tex_v_ = glGetUniformLocation(program_, "uTexV");
    u_dovi_active_ = glGetUniformLocation(program_, "uDoviActive");
    u_dovi_kind_ = glGetUniformLocation(program_, "uDoviKind");
    u_dovi_nonlinear_ = glGetUniformLocation(program_, "uDoviNonlinear");
    u_dovi_offset_ = glGetUniformLocation(program_, "uDoviOffset");
    u_dovi_linear_ = glGetUniformLocation(program_, "uDoviLinear");
    const char *dovi_2d_names[3] = {"uDoviI2d", "uDoviCt2d", "uDoviCp2d"};
    const char *dovi_3d_names[3] = {"uDoviI3d", "uDoviCt3d", "uDoviCp3d"};
    for (int i = 0; i < 3; ++i) {
        u_dovi_tex_2d_[i] = glGetUniformLocation(program_, dovi_2d_names[i]);
        u_dovi_tex_3d_[i] = glGetUniformLocation(program_, dovi_3d_names[i]);
    }

    if (u_transform_ < 0 || u_color_matrix_ < 0 || u_tex_y_ < 0 || u_tex_u_ < 0 || u_tex_v_ < 0 ||
        u_sample_scale_ < 0 || u_sample_mode_ < 0) {
        DDD_LOGE("gl: не найдены uniform-ы программы yuv2rgb");
        return false;
    }

    has_norm16_ = HasGlExtension("GL_EXT_texture_norm16");
    DDD_LOGI("gl: GL_EXT_texture_norm16 %s", has_norm16_ ? "есть" : "нет, 16 бит через пару байт");

    glGenVertexArrays(1, &vao_);
    glGenBuffers(1, &vbo_);
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof kQuad, kQuad, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), nullptr);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                          reinterpret_cast<const void *>(2 * sizeof(float)));
    glBindVertexArray(0);

    glGenTextures(3, texture_);
    for (int i = 0; i < 3; ++i) {
        glBindTexture(GL_TEXTURE_2D, texture_[i]);
        // CLAMP_TO_EDGE обязателен: при REPEAT (значение по умолчанию для S/T в
        // GL это GL_REPEAT) правый край кадра подмешивает левый, и на градиентах
        // это видно полосой.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    }
    glBindTexture(GL_TEXTURE_2D, 0);

    // All six samplers stay complete even when DOVI is disabled. Some mobile
    // drivers validate sampler completeness before uniform branches execute.
    glGenTextures(3, dovi_texture_2d_);
    glGenTextures(3, dovi_texture_3d_);
    const float identity[2] = {0.f, 1.f};
    const float zero = 0.f;
    for (int i = 0; i < 3; ++i) {
        glBindTexture(GL_TEXTURE_2D, dovi_texture_2d_[i]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, 2, 1, 0, GL_RED, GL_FLOAT, identity);

        glBindTexture(GL_TEXTURE_3D, dovi_texture_3d_[i]);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage3D(GL_TEXTURE_3D, 0, GL_R32F, 1, 1, 1, 0, GL_RED, GL_FLOAT, &zero);
    }
    glBindTexture(GL_TEXTURE_2D, 0);
    glBindTexture(GL_TEXTURE_3D, 0);

    return CheckGlError("BuildProgram");
}

VideoRenderer::~VideoRenderer() {
    // Если контекст уже потерян, вызовы просто ничего не сделают — это не ошибка.
    if (texture_[0] != 0) glDeleteTextures(3, texture_);
    if (dovi_texture_2d_[0] != 0) glDeleteTextures(3, dovi_texture_2d_);
    if (dovi_texture_3d_[0] != 0) glDeleteTextures(3, dovi_texture_3d_);
    if (vbo_ != 0) glDeleteBuffers(1, &vbo_);
    if (vao_ != 0) glDeleteVertexArrays(1, &vao_);
    if (program_ != 0) glDeleteProgram(program_);
}

void VideoRenderer::SetPixelAspectRatio(float par) {
    par_ = (par > 0.f && std::isfinite(par)) ? par : 1.f;
}

void VideoRenderer::SetChromaFilter(bool linear) {
    chroma_linear_ = linear;
    // На резервном пути линейная фильтрация запрещена физически: текстура несёт
    // два байта одного отсчёта в разных каналах, и интерполяция младшего байта
    // между соседями даёт не промежуточное значение, а случайное.
    const bool allow_linear = linear && upload_path_ != UploadPath::kBytePair;
    const GLint filter = allow_linear ? GL_LINEAR : GL_NEAREST;
    for (int i = 1; i < 3; ++i) {
        if (texture_[i] == 0) continue;
        glBindTexture(GL_TEXTURE_2D, texture_[i]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    }
    glBindTexture(GL_TEXTURE_2D, 0);
}

void VideoRenderer::SetForceBytePair(bool force) {
    if (force_byte_pair_ == force) return;
    force_byte_pair_ = force;
    // Путь загрузки меняет internal format текстур, поэтому кэш размеров надо
    // сбросить — иначе glTexSubImage2D зальёт 16-битные данные в GL_RG8.
    for (int i = 0; i < 3; ++i) {
        texture_width_[i] = 0;
        texture_height_[i] = 0;
    }
}

void VideoRenderer::UploadPlane(int index, const uint8_t *data, int stride, int width, int height,
                                int channels, bool sixteen) {
    // Байт на тексель одинаково считается на всех трёх путях: 8 бит — по каналу,
    // 16 бит — по два байта на канал, независимо от того, лежат они в
    // GL_R16_EXT или склеены обратно в шейдере.
    const int bytes_per_texel = channels * (sixteen ? 2 : 1);

    GLenum internal_format;
    GLenum format;
    GLenum type;
    if (!sixteen) {
        internal_format = (channels == 2) ? GL_RG8 : GL_R8;
        format = (channels == 2) ? GL_RG : GL_RED;
        type = GL_UNSIGNED_BYTE;
    } else if (upload_path_ == UploadPath::kNorm16) {
        internal_format = (channels == 2) ? GL_RG16_EXT : GL_R16_EXT;
        format = (channels == 2) ? GL_RG : GL_RED;
        type = GL_UNSIGNED_SHORT;
    } else {
        // Те же байты, но истолкованные как два (или четыре) отдельных канала.
        internal_format = (channels == 2) ? GL_RGBA8 : GL_RG8;
        format = (channels == 2) ? GL_RGBA : GL_RG;
        type = GL_UNSIGNED_BYTE;
    }

    glActiveTexture(GL_TEXTURE0 + static_cast<GLenum>(index));
    glBindTexture(GL_TEXTURE_2D, texture_[index]);

    // GL_UNPACK_ROW_LENGTH задаётся В ТЕКСЕЛЯХ, а stride декодера — в байтах.
    // Для двухканальной плоскости NV12 это вдвое меньше, для 16-битной P010 —
    // вчетверо, и перепутанный здесь коэффициент даёт сдвиг цветности на
    // полкадра при формально правильной яркости.
    glPixelStorei(GL_UNPACK_ROW_LENGTH, stride / bytes_per_texel);

    if (texture_width_[index] != width || texture_height_[index] != height) {
        glTexImage2D(GL_TEXTURE_2D, 0, static_cast<GLint>(internal_format), width, height, 0, format,
                     type, data);
        texture_width_[index] = width;
        texture_height_[index] = height;
    } else {
        // Размер не менялся — обновляем содержимое. glTexSubImage2D не
        // переаллоцирует текстуру, и на 4K это заметная разница: 60 раз в
        // секунду выделять 12 МБ драйвер не любит.
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, format, type, data);
    }
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
}

void VideoRenderer::EnsureTextures(const FrameDesc &frame) {
    const FormatInfo info = DescribeFormat(frame.format);
    const UploadPath path = !info.sixteen_bit()             ? UploadPath::kByte
                            : (has_norm16_ && !force_byte_pair_) ? UploadPath::kNorm16
                                                                 : UploadPath::kBytePair;

    // Смена формата кадра (переключение дорожки, смена файла) обязана сбросить
    // кэш размеров: иначе glTexSubImage2D зальёт данные в текстуру с прошлым
    // internal format, и результат — «зелёный экран» без ошибок GL. Путь
    // загрузки сравнивается отдельно от формата: 10 и 12 бит дают одинаковый
    // GL_R16_EXT, а вот kNorm16 против kBytePair — разные текстуры при том же
    // формате кадра.
    if (frame_format_ != frame.format || upload_path_ != path) {
        for (int i = 0; i < 3; ++i) {
            texture_width_[i] = 0;
            texture_height_[i] = 0;
        }
        frame_format_ = frame.format;
        upload_path_ = path;
    }
    frame_info_ = info;
}

bool VideoRenderer::UploadFrame(const FrameDesc &frame) {
    if (frame.width <= 0 || frame.height <= 0 || frame.plane[0] == nullptr) {
        DDD_LOGE("gl: пустой кадр (%dx%d)", frame.width, frame.height);
        return false;
    }

    EnsureTextures(frame);
    const FormatInfo &info = frame_info_;
    const bool sixteen = info.sixteen_bit();

    // Строки плоскостей выровнены декодером как угодно, поэтому распаковка
    // побайтовая. Значение по умолчанию — 4, и на кадре шириной, не кратной 4,
    // оно даёт съезжающие строки. Для 16-битных плоскостей выравнивание 4 к
    // тому же не годится: MediaCodec выдаёт stride, кратный 2, но не 4.
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

    const int chroma_w = DivUp(frame.width, info.sub_x);
    const int chroma_h = DivUp(frame.height, info.sub_y);

    UploadPlane(0, frame.plane[0], frame.stride[0], frame.width, frame.height, 1, sixteen);

    if (info.semiplanar) {
        if (frame.plane[1] == nullptr) {
            DDD_LOGE("gl: семиплоскостной кадр без плоскости UV");
            return false;
        }
        UploadPlane(1, frame.plane[1], frame.stride[1], chroma_w, chroma_h, 2, sixteen);
        // Слот V не используется, но сэмплер обязан быть привязан к валидной
        // текстуре: выборка из неполной текстуры в GL не определена, и часть
        // драйверов на этом падает даже в неисполняемой ветке шейдера.
        if (texture_width_[2] != 1) {
            const uint16_t dummy = 0x8000;
            UploadPlane(2, reinterpret_cast<const uint8_t *>(&dummy), sixteen ? 2 : 1, 1, 1, 1,
                        sixteen);
        } else {
            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D, texture_[2]);
        }
    } else {
        if (frame.plane[1] == nullptr || frame.plane[2] == nullptr) {
            DDD_LOGE("gl: планарный кадр без плоскостей U/V");
            return false;
        }
        UploadPlane(1, frame.plane[1], frame.stride[1], chroma_w, chroma_h, 1, sixteen);
        UploadPlane(2, frame.plane[2], frame.stride[2], chroma_w, chroma_h, 1, sixteen);
    }

    // Фильтр цветности переустанавливается после (пере)создания текстур: их
    // параметры сбрасываются вместе с glTexImage2D только при первом создании,
    // но проще выставить всегда, чем угадывать, когда это нужно. Заодно здесь
    // применяется запрет линейной фильтрации на резервном пути.
    SetChromaFilter(chroma_linear_);

    frame_width_ = frame.width;
    frame_height_ = frame.height;
    frame_standard_ = frame.standard;
    frame_full_range_ = frame.full_range;
    has_frame_ = true;

    return CheckGlError("UploadFrame");
}

void VideoRenderer::SetHdrParams(const HdrParams &params) {
    hdr_ = params;
    tone_ = BuildToneMapUniforms(params);
}

void VideoRenderer::SetDolbyMapping(const DoviFrameMapping *mapping) {
    if (mapping == nullptr) {
        dovi_active_ = false;
        return;
    }
    dovi_active_ = true;
    if (mapping->hash == dovi_hash_) return;

    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    for (int i = 0; i < 3; ++i) {
        dovi_kind_[i] = static_cast<int>(mapping->kind[i]);
        if (mapping->kind[i] == DoviLutKind::kMmr3d) {
            glBindTexture(GL_TEXTURE_3D, dovi_texture_3d_[i]);
            glTexImage3D(GL_TEXTURE_3D, 0, GL_R32F, 16, 16, 16, 0, GL_RED, GL_FLOAT,
                         mapping->lut[i].data());
        } else {
            glBindTexture(GL_TEXTURE_2D, dovi_texture_2d_[i]);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F,
                         static_cast<GLsizei>(mapping->lut[i].size()), 1, 0, GL_RED, GL_FLOAT,
                         mapping->lut[i].data());
        }
    }
    memcpy(dovi_nonlinear_, mapping->nonlinear, sizeof dovi_nonlinear_);
    memcpy(dovi_offset_, mapping->nonlinear_offset, sizeof dovi_offset_);
    memcpy(dovi_linear_, mapping->linear_to_rgb, sizeof dovi_linear_);
    dovi_hash_ = mapping->hash;
    CheckGlError("SetDolbyMapping");
}

bool VideoRenderer::Draw(int viewport_w, int viewport_h, int rotation, ScaleMode mode) {
    if (!has_frame_) {
        DDD_LOGW("gl: Draw без загруженного кадра");
        return false;
    }
    if (viewport_w <= 0 || viewport_h <= 0) return false;

    glViewport(0, 0, viewport_w, viewport_h);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);

    // Пропорции считаются по ОТОБРАЖАЕМОМУ кадру: при повороте на 90/270
    // ширина и высота меняются местами, и без этого портретное видео с
    // телефона вписывается как ландшафтное, с полями сверху и снизу.
    const int rot = ((rotation % 360) + 360) % 360;
    const bool swapped = (rot == 90 || rot == 270);
    const float content_w = swapped ? static_cast<float>(frame_height_)
                                    : static_cast<float>(frame_width_) * par_;
    const float content_h = swapped ? static_cast<float>(frame_width_) * par_
                                    : static_cast<float>(frame_height_);
    const float content_aspect = content_w / content_h;
    const float view_aspect = static_cast<float>(viewport_w) / static_cast<float>(viewport_h);

    float sx = 1.f, sy = 1.f;
    switch (mode) {
        case ScaleMode::kStretch:
            break;
        case ScaleMode::kFill:
            if (content_aspect > view_aspect) {
                sx = content_aspect / view_aspect;
            } else {
                sy = view_aspect / content_aspect;
            }
            break;
        case ScaleMode::kFit:
        default:
            if (content_aspect > view_aspect) {
                sy = view_aspect / content_aspect;
            } else {
                sx = content_aspect / view_aspect;
            }
            break;
    }

    const float theta = static_cast<float>(rot) * 3.14159265358979323846f / 180.f;
    const float c = std::cos(theta);
    const float s = std::sin(theta);

    // column-major mat4: поворот по часовой стрелке, затем масштаб вписывания.
    const float transform[16] = {
        sx * c,  -sy * s, 0.f, 0.f,
        sx * s,   sy * c, 0.f, 0.f,
        0.f,      0.f,    1.f, 0.f,
        0.f,      0.f,    0.f, 1.f,
    };

    float matrix[9];
    float offset[3];
    BuildColorMatrix(frame_standard_, frame_full_range_, frame_info_.bit_depth, matrix, offset);

    glUseProgram(program_);
    glUniformMatrix4fv(u_transform_, 1, GL_FALSE, transform);
    glUniformMatrix3fv(u_color_matrix_, 1, GL_FALSE, matrix);
    if (u_color_offset_ >= 0) glUniform3fv(u_color_offset_, 1, offset);
    if (u_is_semiplanar_ >= 0) glUniform1i(u_is_semiplanar_, frame_info_.semiplanar ? 1 : 0);
    if (u_swap_uv_ >= 0) glUniform1i(u_swap_uv_, frame_info_.swap_uv ? 1 : 0);
    glUniform1f(u_sample_scale_, frame_info_.sample_scale());
    glUniform1i(u_sample_mode_, upload_path_ == UploadPath::kBytePair ? 1 : 0);

    // Uniform'ы тонмаппинга считаются на CPU один раз на файл: в шейдере это
    // были бы pow и log на каждый пиксель — на 4K 8.3 млн раз за кадр ради
    // значений, которые не меняются.
    if (u_transfer_ >= 0) glUniform1i(u_transfer_, tone_.transfer);
    if (u_tone_map_mode_ >= 0) glUniform1i(u_tone_map_mode_, tone_.mode);
    if (u_hdr_curve_ >= 0) {
        glUniform4f(u_hdr_curve_, tone_.src_max_pq, tone_.dst_max_pq, tone_.knee,
                    tone_.display_scale);
    }
    if (u_hdr_display_ >= 0) {
        glUniform3f(u_hdr_display_, tone_.brightness, tone_.hlg_gamma_minus_one,
                    tone_.inv_gamma);
    }
    if (u_gamut_ >= 0) glUniformMatrix3fv(u_gamut_, 1, GL_FALSE, tone_.gamut);
    if (u_dovi_active_ >= 0) glUniform1i(u_dovi_active_, dovi_active_ ? 1 : 0);
    if (u_dovi_kind_ >= 0) glUniform3i(u_dovi_kind_, dovi_kind_[0], dovi_kind_[1], dovi_kind_[2]);
    if (u_dovi_nonlinear_ >= 0) {
        glUniformMatrix3fv(u_dovi_nonlinear_, 1, GL_FALSE, dovi_nonlinear_);
    }
    if (u_dovi_offset_ >= 0) glUniform3fv(u_dovi_offset_, 1, dovi_offset_);
    if (u_dovi_linear_ >= 0) glUniformMatrix3fv(u_dovi_linear_, 1, GL_FALSE, dovi_linear_);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture_[0]);
    glUniform1i(u_tex_y_, 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, texture_[1]);
    glUniform1i(u_tex_u_, 1);
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, texture_[2]);
    glUniform1i(u_tex_v_, 2);
    for (int i = 0; i < 3; ++i) {
        glActiveTexture(GL_TEXTURE3 + static_cast<GLenum>(i));
        glBindTexture(GL_TEXTURE_2D, dovi_texture_2d_[i]);
        if (u_dovi_tex_2d_[i] >= 0) glUniform1i(u_dovi_tex_2d_[i], 3 + i);
        glActiveTexture(GL_TEXTURE6 + static_cast<GLenum>(i));
        glBindTexture(GL_TEXTURE_3D, dovi_texture_3d_[i]);
        if (u_dovi_tex_3d_[i] >= 0) glUniform1i(u_dovi_tex_3d_[i], 6 + i);
    }

    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);

    return CheckGlError("Draw");
}

}  // namespace ddd
