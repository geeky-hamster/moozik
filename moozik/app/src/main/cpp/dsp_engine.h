#pragma once

#include "biquad.h"

#include <array>

namespace moozik {

// Stereo DSP chain: preamp -> per-channel cascade of up to 32 biquads.
// processInterleaved() is allocation-free and real-time safe.
class DspEngine {
public:
    static constexpr int kMaxBands = 48;
    static constexpr int kChannels = 2;

    explicit DspEngine(int sampleRate);

    void setSampleRate(int sampleRate);
    int sampleRate() const { return sampleRate_; }

    void setPreampDb(double gainDb);
    void setBand(int index, FilterType type, double freq, double q, double gainDb, bool enabled);
    void reset();

    // data: interleaved stereo float samples, frames: frames per channel.
    void processInterleaved(float* data, int frames);

private:
    int sampleRate_;
    float preamp_{1.0f};
    std::array<std::array<Biquad, kMaxBands>, kChannels> bands_;
};

} // namespace moozik
