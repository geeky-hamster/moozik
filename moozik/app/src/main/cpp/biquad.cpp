#include "biquad.h"

#include <cmath>

namespace moozik {

namespace {

constexpr double kPi = 3.14159265358979323846;

} // namespace

static void normalize(double a0, double& b0, double& b1, double& b2, double& a1, double& a2) {
    b0 /= a0;
    b1 /= a0;
    b2 /= a0;
    a1 /= a0;
    a2 /= a0;
}

void designPeaking(double fs, double f0, double q, double gainDb, Biquad& out) {
    const double A = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * kPi * f0 / fs;
    const double cw = std::cos(w0);
    const double sw = std::sin(w0);
    const double alpha = sw / (2.0 * q);

    double b0 = 1.0 + alpha * A;
    double b1 = -2.0 * cw;
    double b2 = 1.0 - alpha * A;
    double a0 = 1.0 + alpha / A;
    double a1 = -2.0 * cw;
    double a2 = 1.0 - alpha / A;

    normalize(a0, b0, b1, b2, a1, a2);
    out.b0 = b0; out.b1 = b1; out.b2 = b2; out.a1 = a1; out.a2 = a2;
}

void designLowShelf(double fs, double f0, double q, double gainDb, Biquad& out) {
    const double A = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * kPi * f0 / fs;
    const double cw = std::cos(w0);
    const double sw = std::sin(w0);
    // S = 1 shelf slope
    const double alpha = sw / 2.0 * std::sqrt(2.0);
    const double sqA = 2.0 * std::sqrt(A) * alpha;

    double b0 = A * ((A + 1.0) - (A - 1.0) * cw + sqA);
    double b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cw);
    double b2 = A * ((A + 1.0) - (A - 1.0) * cw - sqA);
    double a0 = (A + 1.0) + (A - 1.0) * cw + sqA;
    double a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cw);
    double a2 = (A + 1.0) + (A - 1.0) * cw - sqA;

    normalize(a0, b0, b1, b2, a1, a2);
    out.b0 = b0; out.b1 = b1; out.b2 = b2; out.a1 = a1; out.a2 = a2;
}

void designHighShelf(double fs, double f0, double q, double gainDb, Biquad& out) {
    const double A = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * kPi * f0 / fs;
    const double cw = std::cos(w0);
    const double sw = std::sin(w0);
    const double alpha = sw / 2.0 * std::sqrt(2.0);
    const double sqA = 2.0 * std::sqrt(A) * alpha;

    double b0 = A * ((A + 1.0) + (A - 1.0) * cw + sqA);
    double b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cw);
    double b2 = A * ((A + 1.0) + (A - 1.0) * cw - sqA);
    double a0 = (A + 1.0) - (A - 1.0) * cw + sqA;
    double a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cw);
    double a2 = (A + 1.0) - (A - 1.0) * cw - sqA;

    normalize(a0, b0, b1, b2, a1, a2);
    out.b0 = b0; out.b1 = b1; out.b2 = b2; out.a1 = a1; out.a2 = a2;
}

double magnitudeAt(const Biquad& f, double fs, double freq) {
    const double w0 = 2.0 * kPi * freq / fs;
    const double c1 = std::cos(w0);
    const double c2 = std::cos(2.0 * w0);

    const double nr = f.b0 + f.b1 * c1 + f.b2 * c2;
    const double ni = -(f.b1 * std::sin(w0) + f.b2 * std::sin(2.0 * w0));
    const double dr = 1.0 + f.a1 * c1 + f.a2 * c2;
    const double di = -(f.a1 * std::sin(w0) + f.a2 * std::sin(2.0 * w0));

    return std::sqrt((nr * nr + ni * ni) / (dr * dr + di * di));
}

} // namespace moozik
