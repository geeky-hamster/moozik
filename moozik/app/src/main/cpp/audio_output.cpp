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
    if (got > 0 && self->dsp_) {
        self->dsp_->processInterleaved(out, static_cast<int>(got / 2));
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
    ring_.resetAbort();

    // Fallback chain: bit-perfect first, guaranteed-sound last.
    // 1) exclusive (mixer bypass) at the native rate
    // 2) shared float at the native rate
    // 3) shared float at 48 kHz (device default; mixer resamples)
    struct Attempt { aaudio_sharing_mode_t mode; int rate; bool native; };
    const Attempt attempts[] = {
        {AAUDIO_SHARING_MODE_EXCLUSIVE, sampleRate, true},
        {AAUDIO_SHARING_MODE_SHARED, sampleRate, true},
        {AAUDIO_SHARING_MODE_SHARED, 48000, false},
    };

    for (const auto& a : attempts) {
        if (openWithMode(engine, a.rate, a.mode)) {
            exclusive_ = (a.mode == AAUDIO_SHARING_MODE_EXCLUSIVE);
            nativeRate_ = a.native;
            LOGI("opened %s %d Hz (native=%d, requested=%d)",
                 exclusive_ ? "EXCLUSIVE" : "SHARED",
                 AAudioStream_getSampleRate(stream_), a.native, sampleRate);
            return true;
        }
        LOGW("open failed: mode=%s rate=%d", a.mode == AAUDIO_SHARING_MODE_EXCLUSIVE ? "excl" : "shared", a.rate);
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
    while (written < count && !ring_.aborted()) {
        written += ring_.write(data + written, count - written);
        if (written < count) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
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
