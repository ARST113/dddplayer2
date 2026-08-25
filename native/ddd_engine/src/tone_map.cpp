#include "tone_map.h"

#include <algorithm>
#include <cmath>

namespace ddd {

namespace {

// Константы ST 2084 как дроби из стандарта, а не десятичные приближения:
// m2 = 2523/4096·128 = 78.84375 ровно, и записанное «78.84» дало бы видимую
// ошибку в тенях.
constexpr double kM1 = 2610.0 / 16384.0;
constexpr double kM2 = 2523.0 / 4096.0 * 128.0;
constexpr double kC1 = 3424.0 / 4096.0;
constexpr double kC2 = 2413.0 / 4096.0 * 32.0;
constexpr double kC3 = 2392.0 / 4096.0 * 32.0;

// ARIB STD-B67.
constexpr double kHlgA = 0.17883277;
constexpr double kHlgB = 0.28466892;
constexpr double kHlgC = 0.55991073;

/**
 * Коэффициенты яркости BT.2020, посчитанные из праймериз (строка Y матрицы
 * RGB→XYZ при D65), а не взятые из таблицы: 0.2627/0.6780/0.0593.
 */
constexpr double kLumaBt2020[3] = {0.2627002, 0.67799807, 0.05930172};

/**
 * BT.2020 → BT.709, строки R,G,B. Выведено из праймериз ITU-R и белой точки D65
 * (`analysis/logs/gamut.js`): M = inv(RGB→XYZ для 709) · (RGB→XYZ для 2020).
 * Суммы строк равны 1 с точностью до 1e-10 — белое переходит в белое, и это
 * первая проверка, которую матрица обязана пройти.
 */
constexpr double kBt2020ToBt709[3][3] = {
    { 1.66049100, -0.58764114, -0.07284986},
    {-0.12455047,  1.13289990, -0.00834942},
    {-0.01815076, -0.10057890,  1.11872966},
};

/** Filmic-кривая из HDR-модификатора 4XVR. */
double FourXvrHable(double x) {
    constexpr double A = 0.15;
    constexpr double B = 0.50;
    constexpr double C = 0.10;
    constexpr double D = 0.20;
    constexpr double E = 0.02;
    constexpr double F = 0.30;
    return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F;
}

void FourXvrToneMapPixel(const HdrParams &p, const double rgb_in[3], double rgb_out[3]) {
    double linear[3];
    for (int i = 0; i < 3; ++i) linear[i] = PqEotf(std::clamp(rgb_in[i], 0.0, 1.2));

    const double lum = std::max({linear[0], linear[1], linear[2]});
    const double scale = FourXvrHable(lum) / std::max(lum, 1e-6);
    // Константы намеренно не "исправлены": это точные коэффициенты шейдера
    // 4XVR, включая его 7-процентную коррекцию зелёного.
    double value[3] = {
        linear[0] * scale * 100.0 * 0.70,
        linear[1] * scale * 93.0 * 0.70,
        linear[2] * scale * 100.0 * 0.70,
    };

    // GetHDRBrightness() в 4XVR по умолчанию возвращает 0.5. В публичном API
    // DDD нейтраль — 1.0, поэтому шкалы связаны коэффициентом 0.5.
    const double slider = std::clamp(static_cast<double>(p.brightness) * 0.5, 0.0, 1.0);
    const double saturation_power = 0.65 + 0.40 * slider;
    const double gamma_power = 0.42 - 0.14 * slider;
    const double smooth_power = 0.40 + 0.80 * std::max(slider - 0.5, 0.0);

    for (double &channel : value) channel = std::pow(std::max(channel, 0.0), gamma_power);
    const double vmax = std::max({value[0], value[1], value[2]});
    for (double &channel : value) {
        channel += std::max(channel - vmax, -0.1) * saturation_power;
        channel = std::clamp(channel, 0.0, 1.0);
        const double v2 = channel * channel;
        const double v3 = v2 * channel;
        channel = v3 * (-2.0 * smooth_power) + v2 * (3.0 * smooth_power) +
                  channel * (1.0 - smooth_power);
    }

    for (int i = 0; i < 3; ++i) rgb_out[i] = std::clamp(value[i], 0.0, 1.0);
}

}  // namespace

float HdrParams::source_peak_nits() const {
    if (mastering_peak_nits > 0.f) return mastering_peak_nits;
    if (max_cll_nits > 0.f) return max_cll_nits;
    // Ни mastering, ни maxCLL: 1000 нит — типовой мастеринг HDR10. Занизить
    // безопаснее, чем завысить: при завышенном пике картинка выйдет темнее, чем
    // задумано, то есть ровно та жалоба, с которой проект начался.
    return 1000.f;
}

double PqEotf(double code) {
    if (code <= 0.0) return 0.0;
    const double p = std::pow(code, 1.0 / kM2);
    const double num = std::max(p - kC1, 0.0);
    const double den = kC2 - kC3 * p;
    if (den <= 0.0) return 1.0;
    return std::pow(num / den, 1.0 / kM1);
}

double PqOetf(double luminance) {
    if (luminance <= 0.0) return 0.0;
    const double p = std::pow(luminance, kM1);
    return std::pow((kC1 + kC2 * p) / (1.0 + kC3 * p), kM2);
}

double HlgInverseOetf(double code) {
    if (code <= 0.5) return code * code / 3.0;
    return (std::exp((code - kHlgC) / kHlgA) + kHlgB) / 12.0;
}

double HlgSystemGamma(double display_peak_nits) {
    if (display_peak_nits <= 0.0) return 1.2;
    return 1.2 + 0.42 * std::log10(display_peak_nits / 1000.0);
}

double Bt2390Eetf(double pq_code, double src_max_pq, double dst_max_pq) {
    if (src_max_pq <= 0.0) return pq_code;
    // Lb = 0, поэтому E1 — простое отношение, а слагаемое minLum обращается в нуль.
    const double e1 = pq_code / src_max_pq;
    const double max_lum = dst_max_pq / src_max_pq;
    const double ks = 1.5 * max_lum - 0.5;

    // Панель ярче контента: сжимать нечего, и кривая обязана быть тождеством —
    // иначе тонмаппинг «на всякий случай» портил бы то, что и так помещается.
    if (ks >= 1.0) return pq_code;

    double e2 = e1;
    if (e1 >= ks) {
        const double t = (e1 - ks) / (1.0 - ks);
        const double t2 = t * t;
        const double t3 = t2 * t;
        e2 = (2.0 * t3 - 3.0 * t2 + 1.0) * ks + (t3 - 2.0 * t2 + t) * (1.0 - ks) +
             (-2.0 * t3 + 3.0 * t2) * max_lum;
    }
    return e2 * src_max_pq;
}

ToneMapUniforms BuildToneMapUniforms(const HdrParams &p) {
    ToneMapUniforms u;
    u.transfer = static_cast<int>(p.transfer);
    u.mode = p.match_four_xvr ? 1 : 0;
    u.brightness = p.brightness > 0.f ? p.brightness : 1.f;
    const float gamma = p.display_gamma > 0.f ? p.display_gamma : 2.2f;
    u.inv_gamma = 1.f / gamma;

    const double dst = p.display_peak_nits > 0.f ? p.display_peak_nits : 500.0;
    const double src = p.source_peak_nits();

    u.src_max_pq = static_cast<float>(PqOetf(src / 10000.0));
    u.dst_max_pq = static_cast<float>(PqOetf(dst / 10000.0));
    const double max_lum = u.src_max_pq > 0.f ? u.dst_max_pq / u.src_max_pq : 1.0;
    u.knee = static_cast<float>(1.5 * max_lum - 0.5);

    if (p.transfer == ColorTransfer::kHlg) {
        // OOTF HLG сама приводит сцену к пику панели, поэтому нормировать нечего
        // и EETF не нужен: HLG адаптивен к дисплею по построению.
        u.display_scale = 1.f;
        u.hlg_gamma_minus_one = static_cast<float>(HlgSystemGamma(dst) - 1.0);
    } else {
        // PQ абсолютен: 1.0 — это 10000 кд/м². Нормируем на пик панели.
        u.display_scale = static_cast<float>(10000.0 / dst);
        u.hlg_gamma_minus_one = 0.f;
    }

    if (p.convert_gamut) {
        // GLSL mat3 — column-major.
        for (int col = 0; col < 3; ++col) {
            for (int row = 0; row < 3; ++row) {
                u.gamut[col * 3 + row] = static_cast<float>(kBt2020ToBt709[row][col]);
            }
        }
    }
    return u;
}

void ToneMapPixel(const HdrParams &p, const double rgb_in[3], double rgb_out[3]) {
    if (p.transfer == ColorTransfer::kPq && p.match_four_xvr) {
        FourXvrToneMapPixel(p, rgb_in, rgb_out);
        return;
    }

    const double dst = p.display_peak_nits > 0.f ? p.display_peak_nits : 500.0;
    const double brightness = p.brightness > 0.f ? p.brightness : 1.0;
    double lin[3];

    if (p.transfer == ColorTransfer::kHlg) {
        double scene[3];
        for (int i = 0; i < 3; ++i) scene[i] = HlgInverseOetf(std::clamp(rgb_in[i], 0.0, 1.0));
        const double ys = kLumaBt2020[0] * scene[0] + kLumaBt2020[1] * scene[1] +
                          kLumaBt2020[2] * scene[2];
        const double gain = std::pow(std::max(ys, 1e-9), HlgSystemGamma(dst) - 1.0);
        for (int i = 0; i < 3; ++i) lin[i] = scene[i] * gain * brightness;
    } else if (p.transfer == ColorTransfer::kPq) {
        const double src_max_pq = PqOetf(p.source_peak_nits() / 10000.0);
        const double dst_max_pq = PqOetf(dst / 10000.0);

        for (int i = 0; i < 3; ++i) lin[i] = PqEotf(std::clamp(rgb_in[i], 0.0, 1.0)) * brightness;
        const double lmax = std::max({lin[0], lin[1], lin[2]});

        // Кривая применяется к максимуму каналов, а результат раздаётся всем трём
        // одним отношением: так цветность пикселя сохраняется точно.
        if (lmax > 1e-9) {
            const double mapped = PqEotf(Bt2390Eetf(PqOetf(lmax), src_max_pq, dst_max_pq));
            const double ratio = mapped / lmax;
            for (int i = 0; i < 3; ++i) lin[i] *= ratio;
        }
        const double scale = 10000.0 / dst;
        for (int i = 0; i < 3; ++i) lin[i] *= scale;
    } else {
        for (int i = 0; i < 3; ++i) rgb_out[i] = std::clamp(rgb_in[i], 0.0, 1.0);
        return;
    }

    // Гамут переводится в линейном свете и ДО отсечения: BT.2020 шире BT.709, и
    // цвета вне гамута дают отрицательные компоненты. Отсечение раньше перевода
    // испортило бы и те цвета, которые в BT.709 попадают.
    double out[3];
    if (p.convert_gamut) {
        for (int row = 0; row < 3; ++row) {
            out[row] = kBt2020ToBt709[row][0] * lin[0] + kBt2020ToBt709[row][1] * lin[1] +
                       kBt2020ToBt709[row][2] * lin[2];
        }
    } else {
        for (int i = 0; i < 3; ++i) out[i] = lin[i];
    }

    const double inv_gamma = 1.0 / (p.display_gamma > 0.f ? p.display_gamma : 2.2);
    for (int i = 0; i < 3; ++i) rgb_out[i] = std::pow(std::clamp(out[i], 0.0, 1.0), inv_gamma);
}

}  // namespace ddd
