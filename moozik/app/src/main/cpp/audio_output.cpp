#include "audio_output.h"

#include <android/log.h>
#include <chrono>
#include <cstring>
#include <thread>

#define LOG_TAG "MoozikAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace moozik {

aaudio_data_callback_result_t AudioOutput::onDataCallback(
        AAudioStream* /*stream*/, void* userData, void* audioData, int32_t numFrames) {
    auto* self = static_cast<AudioOutput*>(userData);
    auto* out = static_cast<float*>(audioData);

    const size_t want = static_cast<size_t>(numFrames) * 2;
    if (self->paused_.load(std::memory_order_relaxed)) {
        std::memset(out, 0, want * sizeof(float));
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    const size_t got = self->ring_.read(out, want);
    if (got > 0) {
        self->consumedFrames_.fetch_add(got / 2, std::memory_order_relaxed);
        if (self->dsp_) {
            self->dsp_->processInterleaved(out, static_cast<int>(got / 2));
        }
    }
    if (got < want) {
        std::memset(out + got, 0, (want - got) * sizeof(float));
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AudioOutput::onErrorCallback(
        AAudioStream* /*stream*/, void* /*userData*/, aaudio_result_t error) {
    LOGE("stream error: %s", AAudio_convertResultToText(error));
}

bool AudioOutput::open(DspEngine* engine, int sampleRate) {
    close();
    dsp_ = engine;
    requestedRate_ = sampleRate;
    ring_.resetAbort();
    consumedFrames_.store(0);

    // Fallback chain: guaranteed-sound first, bit-perfect attempt last.
    // 1) shared float at the native rate (works everywhere, mixer-free on
    //    devices whose HAL passes it through untouched)
    // 2) shared float at 48 kHz (device default; mixer resamples)
    // 3) exclusive (mixer bypass) — opt-in via Settings; some OEM builds
    //    open but render nothing, so it is never the default path.
    struct Attempt { aaudio_sharing_mode_t mode; int rate; bool native; };
    Attempt attempts[] = {
        {AAUDIO_SHARING_MODE_SHARED, sampleRate, true},
        {AAUDIO_SHARING_MODE_SHARED, 48000, false},
        {AAUDIO_SHARING_MODE_EXCLUSIVE, sampleRate, true},
    };

    for (const auto& a : attempts) {
        if (a.mode == AAUDIO_SHARING_MODE_EXCLUSIVE && !s_exclusiveAllowed_.load()) continue;
        if (openWithMode(engine, a.rate, a.mode)) {
            exclusive_ = (a.mode == AAUDIO_SHARING_MODE_EXCLUSIVE);
            nativeRate_ = a.native;
            consumedFrames_.store(0);
            LOGI("opened %s %d Hz (native=%d, requested=%d)",
                 exclusive_ ? "EXCLUSIVE" : "SHARED",
                 AAudioStream_getSampleRate(stream_), a.native, sampleRate);
            return true;
        }
        LOGW("open failed: mode=%s rate=%d",
             a.mode == AAUDIO_SHARING_MODE_EXCLUSIVE ? "excl" : "shared", a.rate);
    }
    return false;
}

// Watchdog recovery: the exclusive stream opened and "started" but the
// hardware never consumes (seen on some ColorOS builds). Reopen in shared.
bool AudioOutput::recoverShared() {
    LOGW("exclusive stream not draining - recovering to SHARED");
    s_exclusiveBroken_.store(true);
    const bool wasNative = nativeRate_;
    close();
    ring_.resetAbort();

    if (openWithMode(dsp_, requestedRate_, AAUDIO_SHARING_MODE_SHARED)) {
        exclusive_ = false;
        nativeRate_ = wasNative;
        consumedFrames_.store(0);
        LOGI("recovered: SHARED %d Hz", actualSampleRate());
        return true;
    }
    if (openWithMode(dsp_, 48000, AAUDIO_SHARING_MODE_SHARED)) {
        exclusive_ = false;
        nativeRate_ = false;
        consumedFrames_.store(0);
        LOGI("recovered: SHARED 48000 Hz");
        return true;
    }
    return false;
}

bool AudioOutput::openWithMode(DspEngine* /*engine*/, int sampleRate, aaudio_sharing_mode_t mode) {
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
        LOGE("builder creation failed");
        return false;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, mode);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setDataCallback(builder, &AudioOutput::onDataCallback, this);
    AAudioStreamBuilder_setErrorCallback(builder, &AudioOutput::onErrorCallback, this);

    aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &stream_);
    if (result == AAUDIO_OK && AAudioStream_requestStart(stream_) != AAUDIO_OK) {
        LOGE("requestStart failed in %s mode", mode == AAUDIO_SHARING_MODE_EXCLUSIVE ? "exclusive" : "shared");
        AAudioStream_close(stream_);
        stream_ = nullptr;
        result = AAUDIO_ERROR_INTERNAL;
    }
    AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK) {
        stream_ = nullptr;
        return false;
    }
    return true;
}

void AudioOutput::close() {
    if (!stream_) return;

    ring_.abort();
    AAudioStream_requestStop(stream_);
    AAudioStream_close(stream_);
    stream_ = nullptr;
}

size_t AudioOutput::write(const float* data, size_t count) {
    size_t written = 0;
    bool watchdogArmed = exclusive_;
    auto firstBlockedAt = std::chrono::steady_clock::now();
    bool blockedBefore = false;

    while (written < count && !ring_.aborted()) {
        written += ring_.write(data + written, count - written);
        if (written >= count) break;

        if (!blockedBefore) {
            blockedBefore = true;
            firstBlockedAt = std::chrono::steady_clock::now();
        }

        // Watchdog for the never-drains zombie stream: if we are blocked
        // because the ring is full yet the callback consumes nothing,
        // the stream is dead — recover to shared mode.
        if (watchdogArmed && !paused_.load()) {
            const auto blockedFor = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - firstBlockedAt).count();
            if (blockedFor > 400) {
                const uint64_t before = consumedFrames_.load();
                std::this_thread::sleep_for(std::chrono::milliseconds(200));
                if (consumedFrames_.load() == before) {
                    if (!recoverShared()) return written;
                    watchdogArmed = false;
                    continue;
                }
                watchdogArmed = false;
            }
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    return written;
}

void AudioOutput::clearRing() {
    // Only valid while the producer is quiescent (seek / teardown).
    ring_.abort();
    std::this_thread::sleep_for(std::chrono::milliseconds(4));
    ring_.resetAbort();
}

int32_t AudioOutput::framesPerBurst() const {
    return stream_ ? AAudioStream_getFramesPerBurst(stream_) : 0;
}

int AudioOutput::actualSampleRate() const {
    return stream_ ? AAudioStream_getSampleRate(stream_) : 0;
}

} // namespace moozik
