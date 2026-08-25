#include "dsp_engine.h"

#include "biquad.h"

namespace moozik {

namespace {

// Transparent below the knee; gracefully flattens EQ/preamp boosts instead
// of hard-clipping the DAC (ref: dynamics limiting in reference engines).
inline float softLimit(float x) {
    constexpr float knee = 0.92f;
    constexpr float head = 1.0f - knee;
    if (x > knee) {
        x = knee + head * std::tanh((x - knee) / head);
    } else if (x < -knee) {
        x = -knee + head * std::tanh((x + knee) / head);
    }
    return x;
}

} // namespace

DspEngine::DspEngine(int sampleRate) : sampleRate_(sampleRate) {
    reset();
}

void DspEngine::setSampleRate(int sampleRate) {
    sampleRate_ = sampleRate;
}

void DspEngine::setPreampDb(double gainDb) {
    preamp_ = static_cast<float>(std::pow(10.0, gainDb / 20.0));
}

void DspEngine::setBand(int index, FilterType type, double freq, double q, double gainDb, bool enabled) {
    if (index < 0 || index >= kMaxBands) return;

    Biquad designed{};
    switch (type) {
        case FilterType::Peaking:   designPeaking(sampleRate_, freq, q, gainDb, designed); break;
        case FilterType::LowShelf:  designLowShelf(sampleRate_, freq, q, gainDb, designed); break;
        case FilterType::HighShelf: designHighShelf(sampleRate_, freq, q, gainDb, designed); break;
    }

    if (!enabled) {
        // Identity coefficients keep the cascade shape stable.
        designed = {};
    }

    for (auto& channel : bands_) {
        auto& slot = channel[index];
        slot.b0 = designed.b0;
        slot.b1 = designed.b1;
        slot.b2 = designed.b2;
        slot.a1 = designed.a1;
        slot.a2 = designed.a2;
        slot.reset();
    }
}

void DspEngine::reset() {
    for (auto& channel : bands_) {
        for (auto& biquad : channel) {
            biquad = {};
        }
    }
}

void DspEngine::processInterleaved(float* data, int frames) {
    for (int frame = 0; frame < frames; ++frame) {
        for (int ch = 0; ch < kChannels; ++ch) {
            float sample = data[frame * kChannels + ch] * preamp_;
            for (auto& biquad : bands_[ch]) {
                sample = biquad.process(sample);
            }
            data[frame * kChannels + ch] = softLimit(sample);
        }
    }
}

} // namespace moozik
