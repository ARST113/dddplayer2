#include "subtitle_decoder.h"

#include <cstdarg>
#include <cstdio>

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

std::string AssPayload(const std::string &value) {
    const bool dialogue = value.rfind("Dialogue:", 0) == 0;
    const int wanted_commas = dialogue ? 9 : 8;
    int commas = 0;
    for (size_t i = 0; i < value.size(); ++i) {
        if (value[i] == ',' && ++commas == wanted_commas) return value.substr(i + 1);
    }
    return value;
}

std::string PlainText(const char *raw, bool ass) {
    if (raw == nullptr) return {};
    const std::string source = ass ? AssPayload(raw) : std::string(raw);
    std::string out;
    out.reserve(source.size());
    bool in_override = false;
    bool in_tag = false;
    for (size_t i = 0; i < source.size(); ++i) {
        const char c = source[i];
        if (in_override) {
            if (c == '}') in_override = false;
            continue;
        }
        if (in_tag) {
            if (c == '>') in_tag = false;
            continue;
        }
        if (c == '{') {
            in_override = true;
            continue;
        }
        if (c == '<') {
            in_tag = true;
            continue;
        }
        if (c == '\\' && i + 1 < source.size()) {
            const char escaped = source[i + 1];
            if (escaped == 'N' || escaped == 'n') {
                out.push_back('\n');
                ++i;
                continue;
            }
            if (escaped == 'h') {
                out.push_back(' ');
                ++i;
                continue;
            }
        }
        if (c != '\r') out.push_back(c);
    }

    const size_t first = out.find_first_not_of(" \t\n");
    if (first == std::string::npos) return {};
    const size_t last = out.find_last_not_of(" \t\n");
    return out.substr(first, last - first + 1);
}

std::string CollectText(const AVSubtitle &subtitle) {
    std::string text;
    for (unsigned i = 0; i < subtitle.num_rects; ++i) {
        const AVSubtitleRect *rect = subtitle.rects[i];
        if (rect == nullptr) continue;
        std::string part;
        if (rect->type == SUBTITLE_ASS && rect->ass != nullptr) {
            part = PlainText(rect->ass, true);
        } else if (rect->text != nullptr) {
            part = PlainText(rect->text, false);
        } else if (rect->ass != nullptr) {
            part = PlainText(rect->ass, true);
        }
        if (part.empty()) continue;
        if (!text.empty()) text.push_back('\n');
        text += part;
    }
    return text;
}

}  // namespace

SubtitleDecodeSession *SubtitleDecodeSession::Create(DemuxSession *demux,
                                                      int stream_index,
                                                      std::string *error) {
    if (demux == nullptr) {
        SetError(error, "subtitle: нет сессии демукса");
        return nullptr;
    }
    const int index = stream_index >= 0 ? stream_index : demux->probe().best_subtitle_index;
    const ProbeSubtitleTrack *track = nullptr;
    for (const ProbeSubtitleTrack &candidate : demux->probe().subtitle) {
        if (candidate.stream_index == index) {
            track = &candidate;
            break;
        }
    }
    if (track == nullptr) {
        SetError(error, "subtitle: поток %d не является субтитрами", index);
        return nullptr;
    }
    if (track->is_bitmap) {
        SetError(error, "subtitle: дорожка %d (%s) содержит bitmap-субтитры",
                 index, track->codec.c_str());
        return nullptr;
    }

    auto *self = new SubtitleDecodeSession();
    self->demux_ = demux;
    self->stream_index_ = index;
    self->par_ = demux->CopyCodecParameters(index);
    demux->StreamTimeBase(index, &self->time_base_num_, &self->time_base_den_);
    if (self->par_ == nullptr || !self->Open(error)) {
        delete self;
        return nullptr;
    }
    DDD_LOGI("subtitle: %s, stream=%d", self->decoder_name_.c_str(), index);
    return self;
}

