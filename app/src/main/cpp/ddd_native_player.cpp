#include <jni.h>
#include <algorithm>
#include <cctype>
#include <string>
#include <sstream>
#include <atomic>
#include <cstdint>
#include <android/log.h>
#include "video/DddPlaybackSession.h"

#if DDD_HAS_FFMPEG
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavcodec/jni.h>
#include <libavutil/avutil.h>
#include <libavutil/error.h>
#include <libavutil/pixdesc.h>
#include <libavutil/dovi_meta.h>
}
#endif

namespace {
constexpr const char* kTag = "DDDPlayer/Native";
constexpr const char* kVersion = "ddd-native-core/0.4-clean-playback";

struct NativeSession {
    uint64_t id = 0;
    std::string uri;
    int64_t start_position_ms = 0;
    jobject surface = nullptr;
    DddPlaybackSession playback;
};

std::atomic<uint64_t> g_next_session_id{1};
JavaVM* g_java_vm = nullptr;

std::string jstringToString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* raw = env->GetStringUTFChars(value, nullptr);
    if (raw == nullptr) return {};
    std::string result(raw);
    env->ReleaseStringUTFChars(value, raw);
    return result;
}

NativeSession* sessionFromHandle(jlong handle) {
    return reinterpret_cast<NativeSession*>(handle);
}

[[maybe_unused]] std::string escapeJson(const std::string& value) {
    std::ostringstream out;
    for (char c : value) {
        switch (c) {
            case '"': out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    out << "\\u" << std::hex << static_cast<int>(c);
                } else {
                    out << c;
                }
        }
    }
    return out.str();
}

std::string capabilitiesJson() {
    std::ostringstream out;
    out << "{"
        << "\"engine\":\"DDD_NATIVE\","
        << "\"stage\":\"native_playback\","
        << "\"ffmpeg_probe\":" << (DDD_HAS_FFMPEG ? "true" : "false") << ","
        << "\"demux\":\"ffmpeg/libavformat\","
        << "\"video_decode\":\"MediaCodec NDK Surface with FFmpeg software fallback\","
        << "\"video_render\":\"Android Surface with HDR color metadata\","
        << "\"audio\":\"FFmpeg decode + OpenSL ES\","
        << "\"subtitles\":\"next milestone\","
        << "\"hdr\":\"PQ/HLG/BT.2020 metadata passthrough; tone mapping and Dolby Vision mapping are not implemented\"," 
        << "\"playback_ready\":" << (DDD_HAS_FFMPEG ? "true" : "false")
        << "}";
    return out.str();
}

#if DDD_HAS_FFMPEG
std::string avErrorToString(int err) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(err, buffer, sizeof(buffer));
    return std::string(buffer);
}

std::string mediaTypeName(AVMediaType type) {
    const char* name = av_get_media_type_string(type);
    return name != nullptr ? std::string(name) : "unknown";
}

std::string colorTransferName(AVColorTransferCharacteristic value) {
    switch (value) {
        case AVCOL_TRC_BT709: return "BT709";
        case AVCOL_TRC_SMPTE2084: return "ST2084/PQ";
        case AVCOL_TRC_ARIB_STD_B67: return "HLG";
        case AVCOL_TRC_SMPTE170M: return "SMPTE170M";
        default: return "UNKNOWN(" + std::to_string(static_cast<int>(value)) + ")";
    }
}

std::string colorPrimariesName(AVColorPrimaries value) {
    switch (value) {
        case AVCOL_PRI_BT709: return "BT709";
        case AVCOL_PRI_BT2020: return "BT2020";
        case AVCOL_PRI_SMPTE170M: return "SMPTE170M";
        default: return "UNKNOWN(" + std::to_string(static_cast<int>(value)) + ")";
    }
}

std::string colorRangeName(AVColorRange value) {
    switch (value) {
        case AVCOL_RANGE_MPEG: return "LIMITED";
        case AVCOL_RANGE_JPEG: return "FULL";
        default: return "UNKNOWN(" + std::to_string(static_cast<int>(value)) + ")";
    }
}

std::string lowerAscii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

