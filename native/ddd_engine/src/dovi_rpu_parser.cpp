/*
 * dovi_rpu_parser.cpp — see dovi_rpu_parser.h.
 */
#include "dovi_rpu_parser.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>

#include "ddd_log.h"
#include "ff_include.h"

extern "C" {
#include "libavcodec/dovi_rpu.h"
}

namespace ddd {
namespace {

constexpr int kPolynomialSize = 1024;
constexpr int kMmrEdge = 16;

uint64_t HashBytes(uint64_t hash, const void *ptr, size_t size) {
    const auto *bytes = static_cast<const uint8_t *>(ptr);
    for (size_t i = 0; i < size; ++i) {
        hash ^= bytes[i];
        hash *= 1099511628211ULL;
    }
    return hash;
}

double Rational(const AVRational &q) {
    return q.den != 0 ? static_cast<double>(q.num) / static_cast<double>(q.den) : 0.0;
}

void Identity(std::vector<float> *out) {
    out->resize(kPolynomialSize);
    for (int i = 0; i < kPolynomialSize; ++i) {
        (*out)[i] = static_cast<float>(i) / static_cast<float>(kPolynomialSize - 1);
    }
}

DoviLutKind Kind(const AVDOVIReshapingCurve &curve) {
    if (curve.num_pivots <= 1) return DoviLutKind::kIdentity2d;
    int mmr = 0;
    int polynomial = 0;
    for (int i = 0; i < curve.num_pivots - 1; ++i) {
        mmr += curve.mapping_idc[i] == AV_DOVI_MAPPING_MMR;
        polynomial += curve.mapping_idc[i] == AV_DOVI_MAPPING_POLYNOMIAL;
    }
    if (mmr == 1) return DoviLutKind::kMmr3d;
    if (polynomial > 0) return DoviLutKind::kPolynomial2d;
    return DoviLutKind::kIdentity2d;
}

void Polynomial(std::vector<float> *out, const AVDOVIReshapingCurve &curve,
                int bit_depth, int coefficient_bits) {
    Identity(out);
    if (curve.num_pivots <= 1) return;
    const double code_max = static_cast<double>((uint64_t{1} << bit_depth) - 1);
    const double coefficient_scale = static_cast<double>(uint64_t{1} << coefficient_bits);
    for (int i = 0; i < kPolynomialSize; ++i) {
        const double x = static_cast<double>(i) / static_cast<double>(kPolynomialSize - 1);
        int piece = -1;
        for (int p = curve.num_pivots - 2; p >= 0; --p) {
            if (x >= static_cast<double>(curve.pivots[p]) / code_max) {
                piece = p;
                break;
            }
        }
        if (piece < 0 || curve.mapping_idc[piece] != AV_DOVI_MAPPING_POLYNOMIAL) continue;
        const double c0 = static_cast<double>(curve.poly_coef[piece][0]) / coefficient_scale;
        const double c1 = static_cast<double>(curve.poly_coef[piece][1]) / coefficient_scale;
        const double c2 = curve.poly_order[piece] == 2
                              ? static_cast<double>(curve.poly_coef[piece][2]) / coefficient_scale
                              : 0.0;
        (*out)[i] = static_cast<float>(c0 + x * c1 + x * x * c2);
    }
}

double MmrValue(double x, double y, double z, const AVDOVIReshapingCurve &curve,
                double coefficient_scale) {
    const double basis[7] = {x, y, z, x * y, x * z, y * z, x * y * z};
    double value = static_cast<double>(curve.mmr_constant[0]) / coefficient_scale;
    const int order = std::clamp<int>(curve.mmr_order[0], 0, 3);
    for (int row = 0; row < order; ++row) {
        for (int i = 0; i < 7; ++i) {
            double term = basis[i];
            if (row == 1) term *= term;
            if (row == 2) term = term * term * term;
            value += term * static_cast<double>(curve.mmr_coef[0][row][i]) /
                     coefficient_scale;
        }
    }
    return value;
}

void Mmr(std::vector<float> *out, const AVDOVIReshapingCurve &curve, int coefficient_bits) {
    out->resize(kMmrEdge * kMmrEdge * kMmrEdge);
    const double scale = static_cast<double>(uint64_t{1} << coefficient_bits);
    for (int z = 0; z < kMmrEdge; ++z) {
        for (int y = 0; y < kMmrEdge; ++y) {
            for (int x = 0; x < kMmrEdge; ++x) {
                const double fx = static_cast<double>(x) / (kMmrEdge - 1);
                const double fy = static_cast<double>(y) / (kMmrEdge - 1);
                const double fz = static_cast<double>(z) / (kMmrEdge - 1);
                (*out)[(z * kMmrEdge + y) * kMmrEdge + x] =
                    static_cast<float>(MmrValue(fx, fy, fz, curve, scale));
            }
        }
    }
}

void MatrixColumnMajor(const AVRational *source, float *dest) {
    for (int row = 0; row < 3; ++row) {
        for (int col = 0; col < 3; ++col) {
            dest[col * 3 + row] = static_cast<float>(Rational(source[row * 3 + col]));
        }
    }
}

void Multiply3x3(const double a[3][3], const AVRational *b, float *column_major) {
    for (int row = 0; row < 3; ++row) {
        for (int col = 0; col < 3; ++col) {
            double value = 0.0;
            for (int k = 0; k < 3; ++k) value += a[row][k] * Rational(b[k * 3 + col]);
            column_major[col * 3 + row] = static_cast<float>(value);
        }
    }
}

std::vector<uint8_t> UnescapeRbsp(const uint8_t *data, size_t size) {
    std::vector<uint8_t> out;
    out.reserve(size);
    int zeros = 0;
    for (size_t i = 0; i < size; ++i) {
        const uint8_t value = data[i];
        if (zeros >= 2 && value == 0x03) {
            zeros = 0;
            continue;
        }
        out.push_back(value);
        zeros = value == 0 ? zeros + 1 : 0;
    }
    return out;
}

}  // namespace

struct DoviRpuParser::Impl {
    DOVIContext context = {};
    int nal_length_size = 4;
    uint64_t last_hash = 0;
    std::shared_ptr<const DoviFrameMapping> last;

