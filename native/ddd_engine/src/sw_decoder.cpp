#include "sw_decoder.h"

#include <cstdarg>
#include <cstdio>

#include "ddd_log.h"
#include "ff_include.h"

namespace ddd {

namespace {

void SetError(std::string *error, const char *fmt, ...) {
    if (error == nullptr) return;
    char buf[256];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof buf, fmt, args);
    va_end(args);
    *error = buf;
}

std::string AvError(int code) {
    char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(code, buf, sizeof buf);
    return buf;
}

bool MapPixelFormat(AVPixelFormat in, FramePixelFormat *out) {
    switch (in) {
        case AV_PIX_FMT_YUV420P:
        case AV_PIX_FMT_YUVJ420P: *out = FramePixelFormat::kYuv420p; return true;
        case AV_PIX_FMT_NV12: *out = FramePixelFormat::kNv12; return true;
        case AV_PIX_FMT_NV21: *out = FramePixelFormat::kNv21; return true;
        case AV_PIX_FMT_YUV420P10LE: *out = FramePixelFormat::kYuv420p10le; return true;
        case AV_PIX_FMT_YUV420P12LE: *out = FramePixelFormat::kYuv420p12le; return true;
        case AV_PIX_FMT_YUV420P16LE: *out = FramePixelFormat::kYuv420p16le; return true;
        case AV_PIX_FMT_YUV422P10LE: *out = FramePixelFormat::kYuv422p10le; return true;
        case AV_PIX_FMT_YUV422P12LE: *out = FramePixelFormat::kYuv422p12le; return true;
        case AV_PIX_FMT_YUV422P16LE: *out = FramePixelFormat::kYuv422p16le; return true;
        case AV_PIX_FMT_YUV444P10LE: *out = FramePixelFormat::kYuv444p10le; return true;
        case AV_PIX_FMT_YUV444P12LE: *out = FramePixelFormat::kYuv444p12le; return true;
        case AV_PIX_FMT_YUV444P16LE: *out = FramePixelFormat::kYuv444p16le; return true;
        case AV_PIX_FMT_P010LE: *out = FramePixelFormat::kP010; return true;
        default: return false;
    }
}

}  // namespace

SwVideoDecoder *SwVideoDecoder::Create(const AVCodecParameters *par, std::string *error) {
    SwVideoDecoder *self = new SwVideoDecoder();
    if (!self->Open(par, error)) {
        delete self;
        return nullptr;
    }
    return self;
}

bool SwVideoDecoder::Open(const AVCodecParameters *par, std::string *error) {
    if (par == nullptr) {
        SetError(error, "SW: нет параметров потока");
        return false;
    }
    codec_ = avcodec_find_decoder(par->codec_id);
    if (codec_ == nullptr) {
        SetError(error, "SW: libavcodec не знает codec_id=%d", static_cast<int>(par->codec_id));
        return false;
    }
    context_ = avcodec_alloc_context3(codec_);
    frame_ = av_frame_alloc();
    if (context_ == nullptr || frame_ == nullptr) {
        SetError(error, "SW: не хватает памяти на контекст/кадр");
        return false;
    }
    int rc = avcodec_parameters_to_context(context_, par);
    if (rc < 0) {
        SetError(error, "SW: parameters_to_context: %s", AvError(rc).c_str());
        return false;
    }
    // DecodeSession передаёт packet PTS уже в микросекундах.
    context_->pkt_timebase = AVRational{1, 1000000};
    context_->time_base = AVRational{1, 1000000};
    context_->thread_count = 0;  // FFmpeg выбирает число потоков по устройству.

    rc = avcodec_open2(context_, codec_, nullptr);
    if (rc < 0) {
        SetError(error, "SW: avcodec_open2(%s): %s", codec_->name, AvError(rc).c_str());
        return false;
    }
    name_ = std::string("libavcodec:") + codec_->name;
    DDD_LOGI("decoder: %s, %dx%d, software", name_.c_str(), par->width, par->height);
    return true;
}

SwVideoDecoder::~SwVideoDecoder() {
    if (pending_ != nullptr) av_packet_free(&pending_);
    if (frame_ != nullptr) av_frame_free(&frame_);
    if (context_ != nullptr) avcodec_free_context(&context_);
}

