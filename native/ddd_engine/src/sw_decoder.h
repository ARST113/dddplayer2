/*
 * sw_decoder.h — программный видеодекодер FFmpeg в тот же FrameDesc, что HW.
 *
 * Это шаг 7: смена декодера не создаёт второй renderer. libavcodec отдаёт свои
 * YUV-плоскости непосредственно VideoRenderer, включая 10/12/16 бит.
 */
#pragma once

#include "hw_decoder.h"

struct AVCodec;
struct AVCodecContext;
struct AVCodecParameters;
struct AVFrame;

namespace ddd {

class SwVideoDecoder final : public VideoDecoder {
public:
    static SwVideoDecoder *Create(const AVCodecParameters *par, std::string *error);
    ~SwVideoDecoder() override;

    Feed Push(AVPacket *pkt, int64_t pts_us, int timeout_ms) override;
    bool has_pending() const override { return pending_ != nullptr; }
    Feed PushPending(int timeout_ms) override;
    Feed PushEos(int timeout_ms) override;
    Pull DequeueFrame(DecodedFrame *out, int timeout_ms) override;
    void ReleaseFrame(const DecodedFrame &frame) override;
    bool RenderFrame(const DecodedFrame &) override { return false; }
    bool Flush() override;

    const DecoderOutput &output() const override { return output_; }
    const std::string &name() const override { return name_; }
    int rung() const override { return 4; }
    int64_t frames_out() const override { return frames_out_; }
    int64_t packets_in() const override { return packets_in_; }
    bool surface_output() const override { return false; }

private:
    SwVideoDecoder() = default;
    bool Open(const AVCodecParameters *par, std::string *error);
    bool DescribeFrame(std::string *error);
    Feed SendPending();

    const AVCodec *codec_ = nullptr;
    AVCodecContext *context_ = nullptr;
    AVFrame *frame_ = nullptr;
    AVPacket *pending_ = nullptr;
    DecoderOutput output_;
    std::string name_;
    int64_t frames_out_ = 0;
    int64_t packets_in_ = 0;
    bool eos_sent_ = false;
    bool frame_held_ = false;
};

}  // namespace ddd