    ~Impl() { ff_dovi_ctx_unref(&context); }

    std::shared_ptr<const DoviFrameMapping> ParseRpu(const uint8_t *payload, size_t size) {
        std::vector<uint8_t> rbsp = UnescapeRbsp(payload, size);
        const int ret = ff_dovi_rpu_parse(&context, rbsp.data(), rbsp.size(), 0);
        if (ret < 0 || context.mapping == nullptr || context.color == nullptr) {
            DDD_LOGW("dovi: RPU parse failed=%d, mapping=%d color=%d", ret,
                     context.mapping != nullptr, context.color != nullptr);
            return nullptr;
        }

        uint64_t hash = 1469598103934665603ULL;
        hash = HashBytes(hash, &context.header, sizeof(context.header));
        hash = HashBytes(hash, context.mapping, sizeof(*context.mapping));
        hash = HashBytes(hash, context.color, sizeof(*context.color));
        if (last && hash == last_hash) return last;

        auto mapping = std::make_shared<DoviFrameMapping>();
        mapping->hash = hash;
        const int depth = std::clamp<int>(context.header.vdr_bit_depth, 8, 16);
        const int denominator = std::clamp<int>(context.header.coef_log2_denom, 0, 32);
        for (int c = 0; c < 3; ++c) {
            const AVDOVIReshapingCurve &curve = context.mapping->curves[c];
            mapping->kind[c] = Kind(curve);
            switch (mapping->kind[c]) {
                case DoviLutKind::kMmr3d:
                    Mmr(&mapping->lut[c], curve, denominator);
                    break;
                case DoviLutKind::kPolynomial2d:
                    Polynomial(&mapping->lut[c], curve, depth, denominator);
                    break;
                default:
                    Identity(&mapping->lut[c]);
                    break;
            }
        }

        MatrixColumnMajor(context.color->ycc_to_rgb_matrix, mapping->nonlinear);
        // Same normalization used by libplacebo for full-range DOVI samples.
        const float code_scale = static_cast<float>(uint64_t{1} << depth) /
                                 static_cast<float>((uint64_t{1} << depth) - 1);
        for (int i = 0; i < 3; ++i) {
            mapping->nonlinear_offset[i] =
                static_cast<float>(Rational(context.color->ycc_to_rgb_offset[i])) * code_scale;
        }
        static constexpr double kHpeLmsToBt2020Rgb[3][3] = {
            {3.06441879, -2.16597676, 0.10155818},
            {-0.65612108, 1.78554118, -0.12943749},
            {0.01736321, -0.04725154, 1.03004253},
        };
        Multiply3x3(kHpeLmsToBt2020Rgb, context.color->rgb_to_lms_matrix,
                    mapping->linear_to_rgb);

        last_hash = hash;
        last = mapping;
        DDD_LOGI("dovi: RPU LUT hash=%llx kinds=%d/%d/%d depth=%d denom=%d",
                 static_cast<unsigned long long>(hash), static_cast<int>(mapping->kind[0]),
                 static_cast<int>(mapping->kind[1]), static_cast<int>(mapping->kind[2]), depth,
                 denominator);
        return mapping;
    }
};

std::unique_ptr<DoviRpuParser> DoviRpuParser::Create(const AVCodecParameters *par) {
    if (par == nullptr || par->codec_id != AV_CODEC_ID_HEVC) return nullptr;
    const AVPacketSideData *side = av_packet_side_data_get(
        par->coded_side_data, par->nb_coded_side_data, AV_PKT_DATA_DOVI_CONF);
    if (side == nullptr || side->size < sizeof(AVDOVIDecoderConfigurationRecord)) return nullptr;

    auto *impl = new Impl();
    impl->context.cfg = *reinterpret_cast<const AVDOVIDecoderConfigurationRecord *>(side->data);
    impl->context.enable = 1;
    impl->context.logctx = nullptr;
    if (par->extradata != nullptr && par->extradata_size > 21 && par->extradata[0] == 1) {
        impl->nal_length_size = (par->extradata[21] & 0x03) + 1;
    }
    DDD_LOGI("dovi: RPU parser profile=%d compat=%d nal-length=%d",
             impl->context.cfg.dv_profile, impl->context.cfg.dv_bl_signal_compatibility_id,
             impl->nal_length_size);
    return std::unique_ptr<DoviRpuParser>(new DoviRpuParser(impl));
}

DoviRpuParser::~DoviRpuParser() { delete impl_; }

std::shared_ptr<const DoviFrameMapping> DoviRpuParser::ParsePacket(const uint8_t *data,
                                                                   size_t size) {
    if (impl_ == nullptr || data == nullptr || size < 3) return nullptr;

    auto parse_nal = [&](const uint8_t *nal, size_t nal_size)
        -> std::shared_ptr<const DoviFrameMapping> {
        if (nal_size <= 2 || ((nal[0] >> 1) & 0x3f) != 62) return nullptr;
        return impl_->ParseRpu(nal + 2, nal_size - 2);
    };

    // Annex-B input (also accepted to keep the parser usable after a BSF).
    if ((size >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) ||
        (data[0] == 0 && data[1] == 0 && data[2] == 1)) {
        size_t start = 0;
        while (start + 3 < size) {
            size_t prefix = 0;
            while (start + 3 < size) {
                if (data[start] == 0 && data[start + 1] == 0 && data[start + 2] == 1) {
                    prefix = 3;
                    break;
                }
                if (start + 4 < size && data[start] == 0 && data[start + 1] == 0 &&
                    data[start + 2] == 0 && data[start + 3] == 1) {
                    prefix = 4;
                    break;
                }
                ++start;
            }
            if (prefix == 0) break;
            const size_t nal_start = start + prefix;
            size_t end = nal_start;
            while (end + 3 < size && !(data[end] == 0 && data[end + 1] == 0 &&
                                       (data[end + 2] == 1 ||
                                        (end + 3 < size && data[end + 2] == 0 &&
                                         data[end + 3] == 1)))) {
                ++end;
            }
            if (end + 3 >= size) end = size;
            if (auto result = parse_nal(data + nal_start, end - nal_start)) return result;
            start = end;
        }
        return nullptr;
    }

    // Matroska/MP4 HEVC packets use the hvcC length prefix.
    size_t offset = 0;
    while (offset + static_cast<size_t>(impl_->nal_length_size) <= size) {
        uint32_t length = 0;
        for (int i = 0; i < impl_->nal_length_size; ++i) length = (length << 8) | data[offset + i];
        offset += impl_->nal_length_size;
        if (length == 0 || offset + length > size) break;
        if (auto result = parse_nal(data + offset, length)) return result;
        offset += length;
    }
    return nullptr;
}

void DoviRpuParser::Flush() {
    if (impl_ == nullptr) return;
    ff_dovi_ctx_flush(&impl_->context);
    impl_->last.reset();
    impl_->last_hash = 0;
}

}  // namespace ddd
