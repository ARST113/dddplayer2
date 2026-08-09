#include "DddPlaybackSession.h"

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <algorithm>
#include <chrono>
#include <cctype>
#include <cstring>
#include <thread>
#include <vector>

#ifndef DDD_HAS_FFMPEG
#define DDD_HAS_FFMPEG 0
#endif

#if DDD_HAS_FFMPEG
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavcodec/bsf.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/dovi_meta.h>
#include <libavutil/error.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}
#endif

namespace {
constexpr const char* kNativeTag = "DDDPlayer/Native";
constexpr const char* kNativeVideoTag = "DDDPlayer/NativeVideo";

#define DDD_LOGI(tag, ...) __android_log_print(ANDROID_LOG_INFO, tag, __VA_ARGS__)
#define DDD_LOGE(tag, ...) __android_log_print(ANDROID_LOG_ERROR, tag, __VA_ARGS__)

constexpr int kAudioSampleRate = 48000;
constexpr int kAudioChannels = 2;
constexpr int64_t kSeekToleranceUs = 50000;
constexpr int64_t kLateVideoUs = 150000;

int64_t nowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

#if DDD_HAS_FFMPEG
std::string ffError(int value) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {};
    av_strerror(value, buffer, sizeof(buffer));
    return buffer;
}

const char* videoMime(AVCodecID codecId) {
    switch (codecId) {
        case AV_CODEC_ID_H264: return "video/avc";
        case AV_CODEC_ID_HEVC: return "video/hevc";
        case AV_CODEC_ID_VP8: return "video/x-vnd.on2.vp8";
        case AV_CODEC_ID_VP9: return "video/x-vnd.on2.vp9";
#if defined(AV_CODEC_ID_AV1)
        case AV_CODEC_ID_AV1: return "video/av01";
#endif
        case AV_CODEC_ID_MPEG2VIDEO: return "video/mpeg2";
        case AV_CODEC_ID_MPEG4: return "video/mp4v-es";
        default: return nullptr;
    }
}

const char* annexBFilter(AVCodecID codecId) {
    if (codecId == AV_CODEC_ID_H264) return "h264_mp4toannexb";
    if (codecId == AV_CODEC_ID_HEVC) return "hevc_mp4toannexb";
    return nullptr;
}

bool startsWithAnnexB(const uint8_t* data, int size) {
    if (data == nullptr || size < 3) return false;
    return (data[0] == 0 && data[1] == 0 && data[2] == 1) ||
        (size >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1);
}

int colorStandard(const AVCodecParameters* parameters) {
    if (parameters->color_primaries == AVCOL_PRI_BT2020 ||
        parameters->color_space == AVCOL_SPC_BT2020_NCL ||
        parameters->color_space == AVCOL_SPC_BT2020_CL) return 6;
    if (parameters->color_primaries == AVCOL_PRI_BT709 ||
        parameters->color_space == AVCOL_SPC_BT709) return 1;
    if (parameters->color_space == AVCOL_SPC_BT470BG) return 2;
    if (parameters->color_space == AVCOL_SPC_SMPTE170M) return 4;
    return 0;
}

int colorTransfer(const AVCodecParameters* parameters) {
    if (parameters->color_trc == AVCOL_TRC_SMPTE2084) return 6;
    if (parameters->color_trc == AVCOL_TRC_ARIB_STD_B67) return 7;
    if (parameters->color_trc == AVCOL_TRC_LINEAR) return 1;
    if (parameters->color_trc == AVCOL_TRC_BT709 ||
        parameters->color_trc == AVCOL_TRC_SMPTE170M) return 3;
    return 0;
}

int colorRange(const AVCodecParameters* parameters) {
    if (parameters->color_range == AVCOL_RANGE_JPEG) return 1;
    if (parameters->color_range == AVCOL_RANGE_MPEG) return 2;
    return 0;
}

bool streamIsHdr(const AVCodecParameters* parameters) {
    if (parameters == nullptr) return false;
    if (parameters->color_trc == AVCOL_TRC_SMPTE2084 ||
        parameters->color_trc == AVCOL_TRC_ARIB_STD_B67 ||
        parameters->color_primaries == AVCOL_PRI_BT2020) return true;
    return av_packet_side_data_get(
        parameters->coded_side_data,
        parameters->nb_coded_side_data,
        AV_PKT_DATA_DOVI_CONF
    ) != nullptr;
}

