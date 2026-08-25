#include "audio_decoder.h"

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <cstring>

#include "ddd_log.h"
#include "ff_include.h"

namespace ddd {
namespace {

void SetError(std::string *error, const char *fmt, ...) {
    if (error == nullptr) return;
    char buf[384];
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

}  // namespace

AudioDecodeSession *AudioDecodeSession::Create(DemuxSession *demux, int stream_index,
                                               int output_sample_rate,
                                               int output_channels,
                                               std::string *error) {
    if (demux == nullptr) {
        SetError(error, "audio: нет сессии демукса");
        return nullptr;
    }
    const ProbeResult &probe = demux->probe();
    const int index = stream_index >= 0 ? stream_index : probe.best_audio_index;
    const ProbeAudioTrack *track = nullptr;
    for (const ProbeAudioTrack &candidate : probe.audio) {
        if (candidate.stream_index == index) {
            track = &candidate;
            break;
        }
    }
    if (track == nullptr) {
        SetError(error, "audio: поток %d не является аудио", index);
        return nullptr;
    }
    if (output_sample_rate < 8000 || output_sample_rate > 192000 ||
        output_channels < 1 || output_channels > 8) {
        SetError(error, "audio: неверный выход %d Гц / %d каналов",
                 output_sample_rate, output_channels);
        return nullptr;
    }

    auto *self = new AudioDecodeSession();
    self->demux_ = demux;
    self->stream_index_ = index;
    self->output_sample_rate_ = output_sample_rate;
    self->output_channels_ = output_channels;
    self->par_ = demux->CopyCodecParameters(index);
    demux->StreamTimeBase(index, &self->time_base_num_, &self->time_base_den_);
    if (self->par_ == nullptr || !self->Open(error)) {
        delete self;
        return nullptr;
    }
    DDD_LOGI("audio: %s, stream=%d, input=%d Гц/%d ch, output=%d Гц/%d ch/float",
             self->decoder_name_.c_str(), index, track->sample_rate, track->channels,
             output_sample_rate, output_channels);
    return self;
}

bool AudioDecodeSession::Open(std::string *error) {
    codec_ = avcodec_find_decoder(par_->codec_id);
    if (codec_ == nullptr) {
        SetError(error, "audio: libavcodec не знает codec_id=%d",
                 static_cast<int>(par_->codec_id));
        return false;
    }
    context_ = avcodec_alloc_context3(codec_);
    frame_ = av_frame_alloc();
    if (context_ == nullptr || frame_ == nullptr) {
        SetError(error, "audio: не хватает памяти на декодер");
        return false;
    }
    int rc = avcodec_parameters_to_context(context_, par_);
    if (rc < 0) {
        SetError(error, "audio: parameters_to_context: %s", AvError(rc).c_str());
        return false;
    }
    context_->pkt_timebase = AVRational{time_base_num_, time_base_den_};
    context_->thread_count = 0;
    rc = avcodec_open2(context_, codec_, nullptr);
    if (rc < 0) {
        SetError(error, "audio: avcodec_open2(%s): %s", codec_->name, AvError(rc).c_str());
        return false;
    }
    decoder_name_ = std::string("libavcodec:") + codec_->name;
    return true;
}

AudioDecodeSession::~AudioDecodeSession() {
    if (swr_ != nullptr) swr_free(&swr_);
    if (frame_ != nullptr) av_frame_free(&frame_);
    if (context_ != nullptr) avcodec_free_context(&context_);
    if (par_ != nullptr) avcodec_parameters_free(&par_);
}

bool AudioDecodeSession::ConfigureResampler(const AVFrame *frame, std::string *error) {
    AVChannelLayout input = frame->ch_layout;
    AVChannelLayout fallback{};
    if (input.nb_channels <= 0) {
        const int channels = context_->ch_layout.nb_channels > 0
                                 ? context_->ch_layout.nb_channels
                                 : par_->ch_layout.nb_channels;
        av_channel_layout_default(&fallback, std::max(channels, 1));
        input = fallback;
    }
    const int in_rate = frame->sample_rate > 0 ? frame->sample_rate : context_->sample_rate;
    const int in_format = frame->format;

    AVChannelLayout old_input{};
    bool same = false;
    if (swr_ != nullptr && input_channels_ > 0) {
        av_channel_layout_default(&old_input, input_channels_);
        // Для стандартных layouts число каналов достаточно только как быстрый
        // путь; реальную раскладку ниже всё равно отдаём swr_alloc_set_opts2.
        same = in_rate == input_sample_rate_ && in_format == input_format_ &&
               input.nb_channels == input_channels_;
        av_channel_layout_uninit(&old_input);
    }
    if (same) {
        av_channel_layout_uninit(&fallback);
        return true;
    }

    if (swr_ != nullptr) swr_free(&swr_);
    AVChannelLayout output{};
    av_channel_layout_default(&output, output_channels_);
    int rc = swr_alloc_set_opts2(&swr_, &output, AV_SAMPLE_FMT_FLT, output_sample_rate_,
                                 &input, static_cast<AVSampleFormat>(in_format), in_rate,
                                 0, nullptr);
    av_channel_layout_uninit(&output);
    av_channel_layout_uninit(&fallback);
    if (rc < 0 || swr_ == nullptr) {
        SetError(error, "audio: swr_alloc_set_opts2: %s", AvError(rc).c_str());
        return false;
    }
    rc = swr_init(swr_);
    if (rc < 0) {
        SetError(error, "audio: swr_init: %s", AvError(rc).c_str());
        swr_free(&swr_);
        return false;
    }
    input_sample_rate_ = in_rate;
    input_channels_ = input.nb_channels;
    input_format_ = in_format;
    return true;
}

bool AudioDecodeSession::ConvertFrame(const AVFrame *frame, std::string *error) {
    if (!ConfigureResampler(frame, error)) return false;

    const int64_t raw_pts = frame->best_effort_timestamp != AV_NOPTS_VALUE
                                ? frame->best_effort_timestamp
                                : frame->pts;
    int64_t frame_pts_us = next_pts_us_;
    if (raw_pts != AV_NOPTS_VALUE) {
        frame_pts_us = av_rescale_q(raw_pts,
                                    AVRational{time_base_num_, time_base_den_},
                                    AVRational{1, 1000000});
        have_clock_ = true;
    } else if (!have_clock_) {
        frame_pts_us = 0;
        have_clock_ = true;
    }

    const int capacity = static_cast<int>(av_rescale_rnd(
        swr_get_delay(swr_, input_sample_rate_) + frame->nb_samples,
        output_sample_rate_, input_sample_rate_, AV_ROUND_UP));
    if (capacity <= 0) return true;
    pending_pcm_.resize(static_cast<size_t>(capacity) * output_channels_);
    uint8_t *output[] = {reinterpret_cast<uint8_t *>(pending_pcm_.data())};
    const int converted = swr_convert(swr_, output, capacity,
                                      const_cast<const uint8_t **>(frame->extended_data),
                                      frame->nb_samples);
    if (converted < 0) {
        SetError(error, "audio: swr_convert: %s", AvError(converted).c_str());
        pending_pcm_.clear();
        return false;
    }
    pending_frames_ = converted;
    pending_offset_ = 0;
    pending_pts_us_ = frame_pts_us;
    next_pts_us_ = frame_pts_us +
        av_rescale_q(converted, AVRational{1, output_sample_rate_}, AVRational{1, 1000000});
    return true;
}

bool AudioDecodeSession::DrainResampler(std::string *error) {
    if (swr_ == nullptr || resampler_drained_) return true;
    const int capacity = static_cast<int>(av_rescale_rnd(
        swr_get_delay(swr_, input_sample_rate_), output_sample_rate_,
        input_sample_rate_, AV_ROUND_UP));
    if (capacity <= 0) {
        resampler_drained_ = true;
        return true;
    }
    pending_pcm_.resize(static_cast<size_t>(capacity) * output_channels_);
    uint8_t *output[] = {reinterpret_cast<uint8_t *>(pending_pcm_.data())};
    const int converted = swr_convert(swr_, output, capacity, nullptr, 0);
    if (converted < 0) {
        SetError(error, "audio: drain swr: %s", AvError(converted).c_str());
        return false;
    }
    pending_frames_ = converted;
    pending_offset_ = 0;
    pending_pts_us_ = next_pts_us_;
    next_pts_us_ += av_rescale_q(converted, AVRational{1, output_sample_rate_},
                                 AVRational{1, 1000000});
    if (converted == 0) resampler_drained_ = true;
    return true;
}

AudioDecodeSession::Step AudioDecodeSession::CopyPending(float *out, int max_frames,
                                                         AudioChunk *chunk) {
    const int available = pending_frames_ - pending_offset_;
    if (available <= 0) return Step::kAgain;
    const int count = std::min(available, max_frames);
    const size_t samples = static_cast<size_t>(count) * output_channels_;
    const size_t offset = static_cast<size_t>(pending_offset_) * output_channels_;
    memcpy(out, pending_pcm_.data() + offset, samples * sizeof(float));
    chunk->frames = count;
    chunk->pts_us = pending_pts_us_ +
        av_rescale_q(pending_offset_, AVRational{1, output_sample_rate_},
                     AVRational{1, 1000000});
    pending_offset_ += count;
    sample_frames_out_ += count;
    if (pending_offset_ >= pending_frames_) {
        pending_pcm_.clear();
        pending_frames_ = 0;
        pending_offset_ = 0;
    }
    return Step::kPcm;
}

AudioDecodeSession::Step AudioDecodeSession::DecodeMore(int timeout_ms) {
    while (true) {
        int rc = avcodec_receive_frame(context_, frame_);
        if (rc == 0) {
            std::string why;
            const bool ok = ConvertFrame(frame_, &why);
            av_frame_unref(frame_);
            if (!ok) {
                DDD_LOGE("audio: %s", why.c_str());
                return Step::kError;
            }
            if (pending_frames_ > 0) return Step::kPcm;
            continue;
        }
        if (rc == AVERROR_EOF) {
            decoder_eof_ = true;
            std::string why;
            if (!DrainResampler(&why)) {
                DDD_LOGE("audio: %s", why.c_str());
                return Step::kError;
            }
            return pending_frames_ > 0 ? Step::kPcm : Step::kEos;
        }
        if (rc != AVERROR(EAGAIN)) {
            DDD_LOGE("audio: receive_frame: %s", AvError(rc).c_str());
            return Step::kError;
        }

        if (drain_sent_) return Step::kAgain;
        AVPacket *packet = demux_->TakePacket(stream_index_, timeout_ms);
        if (packet == nullptr) {
            if (!demux_->GetStats().eof) return Step::kAgain;
            rc = avcodec_send_packet(context_, nullptr);
            if (rc == AVERROR(EAGAIN)) continue;
            if (rc < 0 && rc != AVERROR_EOF) {
                DDD_LOGE("audio: send EOS: %s", AvError(rc).c_str());
                return Step::kError;
            }
            drain_sent_ = true;
            continue;
        }
        rc = avcodec_send_packet(context_, packet);
        av_packet_free(&packet);
        if (rc == AVERROR(EAGAIN)) continue;
        if (rc < 0) {
            DDD_LOGE("audio: send_packet: %s", AvError(rc).c_str());
            return Step::kError;
        }
        ++packets_in_;
    }
}

AudioDecodeSession::Step AudioDecodeSession::Next(float *out, int max_frames,
                                                  int timeout_ms, AudioChunk *chunk) {
    if (out == nullptr || chunk == nullptr || max_frames <= 0 || context_ == nullptr) {
        return Step::kError;
    }
    chunk->frames = 0;
    chunk->pts_us = 0;
    if (pending_frames_ > pending_offset_) return CopyPending(out, max_frames, chunk);
    if (decoder_eof_ && resampler_drained_) return Step::kEos;
    const Step decoded = DecodeMore(timeout_ms);
    if (decoded == Step::kPcm) return CopyPending(out, max_frames, chunk);
    return decoded;
}

bool AudioDecodeSession::Flush() {
    if (context_ == nullptr) return false;
    avcodec_flush_buffers(context_);
    if (swr_ != nullptr) swr_free(&swr_);
    pending_pcm_.clear();
    pending_frames_ = 0;
    pending_offset_ = 0;
    input_sample_rate_ = 0;
    input_channels_ = 0;
    input_format_ = -1;
    next_pts_us_ = 0;
    have_clock_ = false;
    drain_sent_ = false;
    decoder_eof_ = false;
    resampler_drained_ = false;
    return true;
}

}  // namespace ddd