std::string fourCcString(uint32_t tag) {
    if (tag == 0) return "";
    char value[5] = {
        static_cast<char>(tag & 0xFF),
        static_cast<char>((tag >> 8) & 0xFF),
        static_cast<char>((tag >> 16) & 0xFF),
        static_cast<char>((tag >> 24) & 0xFF),
        0
    };
    for (int i = 0; i < 4; ++i) {
        if (!std::isprint(static_cast<unsigned char>(value[i]))) return "";
    }
    return std::string(value);
}

const AVDOVIDecoderConfigurationRecord* doviConfig(const AVStream* stream) {
    if (stream == nullptr) return nullptr;
    size_t size = 0;
#if defined(__clang__)
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
#endif
    const uint8_t* data = av_stream_get_side_data(stream, AV_PKT_DATA_DOVI_CONF, &size);
#if defined(__clang__)
#pragma clang diagnostic pop
#endif
    if (data == nullptr || size < sizeof(AVDOVIDecoderConfigurationRecord)) return nullptr;
    return reinterpret_cast<const AVDOVIDecoderConfigurationRecord*>(data);
}

bool hasDolbyVisionSignal(const AVStream* stream, const AVCodecParameters* par, const char* codecName) {
    if (doviConfig(stream) != nullptr) return true;
    if (par == nullptr) return false;

    const std::string name = lowerAscii(codecName != nullptr ? std::string(codecName) : std::string());
    const std::string tag = lowerAscii(fourCcString(par->codec_tag));
    return name.find("dovi") != std::string::npos ||
           name.find("dolbyvision") != std::string::npos ||
           tag.find("dvh") != std::string::npos ||
           tag.find("dvhe") != std::string::npos ||
           tag.find("dva1") != std::string::npos ||
           tag.find("dvav") != std::string::npos;
}

int dolbyVisionProfile(const AVStream* stream) {
    const auto* config = doviConfig(stream);
    return config != nullptr ? static_cast<int>(config->dv_profile) : -1;
}

bool isHdrVideo(const AVStream* stream, const AVCodecParameters* par, const char* codecName) {
    if (par == nullptr) return false;
    return hasDolbyVisionSignal(stream, par, codecName) ||
           par->color_trc == AVCOL_TRC_SMPTE2084 ||
           par->color_trc == AVCOL_TRC_ARIB_STD_B67 ||
           par->color_primaries == AVCOL_PRI_BT2020;
}

