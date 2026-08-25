/*
 * dovi_rpu_parser.h — Dolby Vision RPU -> 4XVR-compatible mapping textures.
 *
 * The video remains decoded once by MediaCodec. Only the small UNSPEC62 RPU
 * NAL is parsed here, then converted to the same 1024x1 polynomial / 16^3 MMR
 * LUTs used by 4XVR.
 */
#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

struct AVCodecParameters;

namespace ddd {

enum class DoviLutKind : int {
    kIdentity2d = 0,
    kPolynomial2d = 1,
    kMmr3d = 2,
};

struct DoviFrameMapping {
    uint64_t hash = 0;
    DoviLutKind kind[3] = {DoviLutKind::kIdentity2d, DoviLutKind::kIdentity2d,
                           DoviLutKind::kIdentity2d};
    std::vector<float> lut[3];

    // Column-major matrices for GLSL.
    float nonlinear[9] = {1.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 1.f};
    float nonlinear_offset[3] = {0.f, 0.f, 0.f};
    // Combined BT.2020 HPE LMS->RGB * RPU rgb_to_lms matrix.
    float linear_to_rgb[9] = {1.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 1.f};
};

class DoviRpuParser {
public:
    static std::unique_ptr<DoviRpuParser> Create(const AVCodecParameters *par);
    ~DoviRpuParser();

    DoviRpuParser(const DoviRpuParser &) = delete;
    DoviRpuParser &operator=(const DoviRpuParser &) = delete;

    std::shared_ptr<const DoviFrameMapping> ParsePacket(const uint8_t *data, size_t size);
    void Flush();

private:
    struct Impl;
    explicit DoviRpuParser(Impl *impl) : impl_(impl) {}
    Impl *impl_ = nullptr;
};

}  // namespace ddd
