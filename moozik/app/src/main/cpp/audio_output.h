#pragma once

#include "biquad.h"
#include "dsp_engine.h"
#include "ring_buffer.h"

#include <aaudio/AAudio.h>
#include <atomic>
#include <chrono>

namespace moozik {

// Couples the decoder thread (producer) to the AAudio callback (consumer),
// applying the DSP chain inside the real-time callback.
class AudioOutput {
public:
    AudioOutput() : ring_(kRingFloats) {}
    ~AudioOutput() { close(); }

    bool open(DspEngine* engine, int sampleRate);
    void close();
    bool isOpen() const { return stream_ != nullptr; }

    /// Decoder thread. Blocks until fully buffered, aborted, or closed.
    size_t write(const float* data, size_t count);
    void clearRing();
    void setPaused(bool paused) { paused_.store(paused, std::memory_order_relaxed); }

    int32_t framesPerBurst() const;
    int actualSampleRate() const;
    bool isNativeRate() const { return nativeRate_; }
    const char* modeText() const { return exclusive_ ? "exclusive" : "shared"; }
    /// Hardware-anchored playback position (frames consumed by the stream).
    int64_t framesRead() const {
        return stream_ ? AAudioStream_getFramesRead(stream_) : -1;
    }
    /// Blocks until the ring is empty (bounded) — preserves song tails.
    void waitDrained(int timeoutMs);
    /// Closes the AAudio stream but keeps the object (and its ring) alive
    /// for reuse — avoids use-after-free against concurrent JNI pollers.
    void closeStream();

    // Global switches (process-wide, driven from Kotlin prefs).
    static void setExclusiveAllowed(bool allowed) { s_exclusiveAllowed_.store(allowed); }
    static bool consumeExclusiveBroken() {
        return s_exclusiveBroken_.exchange(false);
    }

private:
    bool openWithMode(DspEngine* engine, int sampleRate, aaudio_sharing_mode_t mode);
    bool recoverShared();
    static aaudio_data_callback_result_t onDataCallback(
        AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);

    static void onErrorCallback(
        AAudioStream* stream, void* userData, aaudio_result_t error);

    static constexpr size_t kRingFloats = 1u << 16; // ~340 ms stereo @48 kHz

    DspEngine* dsp_{nullptr};
    RingBuffer ring_;
    AAudioStream* stream_{nullptr};
    std::atomic<bool> paused_{false};
    std::atomic<bool> disconnected_{false};
    bool exclusive_{false};
    bool nativeRate_{true};
    int requestedRate_{0};
    std::atomic<uint64_t> consumedFrames_{0};
    // Diagnostics
    std::atomic<uint64_t> callbackCount_{0};
    std::atomic<bool> callbackSeen_{false};

    static inline std::atomic<bool> s_exclusiveAllowed_{true};
    static inline std::atomic<bool> s_exclusiveBroken_{false};
};

} // namespace moozik
