/*
 * audio_decoder.h — программное декодирование выбранной аудиодорожки.
 *
 * На выходе всегда interleaved float PCM. Единый формат нужен не ради теста:
 * AudioTrack принимает его без ещё одной конверсии, а следующий слой может
 * применять скорость/микшер и использовать количество реально сыгранных
 * семплов как главный A/V-clock.
 */
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "demux_session.h"

struct AVCodec;
struct AVCodecContext;
struct AVCodecParameters;
struct AVFrame;
struct SwrContext;

namespace ddd {

struct AudioChunk {
    int frames = 0;
    int64_t pts_us = 0;
};

class AudioDecodeSession {
public:
    enum class Step { kPcm, kAgain, kEos, kError };

    static AudioDecodeSession *Create(DemuxSession *demux, int stream_index,
                                      int output_sample_rate, int output_channels,
                                      std::string *error);
    ~AudioDecodeSession();

    AudioDecodeSession(const AudioDecodeSession &) = delete;
    AudioDecodeSession &operator=(const AudioDecodeSession &) = delete;

    /** Заполняет не больше max_frames interleaved float-семплов. */
    Step Next(float *out, int max_frames, int timeout_ms, AudioChunk *chunk);
    bool Flush();

    int stream_index() const { return stream_index_; }
    int sample_rate() const { return output_sample_rate_; }
    int channels() const { return output_channels_; }
    const std::string &decoder_name() const { return decoder_name_; }
    int input_channels() const { return input_channels_; }
    int input_sample_rate() const { return input_sample_rate_; }
    int64_t packets_in() const { return packets_in_; }
    int64_t sample_frames_out() const { return sample_frames_out_; }

private:
    AudioDecodeSession() = default;

    bool Open(std::string *error);
    bool ConfigureResampler(const AVFrame *frame, std::string *error);
    bool ConvertFrame(const AVFrame *frame, std::string *error);
    bool DrainResampler(std::string *error);
    Step CopyPending(float *out, int max_frames, AudioChunk *chunk);
    Step DecodeMore(int timeout_ms);

    DemuxSession *demux_ = nullptr;  // не во владении
    AVCodecParameters *par_ = nullptr;
    const AVCodec *codec_ = nullptr;
    AVCodecContext *context_ = nullptr;
    AVFrame *frame_ = nullptr;
    SwrContext *swr_ = nullptr;

    int stream_index_ = -1;
    int time_base_num_ = 1;
    int time_base_den_ = 1000000;
    int output_sample_rate_ = 48000;
    int output_channels_ = 2;
    int input_sample_rate_ = 0;
    int input_channels_ = 0;
    int input_format_ = -1;

    std::string decoder_name_;
    std::vector<float> pending_pcm_;
    int pending_frames_ = 0;
    int pending_offset_ = 0;
    int64_t pending_pts_us_ = 0;
    int64_t next_pts_us_ = 0;
    bool have_clock_ = false;
    bool drain_sent_ = false;
    bool decoder_eof_ = false;
    bool resampler_drained_ = false;

    int64_t packets_in_ = 0;
    int64_t sample_frames_out_ = 0;
};

}  // namespace ddd