std::string hdrKind(const AVStream* stream, const AVCodecParameters* par, const char* codecName) {
    if (par == nullptr) return "UNKNOWN";
    if (hasDolbyVisionSignal(stream, par, codecName)) return "DOLBY_VISION";
    if (par->color_trc == AVCOL_TRC_SMPTE2084) return "HDR10_PQ";
    if (par->color_trc == AVCOL_TRC_ARIB_STD_B67) return "HLG";
    if (par->color_primaries == AVCOL_PRI_BT2020) return "BT2020_SDR_OR_HDR";
    return "SDR";
}
std::string probeUriWithFfmpeg(const std::string& uri) {
    AVFormatContext* format = nullptr;
    AVDictionary* options = nullptr;
    av_dict_set(&options, "rw_timeout", "7000000", 0);
    av_dict_set(&options, "timeout", "7000000", 0);

    int ret = avformat_open_input(&format, uri.c_str(), nullptr, &options);
    av_dict_free(&options);
    if (ret < 0) {
        return "{\"ok\":false,\"engine\":\"DDD_NATIVE\",\"ffmpeg\":true,\"error\":\"open_input failed: " + escapeJson(avErrorToString(ret)) + "\"}";
    }

    ret = avformat_find_stream_info(format, nullptr);
    if (ret < 0) {
        std::string error = avErrorToString(ret);
        avformat_close_input(&format);
        return "{\"ok\":false,\"engine\":\"DDD_NATIVE\",\"ffmpeg\":true,\"error\":\"stream_info failed: " + escapeJson(error) + "\"}";
    }

    std::ostringstream out;
    int64_t durationMs = format->duration == AV_NOPTS_VALUE ? 0 : format->duration / 1000;
    out << "{"
        << "\"ok\":true,"
        << "\"engine\":\"DDD_NATIVE\","
        << "\"ffmpeg\":true,"
        << "\"format\":\"" << escapeJson(format->iformat != nullptr ? format->iformat->name : "") << "\","
        << "\"durationMs\":" << durationMs << ","
        << "\"bitrate\":" << format->bit_rate << ","
        << "\"streamCount\":" << format->nb_streams << ","
        << "\"streams\":[";

    for (unsigned int i = 0; i < format->nb_streams; ++i) {
        AVStream* stream = format->streams[i];
        AVCodecParameters* par = stream != nullptr ? stream->codecpar : nullptr;
        if (i > 0) out << ",";
        const char* codecName = par != nullptr ? avcodec_get_name(par->codec_id) : "unknown";
        out << "{"
            << "\"index\":" << i << ","
            << "\"type\":\"" << escapeJson(par != nullptr ? mediaTypeName(par->codec_type) : "unknown") << "\","
            << "\"codec\":\"" << escapeJson(codecName != nullptr ? codecName : "unknown") << "\","
            << "\"bitrate\":" << (par != nullptr ? par->bit_rate : 0);

        if (par != nullptr && par->codec_type == AVMEDIA_TYPE_VIDEO) {
            const char* pixFmt = par->format >= 0 ? av_get_pix_fmt_name(static_cast<AVPixelFormat>(par->format)) : nullptr;
            AVRational fps = stream->avg_frame_rate.num > 0 ? stream->avg_frame_rate : stream->r_frame_rate;
            double fpsValue = fps.den != 0 ? static_cast<double>(fps.num) / static_cast<double>(fps.den) : 0.0;
            out << ",\"width\":" << par->width
                << ",\"height\":" << par->height
                << ",\"fps\":" << fpsValue
                << ",\"pixelFormat\":\"" << escapeJson(pixFmt != nullptr ? pixFmt : "") << "\""
                << ",\"profile\":" << par->profile
                << ",\"level\":" << par->level
                << ",\"hdr\":" << (isHdrVideo(stream, par, codecName) ? "true" : "false")
                << ",\"hdrKind\":\"" << hdrKind(stream, par, codecName) << "\""
                << ",\"colorTransfer\":\"" << colorTransferName(par->color_trc) << "\""
                << ",\"colorPrimaries\":\"" << colorPrimariesName(par->color_primaries) << "\""
                << ",\"colorRange\":\"" << colorRangeName(par->color_range) << "\""
                << ",\"dolbyVisionProfile\":" << dolbyVisionProfile(stream);
        } else if (par != nullptr && par->codec_type == AVMEDIA_TYPE_AUDIO) {
            out << ",\"sampleRate\":" << par->sample_rate
                << ",\"channels\":" << par->ch_layout.nb_channels
                << ",\"format\":" << par->format;
        }

        AVDictionaryEntry* language = stream != nullptr ? av_dict_get(stream->metadata, "language", nullptr, 0) : nullptr;
        AVDictionaryEntry* title = stream != nullptr ? av_dict_get(stream->metadata, "title", nullptr, 0) : nullptr;
        if (language != nullptr) out << ",\"language\":\"" << escapeJson(language->value) << "\"";
        if (title != nullptr) out << ",\"title\":\"" << escapeJson(title->value) << "\"";
        out << "}";
    }

    out << "]}";
    avformat_close_input(&format);
    return out.str();
}
#endif

std::string playbackSnapshotJson(NativeSession* session) {
    if (session == nullptr) {
        return "{\"running\":false,\"playing\":false,\"buffering\":false,\"ended\":false,\"positionMs\":0,\"durationMs\":0,\"bufferedPositionMs\":0,\"width\":0,\"height\":0,\"selectedAudioStream\":-1,\"error\":\"missing native session\"}";
    }
    const auto snapshot = session->playback.snapshot();
    const std::string error = session->playback.lastError();
    std::ostringstream out;
    out << "{"
        << "\"running\":" << (snapshot.running ? "true" : "false") << ","
        << "\"playing\":" << (snapshot.playing ? "true" : "false") << ","
        << "\"buffering\":" << (snapshot.buffering ? "true" : "false") << ","
        << "\"ended\":" << (snapshot.ended ? "true" : "false") << ","
        << "\"hdr\":" << (snapshot.hdr ? "true" : "false") << ","
        << "\"audioActive\":" << (snapshot.audioActive ? "true" : "false") << ","
        << "\"positionMs\":" << snapshot.positionMs << ","
        << "\"durationMs\":" << snapshot.durationMs << ","
        << "\"bufferedPositionMs\":" << snapshot.bufferedPositionMs << ","
        << "\"width\":" << snapshot.width << ","
        << "\"height\":" << snapshot.height << ","
        << "\"selectedAudioStream\":" << snapshot.selectedAudioStream << ","
        << "\"error\":\"" << escapeJson(error) << "\""
        << "}";
    return out.str();
}