bool SubtitleDecodeSession::Open(std::string *error) {
    codec_ = avcodec_find_decoder(par_->codec_id);
    if (codec_ == nullptr) {
        SetError(error, "subtitle: libavcodec не знает codec_id=%d",
                 static_cast<int>(par_->codec_id));
        return false;
    }
    context_ = avcodec_alloc_context3(codec_);
    if (context_ == nullptr) {
        SetError(error, "subtitle: не хватает памяти на декодер");
        return false;
    }
    int rc = avcodec_parameters_to_context(context_, par_);
    if (rc < 0) {
        SetError(error, "subtitle: parameters_to_context: %s", AvError(rc).c_str());
        return false;
    }
    context_->pkt_timebase = AVRational{time_base_num_, time_base_den_};
    rc = avcodec_open2(context_, codec_, nullptr);
    if (rc < 0) {
        SetError(error, "subtitle: avcodec_open2(%s): %s", codec_->name,
                 AvError(rc).c_str());
        return false;
    }
    decoder_name_ = std::string("libavcodec:") + codec_->name;
    return true;
}

SubtitleDecodeSession::~SubtitleDecodeSession() {
    if (context_ != nullptr) avcodec_free_context(&context_);
    if (par_ != nullptr) avcodec_parameters_free(&par_);
}

SubtitleDecodeSession::Step SubtitleDecodeSession::Next(int timeout_ms,
                                                        SubtitleCue *cue) {
    if (context_ == nullptr || cue == nullptr) return Step::kError;
    cue->text.clear();
    cue->start_us = 0;
    cue->end_us = 0;
    if (drained_) return Step::kEos;

    AVPacket *packet = demux_->TakePacket(stream_index_, timeout_ms);
    AVPacket empty{};
    const bool at_eof = packet == nullptr && demux_->GetStats().eof;
    if (packet == nullptr && !at_eof) return Step::kAgain;

    AVSubtitle subtitle{};
    subtitle.pts = AV_NOPTS_VALUE;
    int got = 0;
    const int64_t packet_pts_us = packet != nullptr && packet->pts != AV_NOPTS_VALUE
        ? av_rescale_q(packet->pts, AVRational{time_base_num_, time_base_den_},
                       AVRational{1, AV_TIME_BASE})
        : 0;
    const int packet_duration_ms = packet != nullptr && packet->duration > 0
        ? static_cast<int>(av_rescale_q(packet->duration,
                                       AVRational{time_base_num_, time_base_den_},
                                       AVRational{1, 1000}))
        : 0;
    const int rc = avcodec_decode_subtitle2(context_, &subtitle, &got,
                                            packet != nullptr ? packet : &empty);
    if (packet != nullptr) av_packet_free(&packet);
    if (rc < 0) {
        DDD_LOGE("subtitle: decode: %s", AvError(rc).c_str());
        return Step::kError;
    }
    if (!got) {
        if (at_eof) drained_ = true;
        return at_eof ? Step::kEos : Step::kAgain;
    }

    cue->text = CollectText(subtitle);
    const int64_t base_us = subtitle.pts != AV_NOPTS_VALUE ? subtitle.pts : packet_pts_us;
    cue->start_us = base_us + static_cast<int64_t>(subtitle.start_display_time) * 1000;
    int end_ms = static_cast<int>(subtitle.end_display_time);
    if (end_ms <= static_cast<int>(subtitle.start_display_time)) {
        end_ms = packet_duration_ms > 0
            ? static_cast<int>(subtitle.start_display_time) + packet_duration_ms
            : static_cast<int>(subtitle.start_display_time) + 5000;
    }
    cue->end_us = base_us + static_cast<int64_t>(end_ms) * 1000;
    avsubtitle_free(&subtitle);
    return cue->text.empty() ? Step::kAgain : Step::kCue;
}

bool SubtitleDecodeSession::Flush() {
    if (context_ == nullptr) return false;
    avcodec_flush_buffers(context_);
    drained_ = false;
    return true;
}

}  // namespace ddd
