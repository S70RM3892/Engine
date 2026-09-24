// サンプル単位 DSP の基本部品 (割り当て無し・分岐少なめ・オーディオスレッド安全)
#pragma once

#include <cmath>
#include <cstdint>
#include <cstring>

namespace es::dsp {

constexpr float kPi = 3.14159265358979f;

struct Noise {
    uint32_t s = 0x9E3779B9u;
    float white() {
        s ^= s << 13;
        s ^= s >> 17;
        s ^= s << 5;
        return static_cast<float>(static_cast<int32_t>(s)) * (1.0f / 2147483648.0f);
    }
};

struct OnePoleLP {
    float a = 0, z = 0;
    void setCutoff(float hz, float sr) { a = 1.0f - std::exp(-2.0f * kPi * hz / sr); }
    float process(float x) { z += a * (x - z); return z; }
};

struct DCBlocker {
    float x1 = 0, y1 = 0, r = 0.995f;
    float process(float x) {
        float y = x - x1 + r * y1;
        x1 = x;
        y1 = y;
        return y;
    }
};

// RBJ biquad (Direct Form I)
struct Biquad {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    float x1 = 0, x2 = 0, y1 = 0, y2 = 0;
    void reset() { x1 = x2 = y1 = y2 = 0; }
    void bandpass(float hz, float q, float sr) {
        float w = 2 * kPi * std::fmin(hz, 0.45f * sr) / sr, al = std::sin(w) / (2 * q), c = std::cos(w);
        float a0 = 1 + al;
        b0 = al / a0; b1 = 0; b2 = -al / a0; a1 = -2 * c / a0; a2 = (1 - al) / a0;
    }
    void lowpass(float hz, float q, float sr) {
        float w = 2 * kPi * std::fmin(hz, 0.45f * sr) / sr, al = std::sin(w) / (2 * q), c = std::cos(w);
        float a0 = 1 + al;
        b0 = (1 - c) * 0.5f / a0; b1 = (1 - c) / a0; b2 = b0; a1 = -2 * c / a0; a2 = (1 - al) / a0;
    }
    void highpass(float hz, float q, float sr) {
        float w = 2 * kPi * std::fmin(hz, 0.45f * sr) / sr, al = std::sin(w) / (2 * q), c = std::cos(w);
        float a0 = 1 + al;
        b0 = (1 + c) * 0.5f / a0; b1 = -(1 + c) / a0; b2 = b0; a1 = -2 * c / a0; a2 = (1 - al) / a0;
    }
    void peak(float hz, float q, float gainDb, float sr) {
        float A = std::pow(10.0f, gainDb / 40.0f);
        float w = 2 * kPi * std::fmin(hz, 0.45f * sr) / sr, al = std::sin(w) / (2 * q), c = std::cos(w);
        float a0 = 1 + al / A;
        b0 = (1 + al * A) / a0; b1 = -2 * c / a0; b2 = (1 - al * A) / a0; a1 = -2 * c / a0; a2 = (1 - al / A) / a0;
    }
    float process(float x) {
        float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1; x1 = x; y2 = y1; y1 = y;
        return y;
    }
};

// 固定長リングバッファ遅延 (長さは 2 の冪)
template <int N>
struct Delay {
    static_assert((N & (N - 1)) == 0, "power of two");
    float buf[N];
    int w = 0;
    Delay() { clear(); }
    void clear() { std::memset(buf, 0, sizeof(buf)); w = 0; }
    void push(float x) { buf[w] = x; w = (w + 1) & (N - 1); }
    // d サンプル前の値 (線形補間, 1 <= d < N-1)
    float tap(float d) const {
        float fi = static_cast<float>(w) - d;
        int i0 = static_cast<int>(std::floor(fi));
        float fr = fi - static_cast<float>(i0);
        float a = buf[i0 & (N - 1)], b = buf[(i0 + 1) & (N - 1)];
        return a + (b - a) * fr;
    }
};

// 正弦オシレータ (位相累積)
struct Osc {
    float ph = 0;
    float sine(float hz, float sr) {
        ph += hz / sr;
        ph -= std::floor(ph);
        return std::sin(2 * kPi * ph);
    }
};

inline float softClip(float x) { return std::tanh(x); }

}  // namespace es::dsp
