// 音のスペクトル確認: 各機種をアイドル → 高回転で鳴らし、帯域ごとのエネルギー比と
// 高域 (モスキート域) の最大成分、スペクトル重心 (シャープネスの目安) を表示する。
//   audio_check [engine_id ...]   (省略時は代表機種)
#include <algorithm>
#include <cmath>
#include <complex>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "audio/EngineAcousticsDSP.h"
#include "sim/EngineSimulation.h"

using namespace es;

static std::string readFile(const std::string& p) {
    std::ifstream f(p);
    std::stringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

static void fft(std::vector<std::complex<double>>& a) {
    const size_t n = a.size();
    for (size_t i = 1, j = 0; i < n; ++i) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    for (size_t len = 2; len <= n; len <<= 1) {
        double ang = -2 * M_PI / len;
        std::complex<double> wl(std::cos(ang), std::sin(ang));
        for (size_t i = 0; i < n; i += len) {
            std::complex<double> w(1);
            for (size_t k = 0; k < len / 2; ++k) {
                auto u = a[i + k], v = a[i + k + len / 2] * w;
                a[i + k] = u + v;
                a[i + k + len / 2] = u - v;
                w *= wl;
            }
        }
    }
}

int main(int argc, char** argv) {
    std::vector<std::string> ids(argv + 1, argv + argc);
    if (ids.empty()) ids = {"i1_250", "i4_20", "i4_20t", "v8_cross", "v12_60", "wankel2_13b", "r9_r1820", "jumo205",
                            "motor_pmsm", "turbofan", "turboshaft"};
    const float sr = 48000;
    const int N = 8192;
    std::printf("%-12s %7s %7s %7s %7s %7s | %9s %8s\n", "engine", "<250", "250-2k", "2-5k", "5-12k", ">12k", "maxHF[dB]", "centroid");
    int fails = 0;
    for (const auto& id : ids) {
        EngineSpec spec;
        std::string err;
        if (!loadEngineSpecFromText(readFile(std::string(ASSET_DIR) + "/" + id + ".json"), spec, err)) { std::printf("%s\n", err.c_str()); return 1; }
        EngineSimulation sim;
        AudioFeed feed;
        sim.init(spec, &feed);
        EngineAcousticsDSP dsp;
        dsp.setSampleRate(sr);
        dsp.setConfig(AcousticConfig::fromSpec(spec));
        Controls c = sim.controls();
        c.gear = 3;
        const int block = 480;
        std::vector<float> buf(block * 2), mono;
        for (int b = 0; b < 48000 * 7 / block; ++b) {
            double t = b * block / 48000.0;
            bool high = t > 2.5;
            c.targetRpm = spec.family == Family::Electric ? (high ? spec.redlineRpm * 0.7f : 2000.0f)
                                                           : (high ? spec.redlineRpm * 0.85f : spec.idleRpm);
            c.load = high ? 0.35f : 0.0f;
            sim.setControls(c);
            sim.step(block / 48000.0);
            dsp.render(buf.data(), block, feed);
            if (t > 1.0) for (int i = 0; i < block; ++i) mono.push_back(0.5f * (buf[2 * i] + buf[2 * i + 1]));
        }
        // Welch 平均パワースペクトル (Hann 窓, 50% 重なり)
        std::vector<double> P(N / 2 + 1, 0.0);
        int segs = 0;
        for (size_t s = 0; s + N <= mono.size(); s += N / 2, ++segs) {
            std::vector<std::complex<double>> a(N);
            for (int i = 0; i < N; ++i) a[i] = mono[s + i] * (0.5 - 0.5 * std::cos(2 * M_PI * i / (N - 1)));
            fft(a);
            for (int k = 0; k <= N / 2; ++k) P[k] += std::norm(a[k]);
        }
        double tot = 0, band[5] = {}, cen = 0, maxAll = 0, maxHF = 0;
        const double edges[5] = {250, 2000, 5000, 12000, 1e9};
        for (int k = 1; k <= N / 2; ++k) {
            double f = k * sr / N;
            tot += P[k];
            cen += f * P[k];
            int bi = 0;
            while (f >= edges[bi]) ++bi;
            band[bi] += P[k];
            maxAll = std::max(maxAll, P[k]);
            if (f >= 12000) maxHF = std::max(maxHF, P[k]);
        }
        double hfDb = 10 * std::log10(std::max(1e-30, maxHF / maxAll));
        std::printf("%-12s %6.1f%% %6.1f%% %6.1f%% %6.2f%% %6.3f%% | %9.1f %6.0fHz\n", id.c_str(), 100 * band[0] / tot, 100 * band[1] / tot,
                    100 * band[2] / tot, 100 * band[3] / tot, 100 * band[4] / tot, hfDb, cen / tot);
        if (getenv("AUDIO_STRICT") && hfDb > -60) ++fails;
    }
    if (getenv("AUDIO_STRICT")) std::printf(fails ? "AUDIO FAIL (%d)\n" : "AUDIO OK\n", fails);
    return fails ? 1 : 0;
}
