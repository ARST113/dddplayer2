#include "DddAudioOutput.h"

#include <algorithm>
#include <android/log.h>
#include <chrono>

namespace {
#define DDD_AUDIO_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "DDDPlayer/NativeAudio", __VA_ARGS__)
#define DDD_AUDIO_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DDDPlayer/NativeAudio", __VA_ARGS__)

bool slOk(SLresult result, const char* operation) {
    if (result == SL_RESULT_SUCCESS) return true;
    DDD_AUDIO_LOGE("FFMPEG_AUDIO_OPENSL_FAILED op=%s result=%u", operation, result);
    return false;
}
}

DddAudioOutput::~DddAudioOutput() {
    stop();
}

bool DddAudioOutput::start(int sampleRate, int channelCount) {
    stop();
    sampleRate_ = sampleRate > 0 ? sampleRate : 48000;
    channelCount_ = channelCount == 1 ? 1 : 2;

    if (!slOk(slCreateEngine(&engineObject_, 0, nullptr, 0, nullptr, nullptr), "create_engine") ||
        !slOk((*engineObject_)->Realize(engineObject_, SL_BOOLEAN_FALSE), "realize_engine") ||
        !slOk((*engineObject_)->GetInterface(engineObject_, SL_IID_ENGINE, &engine_), "get_engine")) {
        destroyObjects();
        return false;
    }

    if (!slOk((*engine_)->CreateOutputMix(engine_, &outputMixObject_, 0, nullptr, nullptr), "create_output_mix") ||
        !slOk((*outputMixObject_)->Realize(outputMixObject_, SL_BOOLEAN_FALSE), "realize_output_mix")) {
        destroyObjects();
        return false;
    }

    SLDataLocator_AndroidSimpleBufferQueue queueLocator{
        SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE,
        static_cast<SLuint32>(kHardwareQueueDepth)
    };
    const SLuint32 channelMask = channelCount_ == 1
        ? SL_SPEAKER_FRONT_CENTER
        : (SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT);
    SLDataFormat_PCM pcmFormat{
        SL_DATAFORMAT_PCM,
        static_cast<SLuint32>(channelCount_),
        static_cast<SLuint32>(sampleRate_ * 1000),
        SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_PCMSAMPLEFORMAT_FIXED_16,
        channelMask,
        SL_BYTEORDER_LITTLEENDIAN
    };
    SLDataSource source{&queueLocator, &pcmFormat};
    SLDataLocator_OutputMix outputLocator{SL_DATALOCATOR_OUTPUTMIX, outputMixObject_};
    SLDataSink sink{&outputLocator, nullptr};
    const SLInterfaceID interfaces[] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE, SL_IID_VOLUME};
    const SLboolean required[] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE};

    if (!slOk(
            (*engine_)->CreateAudioPlayer(
                engine_,
                &playerObject_,
                &source,
                &sink,
                2,
                interfaces,
                required
            ),
            "create_player"
        ) ||
        !slOk((*playerObject_)->Realize(playerObject_, SL_BOOLEAN_FALSE), "realize_player") ||
        !slOk((*playerObject_)->GetInterface(playerObject_, SL_IID_PLAY, &player_), "get_play") ||
        !slOk(
            (*playerObject_)->GetInterface(
                playerObject_,
                SL_IID_ANDROIDSIMPLEBUFFERQUEUE,
                &bufferQueue_
            ),
            "get_buffer_queue"
        ) ||
        !slOk((*playerObject_)->GetInterface(playerObject_, SL_IID_VOLUME, &volume_), "get_volume") ||
        !slOk(
            (*bufferQueue_)->RegisterCallback(
                bufferQueue_,
                &DddAudioOutput::bufferQueueCallback,
                this
            ),
            "register_callback"
        )) {
        destroyObjects();
        return false;
    }

    active_.store(true);
    playing_.store(true);
    playedPositionUs_.store(-1);
    underflowCount_.store(0);
    setMuted(muted_.load());
    if (!slOk((*player_)->SetPlayState(player_, SL_PLAYSTATE_PLAYING), "start_player")) {
        stop();
        return false;
    }
    DDD_AUDIO_LOGI(
        "FFMPEG_AUDIO_OPENSL_READY sampleRate=%d channels=%d queueDepth=%zu",
        sampleRate_,
        channelCount_,
        kHardwareQueueDepth
    );
    return true;
}