int64_t packetPtsUs(const AVPacket* packet, AVRational timeBase) {
    const int64_t pts = packet->pts != AV_NOPTS_VALUE ? packet->pts : packet->dts;
    return pts == AV_NOPTS_VALUE
        ? 0
        : av_rescale_q(pts, timeBase, AVRational{1, 1000000});
}

int64_t framePtsUs(const AVFrame* frame, AVRational timeBase) {
    const int64_t pts = frame->best_effort_timestamp != AV_NOPTS_VALUE
        ? frame->best_effort_timestamp
        : frame->pts;
    return pts == AV_NOPTS_VALUE
        ? 0
        : av_rescale_q(pts, timeBase, AVRational{1, 1000000});
}

std::string metadataValue(AVDictionary* metadata, const char* key) {
    const AVDictionaryEntry* entry = av_dict_get(metadata, key, nullptr, 0);
    return entry != nullptr && entry->value != nullptr ? entry->value : "";
}

std::string upperAscii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::toupper(c));
    });
    return value;
}

std::string channelLabel(int channels) {
    if (channels == 1) return "1.0";
    if (channels == 2) return "2.0";
    if (channels == 6) return "5.1";
    if (channels == 8) return "7.1";
    return channels > 0 ? std::to_string(channels) + " ch" : "";
}

int interruptRead(void* opaque) {
    auto* running = static_cast<std::atomic<bool>*>(opaque);
    return running != nullptr && !running->load() ? 1 : 0;
}
#endif
}

DddPlaybackSession::~DddPlaybackSession() {
    stop();
}

bool DddPlaybackSession::start(
    const std::string& uri,
    int64_t startPositionMs,
    JavaVM* javaVm,
    jobject outputSurface
) {
    stop();
#if DDD_HAS_FFMPEG
    if (uri.empty() || javaVm == nullptr || outputSurface == nullptr) {
        setError("uri, JavaVM or Surface is missing");
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        audioTracks_.clear();
        lastError_.clear();
    }
    javaVm_ = javaVm;
    outputSurface_ = outputSurface;
    const int64_t safeStart = std::max<int64_t>(0, startPositionMs);
    requestedPositionMs_.store(safeStart);
    clockBasePositionUs_.store(safeStart * 1000);
    clockBaseTimeNs_.store(nowNs());
    lastVideoPositionMs_.store(safeStart);
    durationMs_.store(0);
    width_.store(0);
    height_.store(0);
    selectedAudioStream_.store(-1);
    requestedAudioStream_.store(-1);
    playing_.store(true);
    buffering_.store(true);
    ended_.store(false);
    hdr_.store(false);
    seekRequested_.store(false);
    running_.store(true);
    thread_ = std::thread(&DddPlaybackSession::playbackLoop, this, uri, safeStart);
    DDD_LOGI(kNativeTag, "SESSION_START uri=%s startMs=%lld", uri.c_str(), (long long)safeStart);
    return true;
#else
    (void)uri;
    (void)startPositionMs;
    (void)javaVm;
    (void)outputSurface;
    setError("FFmpeg playback is unavailable for this ABI");
    return false;
#endif
}

void DddPlaybackSession::stop() {
    running_.store(false);
    audioOutput_.stop();
    if (thread_.joinable()) thread_.join();
    playing_.store(false);
    buffering_.store(false);
    javaVm_ = nullptr;
    outputSurface_ = nullptr;
}

void DddPlaybackSession::setPlaying(bool value) {
    const int64_t current = snapshot().positionMs;
    clockBasePositionUs_.store(current * 1000);
    clockBaseTimeNs_.store(nowNs());
    playing_.store(value);
    audioOutput_.setPlaying(value);
}

void DddPlaybackSession::seekTo(int64_t positionMs) {
    const int64_t safe = std::max<int64_t>(0, positionMs);
    requestedPositionMs_.store(safe);
    clockBasePositionUs_.store(safe * 1000);
    clockBaseTimeNs_.store(nowNs());
    lastVideoPositionMs_.store(safe);
    buffering_.store(true);
    seekRequested_.store(true);
}

