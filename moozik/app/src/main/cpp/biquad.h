#pragma once

#include <array>
#include <cmath>

namespace moozik {

enum class FilterType : int {
    Peaking = 0,
    LowShelf = 1,
    HighShelf = 2,
};

// Transposed Direct Form II biquad: double coefficients, float state.
struct Biquad {
    double b0{1.0}, b1{0.0}, b2{0.0};
    double a1{0.0}, a2{0.0};
    float s1{0.0f}, s2{0.0f};

    inline float process(float x) {
        const float y = static_cast<float>(b0) * x + s1;
        s1 = static_cast<float>(b1) * x - static_cast<float>(a1) * y + s2;
        s2 = static_cast<float>(b2) * x - static_cast<float>(a2) * y;
        return y;
    }

    void reset() { s1 = 0.0f; s2 = 0.0f; }
};

void designPeaking(double fs, double f0, double q, double gainDb, Biquad& out);
void designLowShelf(double fs, double f0, double q, double gainDb, Biquad& out);
void designHighShelf(double fs, double f0, double q, double gainDb, Biquad& out);

double magnitudeAt(const Biquad& f, double fs, double freq);

} // namespace moozik