std::string audioTracksJson(NativeSession* session) {
    if (session == nullptr) return "[]";
    const auto tracks = session->playback.audioTracks();
    std::ostringstream out;
    out << "[";
    for (size_t i = 0; i < tracks.size(); ++i) {
        if (i > 0) out << ",";
        const auto& track = tracks[i];
        out << "{"
            << "\"id\":" << track.id << ","
            << "\"label\":\"" << escapeJson(track.label) << "\","
            << "\"codec\":\"" << escapeJson(track.codec) << "\","
            << "\"language\":\"" << escapeJson(track.language) << "\","
            << "\"channels\":" << track.channels << ","
            << "\"sampleRate\":" << track.sampleRate << ","
            << "\"bitrate\":" << track.bitrate << ","
            << "\"selected\":" << (track.selected ? "true" : "false")
            << "}";
    }
    out << "]";
    return out.str();
}

void releaseSessionSurface(JNIEnv* env, NativeSession* session) {
    if (env != nullptr && session != nullptr && session->surface != nullptr) {
        env->DeleteGlobalRef(session->surface);
        session->surface = nullptr;
    }
}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_java_vm = vm;
#if DDD_HAS_FFMPEG
    av_jni_set_java_vm(vm, nullptr);
#endif
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_rootu_dddplayer_player_nativecore_DddNativeBridge_nativeGetVersion(
        JNIEnv* env,
        jobject /* thiz */) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "nativeGetVersion %s", kVersion);
    return env->NewStringUTF(kVersion);
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_rootu_dddplayer_player_nativecore_DddNativeBridge_nativeGetCapabilities(
        JNIEnv* env,
        jobject /* thiz */) {
    auto value = capabilitiesJson();
    __android_log_print(ANDROID_LOG_INFO, kTag, "nativeGetCapabilities ffmpeg=%d", DDD_HAS_FFMPEG);
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_top_rootu_dddplayer_player_nativecore_DddNativeBridge_nativeHasFfmpegProbe(
        JNIEnv* /* env */,
        jobject /* thiz */) {
    return DDD_HAS_FFMPEG ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_rootu_dddplayer_player_nativecore_DddNativeBridge_nativeProbeUri(
        JNIEnv* env,
        jobject /* thiz */,
        jstring uri) {
    auto uriString = jstringToString(env, uri);
#if DDD_HAS_FFMPEG
    auto result = probeUriWithFfmpeg(uriString);
#else
    auto result = std::string("{\"ok\":false,\"engine\":\"DDD_NATIVE\",\"ffmpeg\":false,\"error\":\"FFmpeg probe is not linked for this ABI\"}");
#endif
    __android_log_print(ANDROID_LOG_INFO, kTag, "nativeProbeUri ffmpeg=%d uri=%s", DDD_HAS_FFMPEG, uriString.c_str());
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_rootu_dddplayer_player_nativecore_DddNativeBridge_nativeCreateSession(
        JNIEnv*,
        jobject) {
    auto* session = new NativeSession();
    session->id = g_next_session_id.fetch_add(1);
    __android_log_print(ANDROID_LOG_INFO, kTag, "nativeCreateSession id=%llu", static_cast<unsigned long long>(session->id));
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL
Java_top_rootu_dddplayer_player_nativecore_DddNativeBridge_nativeReleaseSession(
        JNIEnv* env,
        jobject,
        jlong handle) {
    auto* session = sessionFromHandle(handle);
    if (session == nullptr) return;
    __android_log_print(ANDROID_LOG_INFO, kTag, "nativeReleaseSession id=%llu", static_cast<unsigned long long>(session->id));
    session->playback.stop();
    releaseSessionSurface(env, session);
    delete session;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_top_rootu_dddplayer_player_nativecore_DddNativeBridge_nativeSetSurface(
        JNIEnv* env,
        jobject,
        jlong handle,
        jobject surface) {
    auto* session = sessionFromHandle(handle);
    if (session == nullptr) return JNI_FALSE;
    releaseSessionSurface(env, session);
    if (surface != nullptr) {
        session->surface = env->NewGlobalRef(surface);
    }
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "nativeSetSurface id=%llu surface=%p",
        static_cast<unsigned long long>(session->id),
        session->surface
    );
    return session->surface != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_rootu_dddplayer_player_nativecore_DddNativeBridge_nativePrepareSession(
        JNIEnv* env,
        jobject,
        jlong handle,
        jstring uri,
        jlong startPositionMs) {
    auto* session = sessionFromHandle(handle);
    if (session == nullptr) {
        return env->NewStringUTF("{\"ok\":false,\"reason\":\"missing native session\"}");
    }
    if (session->surface == nullptr) {
        return env->NewStringUTF("{\"ok\":false,\"reason\":\"surface is not attached\"}");
    }

    session->uri = jstringToString(env, uri);
    session->start_position_ms = std::max<int64_t>(0, static_cast<int64_t>(startPositionMs));
    const bool started = session->playback.start(
        session->uri,
        session->start_position_ms,
        g_java_vm,
        session->surface
    );
    std::ostringstream out;
    out << "{\"ok\":" << (started ? "true" : "false")
        << ",\"stage\":\"native_playback\""
        << ",\"reason\":\"" << escapeJson(session->playback.lastError()) << "\"}";
    const std::string result = out.str();
    __android_log_print(
        started ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
        kTag,
        "nativePrepareSession id=%llu started=%d startMs=%lld uri=%s error=%s",
        static_cast<unsigned long long>(session->id),
        started ? 1 : 0,
        static_cast<long long>(session->start_position_ms),
        session->uri.c_str(),
        session->playback.lastError().c_str()
    );
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_top_rootu_dddplayer_player_nativecore_DddNativeBridge_nativePlaySession(
        JNIEnv*,
        jobject,
        jlong handle) {
    auto* session = sessionFromHandle(handle);
    if (session == nullptr) return;
    session->playback.setPlaying(true);
}

extern "C" JNIEXPORT void JNICALL
Java_top_rootu_dddplayer_player_nativecore_DddNativeBridge_nativePauseSession(
        JNIEnv*,
        jobject,
        jlong handle) {
    auto* session = sessionFromHandle(handle);
    if (session == nullptr) return;
    session->playback.setPlaying(false);
}

extern "C" JNIEXPORT void JNICALL
Java_top_rootu_dddplayer_player_nativecore_DddNativeBridge_nativeSeekSession(
        JNIEnv*,
        jobject,
        jlong handle,
        jlong positionMs) {
    auto* session = sessionFromHandle(handle);
    if (session == nullptr) return;
    session->playback.seekTo(std::max<int64_t>(0, static_cast<int64_t>(positionMs)));
}

extern "C" JNIEXPORT void JNICALL
Java_top_rootu_dddplayer_player_nativecore_DddNativeBridge_nativeStopSession(
        JNIEnv*,
        jobject,
        jlong handle) {
    auto* session = sessionFromHandle(handle);
    if (session != nullptr) session->playback.stop();
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_rootu_dddplayer_player_nativecore_DddNativeBridge_nativeGetPlaybackSnapshot(
        JNIEnv* env,
        jobject,
        jlong handle) {
    const auto value = playbackSnapshotJson(sessionFromHandle(handle));
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_rootu_dddplayer_player_nativecore_DddNativeBridge_nativeGetAudioTracks(
        JNIEnv* env,
        jobject,
        jlong handle) {
    const auto value = audioTracksJson(sessionFromHandle(handle));
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_top_rootu_dddplayer_player_nativecore_DddNativeBridge_nativeSelectAudioTrack(
        JNIEnv*,
        jobject,
        jlong handle,
        jint streamIndex) {
    auto* session = sessionFromHandle(handle);
    if (session == nullptr) return JNI_FALSE;
    return session->playback.selectAudioTrack(streamIndex) ? JNI_TRUE : JNI_FALSE;
}