bool DddPlaybackSession::selectAudioTrack(int streamIndex) {
    std::lock_guard<std::mutex> lock(mutex_);
    const bool found = std::any_of(
        audioTracks_.begin(),
        audioTracks_.end(),
        [streamIndex](const DddAudioTrackInfo& track) { return track.id == streamIndex; }
    );
    if (!found) return false;
    requestedAudioStream_.store(streamIndex);
    return true;
}

DddPlaybackSnapshot DddPlaybackSession::snapshot() const {
    DddPlaybackSnapshot result{};
    result.running = running_.load();
    result.playing = playing_.load();
    result.buffering = buffering_.load() || audioOutput_.buffering();
    result.ended = ended_.load();
    result.hdr = hdr_.load();
    result.audioActive = audioOutput_.active();
    const int64_t audioPositionUs = audioOutput_.positionUs();
    result.positionMs = audioPositionUs >= 0
        ? audioPositionUs / 1000
        : std::max<int64_t>(0, lastVideoPositionMs_.load());
    result.durationMs = durationMs_.load();
    result.bufferedPositionMs = result.positionMs;
    result.width = width_.load();
    result.height = height_.load();
    result.selectedAudioStream = selectedAudioStream_.load();
    return result;
}

std::vector<DddAudioTrackInfo> DddPlaybackSession::audioTracks() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return audioTracks_;
}

std::string DddPlaybackSession::lastError() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return lastError_;
}

void DddPlaybackSession::setError(const std::string& value) {
    std::lock_guard<std::mutex> lock(mutex_);
    lastError_ = value;
    DDD_LOGE(kNativeTag, "%s", value.c_str());
}