void DddAudioOutput::stop() {
    active_.store(false);
    spaceAvailable_.notify_all();
    if (player_ != nullptr) {
        (*player_)->SetPlayState(player_, SL_PLAYSTATE_STOPPED);
    }
    if (bufferQueue_ != nullptr) {
        (*bufferQueue_)->Clear(bufferQueue_);
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        pending_.clear();
        submitted_.clear();
    }
    destroyObjects();
    playedPositionUs_.store(-1);
}

void DddAudioOutput::flush(int64_t positionUs) {
    if (bufferQueue_ != nullptr) {
        (*bufferQueue_)->Clear(bufferQueue_);
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        pending_.clear();
        submitted_.clear();
    }
    playedPositionUs_.store(std::max<int64_t>(0, positionUs));
    spaceAvailable_.notify_all();
    DDD_AUDIO_LOGI("FFMPEG_AUDIO_FLUSH positionMs=%lld", (long long)(positionUs / 1000));
}

void DddAudioOutput::setPlaying(bool playing) {
    playing_.store(playing);
    if (player_ != nullptr) {
        (*player_)->SetPlayState(
            player_,
            playing ? SL_PLAYSTATE_PLAYING : SL_PLAYSTATE_PAUSED
        );
    }
}

void DddAudioOutput::setMuted(bool muted) {
    muted_.store(muted);
    if (volume_ != nullptr) {
        (*volume_)->SetMute(volume_, muted ? SL_BOOLEAN_TRUE : SL_BOOLEAN_FALSE);
    }
}

bool DddAudioOutput::enqueue(const int16_t* samples, int frameCount, int64_t ptsUs) {
    if (!active_.load() || samples == nullptr || frameCount <= 0) return false;
    AudioChunk chunk{};
    chunk.frameCount = frameCount;
    chunk.ptsUs = std::max<int64_t>(0, ptsUs);
    chunk.endUs = chunk.ptsUs +
        (static_cast<int64_t>(frameCount) * 1000000LL) / std::max(1, sampleRate_);
    chunk.samples.assign(
        samples,
        samples + static_cast<size_t>(frameCount) * static_cast<size_t>(channelCount_)
    );

    std::unique_lock<std::mutex> lock(mutex_);
    spaceAvailable_.wait(lock, [this]() {
        return !active_.load() || pending_.size() + submitted_.size() < kMaxQueuedChunks;
    });
    if (!active_.load()) return false;
    pending_.push_back(std::move(chunk));
    return submitAvailableLocked();
}

bool DddAudioOutput::buffering() const {
    if (!active_.load() || !playing_.load()) return false;
    std::lock_guard<std::mutex> lock(mutex_);
    return pending_.empty() && submitted_.empty();
}

void DddAudioOutput::bufferQueueCallback(
    SLAndroidSimpleBufferQueueItf,
    void* context
) {
    if (context != nullptr) {
        static_cast<DddAudioOutput*>(context)->onBufferConsumed();
    }
}

void DddAudioOutput::onBufferConsumed() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!submitted_.empty()) {
        playedPositionUs_.store(submitted_.front().endUs);
        submitted_.pop_front();
    }
    if (pending_.empty() && submitted_.empty() && playing_.load()) {
        underflowCount_.fetch_add(1);
    }
    submitAvailableLocked();
    spaceAvailable_.notify_all();
}

bool DddAudioOutput::submitAvailableLocked() {
    if (bufferQueue_ == nullptr) return false;
    while (submitted_.size() < kHardwareQueueDepth && !pending_.empty()) {
        submitted_.push_back(std::move(pending_.front()));
        pending_.pop_front();
        AudioChunk& chunk = submitted_.back();
        const SLresult result = (*bufferQueue_)->Enqueue(
            bufferQueue_,
            chunk.samples.data(),
            static_cast<SLuint32>(chunk.samples.size() * sizeof(int16_t))
        );
        if (result != SL_RESULT_SUCCESS) {
            pending_.push_front(std::move(chunk));
            submitted_.pop_back();
            DDD_AUDIO_LOGE("FFMPEG_AUDIO_ENQUEUE_FAILED result=%u", result);
            return false;
        }
    }
    return true;
}

void DddAudioOutput::destroyObjects() {
    bufferQueue_ = nullptr;
    player_ = nullptr;
    volume_ = nullptr;
    if (playerObject_ != nullptr) {
        (*playerObject_)->Destroy(playerObject_);
        playerObject_ = nullptr;
    }
    if (outputMixObject_ != nullptr) {
        (*outputMixObject_)->Destroy(outputMixObject_);
        outputMixObject_ = nullptr;
    }
    engine_ = nullptr;
    if (engineObject_ != nullptr) {
        (*engineObject_)->Destroy(engineObject_);
        engineObject_ = nullptr;
    }
}
