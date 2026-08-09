#pragma once

#include "DddAudioOutput.h"

#include <jni.h>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

struct DddAudioTrackInfo {
    int id = -1;
    std::string label;
    std::string codec;
    std::string language;
    int channels = 0;
    int sampleRate = 0;
    int bitrate = 0;
    bool selected = false;
};

struct DddPlaybackSnapshot {
    bool running = false;
    bool playing = false;
    bool buffering = false;
    bool ended = false;
    bool hdr = false;
    bool audioActive = false;
    int64_t positionMs = 0;
    int64_t durationMs = 0;
    int64_t bufferedPositionMs = 0;
    int width = 0;
    int height = 0;
    int selectedAudioStream = -1;
};

class DddPlaybackSession {
public:
    DddPlaybackSession() = default;
    ~DddPlaybackSession();

    bool start(
        const std::string& uri,
        int64_t startPositionMs,
        JavaVM* javaVm,
        jobject outputSurface
    );
    void stop();
    void setPlaying(bool playing);
    void seekTo(int64_t positionMs);
    bool selectAudioTrack(int streamIndex);

    DddPlaybackSnapshot snapshot() const;
    std::vector<DddAudioTrackInfo> audioTracks() const;
    std::string lastError() const;

private:
    void playbackLoop(std::string uri, int64_t startPositionMs);
    void setError(const std::string& value);

    std::atomic<bool> running_{false};
    std::atomic<bool> playing_{true};
    std::atomic<bool> buffering_{true};
    std::atomic<bool> ended_{false};
    std::atomic<bool> hdr_{false};
    std::atomic<bool> seekRequested_{false};
    std::atomic<int64_t> requestedPositionMs_{0};
    std::atomic<int64_t> clockBasePositionUs_{0};
    std::atomic<int64_t> clockBaseTimeNs_{0};
    std::atomic<int64_t> lastVideoPositionMs_{0};
    std::atomic<int64_t> durationMs_{0};
    std::atomic<int> width_{0};
    std::atomic<int> height_{0};
    std::atomic<int> selectedAudioStream_{-1};
    std::atomic<int> requestedAudioStream_{-1};

    JavaVM* javaVm_ = nullptr;
    jobject outputSurface_ = nullptr;
    DddAudioOutput audioOutput_;
    std::thread thread_;
    mutable std::mutex mutex_;
    std::vector<DddAudioTrackInfo> audioTracks_;
    std::string lastError_;
};