VideoDecoder::Feed SwVideoDecoder::Push(AVPacket *pkt, int64_t pts_us, int) {
    if (context_ == nullptr || pkt == nullptr || pending_ != nullptr || eos_sent_) {
        if (pkt != nullptr) av_packet_free(&pkt);
        return Feed::kError;
    }
    pkt->pts = pts_us;
    pkt->dts = pts_us;
    const int rc = avcodec_send_packet(context_, pkt);
    if (rc == AVERROR(EAGAIN)) {
        pending_ = pkt;
        return Feed::kBusy;
    }
    av_packet_free(&pkt);
    if (rc < 0) {
        DDD_LOGE("decoder: SW send packet: %s", AvError(rc).c_str());
        return Feed::kError;
    }
    ++packets_in_;
    return Feed::kQueued;
}

VideoDecoder::Feed SwVideoDecoder::SendPending() {
    if (pending_ == nullptr) return Feed::kQueued;
    const int rc = avcodec_send_packet(context_, pending_);
    if (rc == AVERROR(EAGAIN)) return Feed::kBusy;
    av_packet_free(&pending_);
    if (rc < 0) {
        DDD_LOGE("decoder: SW send pending: %s", AvError(rc).c_str());
        return Feed::kError;
    }
    ++packets_in_;
    return Feed::kQueued;
}

VideoDecoder::Feed SwVideoDecoder::PushPending(int) { return SendPending(); }

VideoDecoder::Feed SwVideoDecoder::PushEos(int) {
    if (eos_sent_) return Feed::kQueued;
    const int rc = avcodec_send_packet(context_, nullptr);
    if (rc == AVERROR(EAGAIN)) return Feed::kBusy;
    if (rc < 0 && rc != AVERROR_EOF) {
        DDD_LOGE("decoder: SW send EOS: %s", AvError(rc).c_str());
        return Feed::kError;
    }
    eos_sent_ = true;
    return Feed::kQueued;
}

bool SwVideoDecoder::DescribeFrame(std::string *error) {
    FramePixelFormat format;
    const AVPixelFormat av_format = static_cast<AVPixelFormat>(frame_->format);
    if (!MapPixelFormat(av_format, &format)) {
        const char *name = av_get_pix_fmt_name(av_format);
        SetError(error, "SW: формат кадра %s не поддержан renderer", name != nullptr ? name : "?");
        return false;
    }
    output_.color_format = -2;  // Не MediaCodec; значение только диагностическое.
    output_.format = format;
    output_.width = frame_->width;
    output_.height = frame_->height;
    output_.stride = frame_->linesize[0];
    output_.slice_height = frame_->height;
    output_.crop_left = 0;
    output_.crop_top = 0;
    output_.stride_reported = true;
    return true;
}

VideoDecoder::Pull SwVideoDecoder::DequeueFrame(DecodedFrame *out, int) {
    if (context_ == nullptr || out == nullptr || frame_held_) return Pull::kError;
    const int rc = avcodec_receive_frame(context_, frame_);
    if (rc == AVERROR(EAGAIN)) return Pull::kAgain;
    if (rc == AVERROR_EOF) return Pull::kEos;
    if (rc < 0) {
        DDD_LOGE("decoder: SW receive frame: %s", AvError(rc).c_str());
        return Pull::kError;
    }

    std::string why;
    if (!DescribeFrame(&why)) {
        DDD_LOGE("decoder: %s", why.c_str());
        av_frame_unref(frame_);
        return Pull::kError;
    }
    out->frame.width = output_.width;
    out->frame.height = output_.height;
    out->frame.format = output_.format;
    for (int i = 0; i < 3; ++i) {
        out->frame.plane[i] = frame_->data[i];
        out->frame.stride[i] = frame_->linesize[i];
    }
    const int64_t pts = frame_->best_effort_timestamp != AV_NOPTS_VALUE
                            ? frame_->best_effort_timestamp
                            : frame_->pts;
    out->pts_us = pts != AV_NOPTS_VALUE ? pts : 0;
    out->index = 0;  // У SW нет индекса MediaCodec; неотрицательное = кадр удержан.
    frame_held_ = true;
    ++frames_out_;
    return Pull::kFrame;
}

void SwVideoDecoder::ReleaseFrame(const DecodedFrame &) {
    if (!frame_held_) return;
    av_frame_unref(frame_);
    frame_held_ = false;
}

bool SwVideoDecoder::Flush() {
    if (context_ == nullptr) return false;
    if (pending_ != nullptr) av_packet_free(&pending_);
    if (frame_held_) av_frame_unref(frame_);
    frame_held_ = false;
    eos_sent_ = false;
    avcodec_flush_buffers(context_);
    return true;
}

}  // namespace ddd
