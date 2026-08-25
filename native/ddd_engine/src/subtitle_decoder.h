/*
 * subtitle_decoder.h — текстовые субтитры выбранной дорожки.
 *
 * Декодирование остаётся в том же FFmpeg demux/decode pipeline. Kotlin получает
 * только готовый UTF-8 текст и временной интервал, чтобы Media3 SubtitleView
 * продолжал отвечать за масштабирование и доступность текста.
 */
#pragma once

#include <cstdint>
#include <string>

#include "demux_session.h"

struct AVCodec;
struct AVCodecContext;
struct AVCodecParameters;

namespace ddd {

struct SubtitleCue {
    int64_t start_us = 0;
    int64_t end_us = 0;
    std::string text;
};

class SubtitleDecodeSession {
public:
    enum class Step { kCue, kAgain, kEos, kError };

    static SubtitleDecodeSession *Create(DemuxSession *demux, int stream_index,
                                         std::string *error);
    ~SubtitleDecodeSession();

    SubtitleDecodeSession(const SubtitleDecodeSession &) = delete;
    SubtitleDecodeSession &operator=(const SubtitleDecodeSession &) = delete;

    Step Next(int timeout_ms, SubtitleCue *cue);
    bool Flush();

    const std::string &decoder_name() const { return decoder_name_; }

private:
    SubtitleDecodeSession() = default;
    bool Open(std::string *error);

    DemuxSession *demux_ = nullptr;  // не во владении
    AVCodecParameters *par_ = nullptr;
    const AVCodec *codec_ = nullptr;
    AVCodecContext *context_ = nullptr;
    int stream_index_ = -1;
    int time_base_num_ = 1;
    int time_base_den_ = 1000000;
    bool drained_ = false;
    std::string decoder_name_;
};

}  // namespace ddd
