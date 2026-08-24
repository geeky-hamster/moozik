#pragma once

#include "biquad.h"
#include "dsp_engine.h"
#include "ring_buffer.h"

#include <aaudio/AAudio.h>
#include <atomic>

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

private:
    bool openWithMode(DspEngine* engine, int sampleRate, aaudio_sharing_mode_t mode);
    static aaudio_data_callback_result_t onDataCallback(
        AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);

    static void onErrorCallback(
        AAudioStream* stream, void* userData, aaudio_result_t error);

    static constexpr size_t kRingFloats = 1u << 16; // ~340 ms stereo @48 kHz

    DspEngine* dsp_{nullptr};
    RingBuffer ring_;
    AAudioStream* stream_{nullptr};
    std::atomic<bool> paused_{false};
    bool exclusive_{false};
    bool nativeRate_{true};
};

} // namespace moozik