void DddPlaybackSession::playbackLoop(std::string uri, int64_t startPositionMs) {
#if DDD_HAS_FFMPEG
    avformat_network_init();

    AVFormatContext* format = avformat_alloc_context();
    AVCodecContext* softwareVideo = nullptr;
    AVCodecContext* audioCodec = nullptr;
    AVBSFContext* bitstreamFilter = nullptr;
    AVPacket* packet = av_packet_alloc();
    AVPacket* filteredPacket = av_packet_alloc();
    AVFrame* videoFrame = av_frame_alloc();
    AVFrame* audioFrame = av_frame_alloc();
    SwsContext* sws = nullptr;
    SwrContext* swr = nullptr;
    AMediaCodec* mediaCodec = nullptr;
    AMediaFormat* mediaFormat = nullptr;
    ANativeWindow* nativeWindow = nullptr;
    bool mediaCodecStarted = false;
    JNIEnv* env = nullptr;
    bool attachedThread = false;
    int videoStream = -1;
    int audioStream = -1;
    int64_t discardVideoUntilUs = startPositionMs > 0 ? startPositionMs * 1000 : -1;
    int64_t discardAudioUntilUs = discardVideoUntilUs;
    bool reachedEof = false;

    auto cleanupAudio = [&]() {
        audioOutput_.stop();
        if (swr != nullptr) swr_free(&swr);
        if (audioCodec != nullptr) avcodec_free_context(&audioCodec);
        audioStream = -1;
        selectedAudioStream_.store(-1);
    };

    auto cleanup = [&]() {
        cleanupAudio();
        if (sws != nullptr) sws_freeContext(sws);
        if (softwareVideo != nullptr) avcodec_free_context(&softwareVideo);
        if (bitstreamFilter != nullptr) av_bsf_free(&bitstreamFilter);
        if (mediaCodec != nullptr) {
            if (mediaCodecStarted) AMediaCodec_stop(mediaCodec);
            AMediaCodec_delete(mediaCodec);
        }
        if (mediaFormat != nullptr) AMediaFormat_delete(mediaFormat);
        if (nativeWindow != nullptr) ANativeWindow_release(nativeWindow);
        if (videoFrame != nullptr) av_frame_free(&videoFrame);
        if (audioFrame != nullptr) av_frame_free(&audioFrame);
        if (packet != nullptr) av_packet_free(&packet);
        if (filteredPacket != nullptr) av_packet_free(&filteredPacket);
        if (format != nullptr) avformat_close_input(&format);
        if (attachedThread && javaVm_ != nullptr) javaVm_->DetachCurrentThread();
        buffering_.store(false);
        running_.store(false);
        ended_.store(reachedEof && lastError().empty());
        DDD_LOGI(kNativeTag, "SESSION_STOP eof=%d error=%s", reachedEof ? 1 : 0, lastError().c_str());
    };

    if (format == nullptr || packet == nullptr || filteredPacket == nullptr ||
        videoFrame == nullptr || audioFrame == nullptr) {
        setError("native allocation failed");
        cleanup();
        return;
    }

    format->interrupt_callback.callback = interruptRead;
    format->interrupt_callback.opaque = &running_;
    AVDictionary* options = nullptr;
    av_dict_set(&options, "user_agent", "DDDPlayer2/Native", 0);
    av_dict_set(&options, "rw_timeout", "30000000", 0);
    av_dict_set(&options, "timeout", "30000000", 0);
    av_dict_set(&options, "reconnect", "1", 0);
    av_dict_set(&options, "reconnect_streamed", "1", 0);
    av_dict_set(&options, "reconnect_delay_max", "4", 0);
    int err = avformat_open_input(&format, uri.c_str(), nullptr, &options);
    av_dict_free(&options);
    if (err < 0) {
        setError("avformat_open_input: " + ffError(err));
        cleanup();
        return;
    }
    err = avformat_find_stream_info(format, nullptr);
    if (err < 0) {
        setError("avformat_find_stream_info: " + ffError(err));
        cleanup();
        return;
    }

    videoStream = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    if (videoStream < 0) {
        setError("video stream not found");
        cleanup();
        return;
    }
    AVStream* video = format->streams[videoStream];
    AVCodecParameters* videoParameters = video->codecpar;
    width_.store(videoParameters->width);
    height_.store(videoParameters->height);
    hdr_.store(streamIsHdr(videoParameters));
    durationMs_.store(
        format->duration == AV_NOPTS_VALUE
            ? 0
            : std::max<int64_t>(0, format->duration / (AV_TIME_BASE / 1000))
    );

    int firstAudio = -1;
    int defaultAudio = -1;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        audioTracks_.clear();
        for (unsigned int i = 0; i < format->nb_streams; ++i) {
            AVStream* stream = format->streams[i];
            if (stream == nullptr || stream->codecpar == nullptr ||
                stream->codecpar->codec_type != AVMEDIA_TYPE_AUDIO) continue;
            if (firstAudio < 0) firstAudio = static_cast<int>(i);
            if (defaultAudio < 0 && (stream->disposition & AV_DISPOSITION_DEFAULT) != 0) {
                defaultAudio = static_cast<int>(i);
            }
            DddAudioTrackInfo track{};
            track.id = static_cast<int>(i);
            track.language = metadataValue(stream->metadata, "language");
            const std::string title = metadataValue(stream->metadata, "title");
            track.codec = upperAscii(avcodec_get_name(stream->codecpar->codec_id));
            track.channels = stream->codecpar->ch_layout.nb_channels;
            track.sampleRate = stream->codecpar->sample_rate;
            track.bitrate = static_cast<int>(std::min<int64_t>(
                std::max<int64_t>(0, stream->codecpar->bit_rate),
                static_cast<int64_t>(INT32_MAX)
            ));
            track.label = !title.empty()
                ? title
                : (!track.language.empty() ? track.language : "Audio " + std::to_string(i));
            if (!track.codec.empty()) track.label += " / " + track.codec;
            const std::string channels = channelLabel(stream->codecpar->ch_layout.nb_channels);
            if (!channels.empty()) track.label += " " + channels;
            audioTracks_.push_back(std::move(track));
        }
    }
    requestedAudioStream_.store(defaultAudio >= 0 ? defaultAudio : firstAudio);

    auto openAudio = [&](int streamIndex, int64_t positionUs) -> bool {
        cleanupAudio();
        if (streamIndex < 0 || streamIndex >= static_cast<int>(format->nb_streams)) return false;
        AVStream* stream = format->streams[streamIndex];
        const AVCodec* decoder = avcodec_find_decoder(stream->codecpar->codec_id);
        if (decoder == nullptr) return false;
        audioCodec = avcodec_alloc_context3(decoder);
        if (audioCodec == nullptr) return false;
        int value = avcodec_parameters_to_context(audioCodec, stream->codecpar);
        if (value >= 0) {
            audioCodec->pkt_timebase = stream->time_base;
            audioCodec->thread_count = 2;
            value = avcodec_open2(audioCodec, decoder, nullptr);
        }
        if (value < 0) {
            cleanupAudio();
            return false;
        }
        if (audioCodec->ch_layout.nb_channels <= 0) av_channel_layout_default(&audioCodec->ch_layout, 2);
        const AVChannelLayout outputLayout = AV_CHANNEL_LAYOUT_STEREO;
        value = swr_alloc_set_opts2(
            &swr,
            &outputLayout,
            AV_SAMPLE_FMT_S16,
            kAudioSampleRate,
            &audioCodec->ch_layout,
            audioCodec->sample_fmt,
            std::max(1, audioCodec->sample_rate),
            0,
            nullptr
        );
        if (value >= 0) value = swr_init(swr);
        if (value < 0 || swr == nullptr || !audioOutput_.start(kAudioSampleRate, kAudioChannels)) {
            cleanupAudio();
            return false;
        }
        audioOutput_.flush(std::max<int64_t>(0, positionUs));
        audioOutput_.setPlaying(playing_.load());
        audioStream = streamIndex;
        selectedAudioStream_.store(streamIndex);
        requestedAudioStream_.store(streamIndex);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            for (auto& track : audioTracks_) track.selected = track.id == streamIndex;
        }
        DDD_LOGI("DDDPlayer/NativeAudio", "TRACK_OPEN stream=%d codec=%s", streamIndex, decoder->name);
        return true;
    };

    auto decodeAudio = [&](AVPacket* audioPacket) {
        if (audioCodec == nullptr || swr == nullptr || audioPacket->stream_index != audioStream) return;
        int value = avcodec_send_packet(audioCodec, audioPacket);
        if (value < 0 && value != AVERROR(EAGAIN)) return;
        while (running_.load()) {
            value = avcodec_receive_frame(audioCodec, audioFrame);
            if (value == AVERROR(EAGAIN) || value == AVERROR_EOF) break;
            if (value < 0) break;
            AVStream* stream = format->streams[audioStream];
            const int64_t ptsUs = framePtsUs(audioFrame, stream->time_base);
            const int inputRate = std::max(1, audioCodec->sample_rate);
            const int capacity = static_cast<int>(av_rescale_rnd(
                swr_get_delay(swr, inputRate) + audioFrame->nb_samples,
                kAudioSampleRate,
                inputRate,
                AV_ROUND_UP
            ));
            std::vector<int16_t> pcm(static_cast<size_t>(std::max(1, capacity)) * kAudioChannels);
            uint8_t* output[] = {reinterpret_cast<uint8_t*>(pcm.data())};
            const int converted = swr_convert(
                swr,
                output,
                capacity,
                const_cast<const uint8_t**>(audioFrame->extended_data),
                audioFrame->nb_samples
            );
            const int64_t endUs = ptsUs +
                static_cast<int64_t>(std::max(0, converted)) * 1000000LL / kAudioSampleRate;
            if (converted > 0 &&
                (discardAudioUntilUs < 0 || endUs + kSeekToleranceUs >= discardAudioUntilUs)) {
                if (audioOutput_.enqueue(pcm.data(), converted, ptsUs)) {
                    buffering_.store(false);
                    discardAudioUntilUs = -1;
                }
            }
            av_frame_unref(audioFrame);
        }
    };

    const int initialAudio = requestedAudioStream_.load();
    if (initialAudio >= 0) openAudio(initialAudio, startPositionMs * 1000);

    const jint envStatus = javaVm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (envStatus == JNI_EDETACHED && javaVm_->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        attachedThread = true;
    }
    if (env != nullptr) nativeWindow = ANativeWindow_fromSurface(env, outputSurface_);

    bool hardwareVideo = false;
    const char* mime = videoMime(videoParameters->codec_id);
    if (mime != nullptr && nativeWindow != nullptr) {
        const char* filterName = annexBFilter(videoParameters->codec_id);
        if (filterName != nullptr) {
            const AVBitStreamFilter* filter = av_bsf_get_by_name(filterName);
            if (filter != nullptr && av_bsf_alloc(filter, &bitstreamFilter) >= 0) {
                err = avcodec_parameters_copy(bitstreamFilter->par_in, videoParameters);
                if (err >= 0) {
                    bitstreamFilter->time_base_in = video->time_base;
                    err = av_bsf_init(bitstreamFilter);
                }
                if (err < 0) av_bsf_free(&bitstreamFilter);
            }
        }

        mediaCodec = AMediaCodec_createDecoderByType(mime);
        mediaFormat = AMediaFormat_new();
        if (mediaCodec != nullptr && mediaFormat != nullptr) {
            AMediaFormat_setString(mediaFormat, "mime", mime);
            AMediaFormat_setInt32(mediaFormat, "width", videoParameters->width);
            AMediaFormat_setInt32(mediaFormat, "height", videoParameters->height);
            AMediaFormat_setInt32(mediaFormat, "max-input-size", 8 * 1024 * 1024);
            AMediaFormat_setInt32(mediaFormat, "priority", 0);
            const int standard = colorStandard(videoParameters);
            const int transfer = colorTransfer(videoParameters);
            const int range = colorRange(videoParameters);
            if (standard != 0) AMediaFormat_setInt32(mediaFormat, "color-standard", standard);
            if (transfer != 0) AMediaFormat_setInt32(mediaFormat, "color-transfer", transfer);
            if (range != 0) AMediaFormat_setInt32(mediaFormat, "color-range", range);

            const AVCodecParameters* csd = bitstreamFilter != nullptr
                ? bitstreamFilter->par_out
                : videoParameters;
            if (csd != nullptr && csd->extradata != nullptr && csd->extradata_size > 0 &&
                (annexBFilter(videoParameters->codec_id) == nullptr ||
                    startsWithAnnexB(csd->extradata, csd->extradata_size))) {
                AMediaFormat_setBuffer(
                    mediaFormat,
                    "csd-0",
                    csd->extradata,
                    static_cast<size_t>(csd->extradata_size)
                );
            }

            media_status_t status = AMediaCodec_configure(mediaCodec, mediaFormat, nativeWindow, nullptr, 0);
            if (status == AMEDIA_OK) status = AMediaCodec_start(mediaCodec);
            if (status == AMEDIA_OK) {
                mediaCodecStarted = true;
                hardwareVideo = true;
                DDD_LOGI(
                    kNativeVideoTag,
                    "MEDIACODEC_READY mime=%s size=%dx%d hdr=%d format=%s",
                    mime,
                    videoParameters->width,
                    videoParameters->height,
                    hdr_.load() ? 1 : 0,
                    AMediaFormat_toString(mediaFormat)
                );
            }
        }
    }

    if (!hardwareVideo) {
        const AVCodec* decoder = avcodec_find_decoder(videoParameters->codec_id);
        if (decoder == nullptr || nativeWindow == nullptr) {
            setError("no hardware or software video decoder");
            cleanup();
            return;
        }
        softwareVideo = avcodec_alloc_context3(decoder);
        if (softwareVideo == nullptr ||
            avcodec_parameters_to_context(softwareVideo, videoParameters) < 0) {
            setError("software video decoder failed");
            cleanup();
            return;
        }
        softwareVideo->thread_count = 4;
        if (avcodec_open2(softwareVideo, decoder, nullptr) < 0) {
            setError("software video decoder failed");
            cleanup();
            return;
        }
        ANativeWindow_setBuffersGeometry(
            nativeWindow,
            videoParameters->width,
            videoParameters->height,
            WINDOW_FORMAT_RGBA_8888
        );
        DDD_LOGI(
            kNativeVideoTag,
            "SOFTWARE_READY decoder=%s size=%dx%d",
            decoder->name,
            videoParameters->width,
            videoParameters->height
        );
    }

    auto masterPositionUs = [&]() -> int64_t {
        const int64_t audioUs = audioOutput_.positionUs();
        if (audioOutput_.active() && audioUs >= 0) return audioUs;
        const int64_t base = clockBasePositionUs_.load();
        if (!playing_.load()) return base;
        return base + std::max<int64_t>(0, nowNs() - clockBaseTimeNs_.load()) / 1000;
    };

    auto waitForVideo = [&](int64_t ptsUs) {
        while (running_.load() && playing_.load() && ptsUs > masterPositionUs() + 25000) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
    };

    auto drainHardware = [&](int64_t timeoutUs) {
        if (!hardwareVideo) return;
        while (running_.load()) {
            AMediaCodecBufferInfo info{};
            const ssize_t output = AMediaCodec_dequeueOutputBuffer(mediaCodec, &info, timeoutUs);
            timeoutUs = 0;
            if (output >= 0) {
                const bool config = (info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) != 0;
                const bool preroll = !config && discardVideoUntilUs >= 0 &&
                    info.presentationTimeUs + kSeekToleranceUs < discardVideoUntilUs;
                const bool late = !config && !preroll && audioOutput_.active() &&
                    info.presentationTimeUs + kLateVideoUs < masterPositionUs();
                const bool render = !config && !preroll && !late;
                if (render) waitForVideo(info.presentationTimeUs);
                AMediaCodec_releaseOutputBuffer(mediaCodec, static_cast<size_t>(output), render);
                if (render) {
                    lastVideoPositionMs_.store(std::max<int64_t>(0, info.presentationTimeUs / 1000));
                    buffering_.store(false);
                    discardVideoUntilUs = -1;
                }
                continue;
            }
            if (output == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                AMediaFormat* outputFormat = AMediaCodec_getOutputFormat(mediaCodec);
                DDD_LOGI(
                    kNativeVideoTag,
                    "OUTPUT_FORMAT %s",
                    outputFormat != nullptr ? AMediaFormat_toString(outputFormat) : "null"
                );
                if (outputFormat != nullptr) AMediaFormat_delete(outputFormat);
                continue;
            }
            break;
        }
    };

    auto queueHardwarePacket = [&](const AVPacket* videoPacket) -> bool {
        const int64_t ptsUs = packetPtsUs(videoPacket, video->time_base);
        while (running_.load()) {
            drainHardware(0);
            const ssize_t input = AMediaCodec_dequeueInputBuffer(mediaCodec, 10000);
            if (input < 0) continue;
            size_t capacity = 0;
            uint8_t* buffer = AMediaCodec_getInputBuffer(mediaCodec, static_cast<size_t>(input), &capacity);
            if (buffer == nullptr || static_cast<size_t>(videoPacket->size) > capacity) {
                setError("MediaCodec input packet exceeds buffer capacity");
                return false;
            }
            std::memcpy(buffer, videoPacket->data, static_cast<size_t>(videoPacket->size));
            return AMediaCodec_queueInputBuffer(
                mediaCodec,
                static_cast<size_t>(input),
                0,
                static_cast<size_t>(videoPacket->size),
                ptsUs,
                0
            ) == AMEDIA_OK;
        }
        return false;
    };

    auto decodeSoftwarePacket = [&](AVPacket* videoPacket) {
        int value = avcodec_send_packet(softwareVideo, videoPacket);
        if (value < 0 && value != AVERROR(EAGAIN)) return;
        while (running_.load()) {
            value = avcodec_receive_frame(softwareVideo, videoFrame);
            if (value == AVERROR(EAGAIN) || value == AVERROR_EOF) break;
            if (value < 0) break;
            const int64_t ptsUs = framePtsUs(videoFrame, video->time_base);
            if (discardVideoUntilUs >= 0 && ptsUs + kSeekToleranceUs < discardVideoUntilUs) {
                av_frame_unref(videoFrame);
                continue;
            }
            waitForVideo(ptsUs);
            ANativeWindow_Buffer windowBuffer{};
            if (ANativeWindow_lock(nativeWindow, &windowBuffer, nullptr) == 0) {
                sws = sws_getCachedContext(
                    sws,
                    videoFrame->width,
                    videoFrame->height,
                    static_cast<AVPixelFormat>(videoFrame->format),
                    videoFrame->width,
                    videoFrame->height,
                    AV_PIX_FMT_RGBA,
                    SWS_FAST_BILINEAR,
                    nullptr,
                    nullptr,
                    nullptr
                );
                if (sws != nullptr) {
                    uint8_t* destination[] = {static_cast<uint8_t*>(windowBuffer.bits)};
                    int destinationStride[] = {windowBuffer.stride * 4};
                    sws_scale(
                        sws,
                        videoFrame->data,
                        videoFrame->linesize,
                        0,
                        videoFrame->height,
                        destination,
                        destinationStride
                    );
                }
                ANativeWindow_unlockAndPost(nativeWindow);
                lastVideoPositionMs_.store(std::max<int64_t>(0, ptsUs / 1000));
                buffering_.store(false);
                discardVideoUntilUs = -1;
            }
            av_frame_unref(videoFrame);
        }
    };

    auto performSeek = [&](int64_t positionMs) {
        const int64_t targetUs = std::max<int64_t>(0, positionMs) * 1000;
        const int64_t timestamp = av_rescale_q(
            targetUs,
            AVRational{1, 1000000},
            video->time_base
        );
        const int value = av_seek_frame(format, videoStream, timestamp, AVSEEK_FLAG_BACKWARD);
        if (value < 0) {
            setError("seek failed: " + ffError(value));
            return;
        }
        if (bitstreamFilter != nullptr) av_bsf_flush(bitstreamFilter);
        if (hardwareVideo) AMediaCodec_flush(mediaCodec);
        if (softwareVideo != nullptr) avcodec_flush_buffers(softwareVideo);
        const int currentAudio = selectedAudioStream_.load();
        if (currentAudio >= 0) openAudio(currentAudio, targetUs);
        discardVideoUntilUs = std::max<int64_t>(0, targetUs - kSeekToleranceUs);
        discardAudioUntilUs = discardVideoUntilUs;
        clockBasePositionUs_.store(targetUs);
        clockBaseTimeNs_.store(nowNs());
        lastVideoPositionMs_.store(positionMs);
        buffering_.store(true);
        DDD_LOGI(kNativeTag, "SEEK targetMs=%lld", (long long)positionMs);
    };

    if (startPositionMs > 0) performSeek(startPositionMs);

    while (running_.load()) {
        if (!playing_.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(8));
            continue;
        }
        if (seekRequested_.exchange(false)) {
            performSeek(requestedPositionMs_.load());
            if (!lastError().empty()) break;
        }
        const int requestedAudio = requestedAudioStream_.load();
        if (requestedAudio >= 0 && requestedAudio != audioStream) {
            openAudio(requestedAudio, masterPositionUs());
        }
        if (hardwareVideo) drainHardware(0);

        err = av_read_frame(format, packet);
        if (err == AVERROR_EOF) {
            reachedEof = true;
            break;
        }
        if (err < 0) {
            if (!running_.load()) break;
            buffering_.store(true);
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        if (packet->stream_index == videoStream) {
            if (hardwareVideo) {
                if (bitstreamFilter != nullptr) {
                    err = av_bsf_send_packet(bitstreamFilter, packet);
                    if (err >= 0) {
                        while (av_bsf_receive_packet(bitstreamFilter, filteredPacket) >= 0) {
                            if (!queueHardwarePacket(filteredPacket)) break;
                            av_packet_unref(filteredPacket);
                        }
                    }
                } else {
                    queueHardwarePacket(packet);
                }
            } else {
                decodeSoftwarePacket(packet);
            }
        } else if (packet->stream_index == audioStream) {
            decodeAudio(packet);
        }
        av_packet_unref(packet);
        if (!lastError().empty()) break;
    }

    if (hardwareVideo && reachedEof) drainHardware(20000);
    cleanup();
#else
    (void)uri;
    (void)startPositionMs;
#endif
}
