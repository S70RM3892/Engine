// ホスト検証: カタログ全機種の読み込み・キネマティクス・熱力学・音響を実行し、
// 物理的な妥当性 (有限値・アイドル安定・幾何拘束) をチェックする。
#include <cmath>
#include <cstdio>
#include <algorithm>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "audio/EngineAcousticsDSP.h"
#include "core/CustomEngine.h"
#include "core/EngineSpec.h"
#include "core/Json.h"
#include "sim/EngineSimulation.h"

using namespace es;

static int g_fail = 0;
#define CHECK(cond, ...)                                  \
    do {                                                  \
        if (!(cond)) {                                    \
            std::printf("  FAIL %s:%d: ", __FILE__, __LINE__); \
            std::printf(__VA_ARGS__);                     \
            std::printf("\n");                            \
            ++g_fail;                                     \
        }                                                 \
    } while (0)

static std::string readFile(const std::string& p) {
    std::ifstream f(p);
    std::stringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

static void writeWav(const std::string& path, const std::vector<float>& st, int sr) {
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return;
    int n = static_cast<int>(st.size());
    int dataBytes = n * 2;
    auto w32 = [&](uint32_t v) { std::fwrite(&v, 4, 1, f); };
    auto w16 = [&](uint16_t v) { std::fwrite(&v, 2, 1, f); };
    std::fwrite("RIFF", 1, 4, f); w32(36 + dataBytes); std::fwrite("WAVEfmt ", 1, 8, f);
    w32(16); w16(1); w16(2); w32(sr); w32(sr * 4); w16(4); w16(16);
    std::fwrite("data", 1, 4, f); w32(dataBytes);
    for (float x : st) { int16_t s = static_cast<int16_t>(std::fmax(-1.f, std::fmin(1.f, x)) * 32767); w16(static_cast<uint16_t>(s)); }
    std::fclose(f);
}

static void testSliderCrank() {
    // 直4の厳密解を閉形式 x = r cos t + sqrt(L^2 - r^2 sin^2 t) と比較
    EngineSpec s;
    std::string err;
    bool ok = loadEngineSpecFromText(readFile(std::string(ASSET_DIR) + "/i4_20.json"), s, err);
    CHECK(ok, "load i4: %s", err.c_str());
    EngineKinematics k;
    k.init(&s);
    float r = s.stroke / 2, L = s.rodLength;
    double maxErr = 0;
    for (int d = 0; d < 360; d += 7) {
        double t = deg2radD(d);  // 1 番気筒 (ピン角 0, 軸角 0)
        double ref = r * std::cos(t) + std::sqrt(L * L - r * r * std::sin(t) * std::sin(t));
        maxErr = std::fmax(maxErr, std::fabs(ref - k.pistonS(0, d)));
    }
    CHECK(maxErr < 1e-6, "slider-crank error %g", maxErr);
    // 行程容積 = 1998cc 付近
    CHECK(std::fabs(s.displacement * 1e6 - 1998) < 5, "displacement %f", s.displacement * 1e6);
    // 点火順序 1-3-4-2 で 180° 等間隔
    std::vector<float> f(4);
    for (int c = 0; c < 4; ++c) f[s.chambers[c].number - 1] = s.chambers[c].firingDeg;
    CHECK(std::fabs(wrapPos(f[2] - f[0], 720) - 180) < 0.5 && std::fabs(wrapPos(f[3] - f[2], 720) - 180) < 0.5 &&
              std::fabs(wrapPos(f[1] - f[3], 720) - 180) < 0.5,
          "firing %f %f %f %f", f[0], f[1], f[2], f[3]);
}

static void testRodLengthConstraint(const EngineSpec& s, const std::string& id) {
    EngineKinematics k;
    k.init(&s);
    KinState st;
    double worst = 0;
    for (int d = 0; d < 720; d += 3) {
        k.evaluate(d, st);
        for (size_t p = 0; p < s.pistons.size(); ++p) {
            const PistonPose& pp = st.pistons[p];
            double len = length(pp.wrist - pp.bigEnd);
            worst = std::fmax(worst, std::fabs(len - s.pistons[p].rodLength));
            // ピストンはシリンダ軸上
            const CrankDef& cr = s.cranks[s.throws[s.pistons[p].throwIdx].crank];
            Vec3 rel = pp.wrist - Vec3{pp.wrist.x, cr.y, cr.z};
            double off = length(cross(rel, pp.axis));
            worst = std::fmax(worst, off);
        }
    }
    CHECK(worst < 1e-4, "%s rod constraint violated by %g m", id.c_str(), worst);
}

struct RunResult { float rpm, torque, power, eff; };

static RunResult runAt(EngineSimulation& sim, float target, float load, double seconds, bool link = true, float thr = 0) {
    Controls c = sim.controls();
    c.targetRpm = target;
    c.load = load;
    c.throttleLink = link;
    c.throttle = thr;
    sim.setControls(c);
    const double dt = 1.0 / 60.0;
    for (double t = 0; t < seconds; t += dt) sim.step(dt);
    const Telemetry& tl = sim.telemetry();
    return {tl.rpm, tl.torqueNm, tl.powerKW, tl.efficiency};
}


// カスタム n 気筒: 各レイアウト・気筒数で生成→読込→(4 スト/2 スト)等間隔点火を確認
static void testCustomEngines() {
    struct Case { const char* layout; int nMin, nMax, step; const char* cycle; bool expectEven; };
    const Case cases[] = {{"inline", 1, 16, 1, "otto4", true}, {"inline", 1, 8, 1, "otto2", true},
                          {"v", 2, 24, 2, "otto4", true},      {"flat", 2, 16, 2, "otto4", true},
                          {"radial", 3, 11, 1, "otto4", false}, {"opposed", 1, 12, 1, "diesel2", false},
                          {"wankel", 1, 4, 1, "otto4", false}};
    int count = 0;
    for (const Case& cs : cases) {
        for (int n = cs.nMin; n <= cs.nMax; n += cs.step) {
            for (float bank : {60.0f, 90.0f}) {
                if (std::string(cs.layout) != "v" && bank != 60.0f) continue;
                CustomEngineParams p;
                p.layout = cs.layout;
                p.cylinders = n;
                p.cycle = cs.cycle;
                p.bankAngle = bank;
                p.rows = std::string(cs.layout) == "radial" ? 2 : 1;
                std::string json, err;
                if (!buildCustomEngineJson(p, json, err)) { CHECK(false, "custom %s %d: %s", cs.layout, n, err.c_str()); continue; }
                EngineSpec spec;
                if (!loadEngineSpecFromText(json, spec, err)) { CHECK(false, "custom load %s %d: %s", cs.layout, n, err.c_str()); continue; }
                ++count;
                if (cs.expectEven && n > 1) {
                    std::vector<double> f;
                    for (auto& ch : spec.chambers) f.push_back(ch.firingDeg);
                    std::sort(f.begin(), f.end());
                    double per = spec.cycleDeg(), ideal = per / f.size(), worst = 0;
                    for (size_t k = 0; k < f.size(); ++k) {
                        double iv = (k + 1 < f.size() ? f[k + 1] : f[0] + per) - f[k];
                        worst = std::fmax(worst, std::fabs(iv - ideal));
                    }
                    CHECK(worst < 1.0, "custom %s %s n=%d bank=%.0f uneven firing (dev %.1f deg)", cs.layout, cs.cycle, n, bank, worst);
                }
                AudioFeed feed;
                EngineSimulation sim;
                sim.init(spec, &feed);
                RunResult r = runAt(sim, spec.redlineRpm * 0.6f, 0.2f, 2.0);
                CHECK(std::isfinite(r.torque) && r.rpm > 0.2f * spec.redlineRpm, "custom %s n=%d run rpm %f", cs.layout, n, r.rpm);
            }
        }
    }
    std::printf("custom engines generated & simulated: %d\n", count);
}

// 車両モード: AT/MT で発進し、ブレーキ停止後もエンストしないこと
static void testVehicle(const EngineSpec& spec, const std::string& id) {
    if (spec.drivetrain.propeller || spec.family == Family::Turbine || !spec.drivetrain.hasGearbox) return;
    for (int mode : {1, 2}) {
        AudioFeed feed;
        EngineSimulation sim;
        sim.init(spec, &feed);
        Controls c = sim.controls();
        c.driveMode = mode; c.gear = 1; c.throttleLink = false; c.throttle = 1.0f;
        if (spec.family == Family::Electric) c.targetRpm = 0;
        sim.setControls(c);
        int cd = 0;
        for (int i = 0; i < 60 * 12; ++i) {
            const Telemetry& t = sim.telemetry();
            if (mode == 1 && --cd <= 0 && t.rpm > spec.redlineRpm * 0.93f && c.gear < static_cast<int>(spec.drivetrain.gears.size())) {
                ++c.gear; sim.setControls(c); cd = 40;
            }
            sim.step(1 / 60.0);
        }
        float v = sim.telemetry().speedKmh;
        CHECK(std::isfinite(v) && v > 30, "%s vehicle mode %d speed %f", id.c_str(), mode, v);
        c.throttle = 0; c.brake = 1; sim.setControls(c);
        for (int i = 0; i < 60 * 12; ++i) sim.step(1 / 60.0);
        float rpm = sim.telemetry().rpm;
        CHECK(sim.telemetry().speedKmh < 0.5f, "%s mode %d did not stop", id.c_str(), mode);
        if (spec.family != Family::Electric && spec.cycle != Cycle::Steam && spec.cycle != Cycle::Stirling)
            CHECK(rpm > 0.6f * spec.idleRpm, "%s mode %d stalled at stop (%f rpm)", id.c_str(), mode, rpm);
    }
}

int main(int argc, char** argv) {
    bool wav = argc > 1 && std::strcmp(argv[1], "--wav") == 0;
    testSliderCrank();
    testCustomEngines();
    JsonValue idx;
    std::string err;
    CHECK(parseJson(readFile(std::string(ASSET_DIR) + "/index.json"), idx, err), "index: %s", err.c_str());
    const JsonValue& list = idx["engines"];
    std::printf("%-16s %5s %7s %6s | %7s %7s | %7s %7s %6s %6s %5s | %s\n", "id", "cyl", "disp[L]", "fire", "idle", "tgtIdle",
                "WOTrpm", "Tq[Nm]", "P[kW]", "eff", "pmax", "firing angles");
    for (size_t i = 0; i < list.size(); ++i) {
        std::string file = list[i].str("file", "");
        std::string id = list[i].str("id", "");
        EngineSpec spec;
        if (!loadEngineSpecFromText(readFile(std::string(ASSET_DIR) + "/" + file), spec, err)) {
            CHECK(false, "%s: %s", id.c_str(), err.c_str());
            continue;
        }
        if (spec.family == Family::Reciprocating) testRodLengthConstraint(spec, id);
        testVehicle(spec, id);
        AudioFeed feed;
        EngineSimulation sim;
        sim.init(spec, &feed);
        float idleTarget = spec.family == Family::Electric ? 1000.0f : spec.idleRpm;
        RunResult idle = runAt(sim, idleTarget, 0.0f, 8.0);
        // 全開 + 負荷: レッドラインの 85% 付近を狙う
        float wotTarget = spec.redlineRpm * 0.85f;
        RunResult wot = runAt(sim, wotTarget, 0.0f, 3.0);
        wot = runAt(sim, wotTarget, 0.0f, 0.1, false, 1.0f);
        // 手動全開で負荷をかけ、回転が釣り合った点の出力を見る
        RunResult loaded = runAt(sim, wotTarget, 0.6f, 4.0, false, 1.0f);
        const Telemetry& tl = sim.telemetry();
        bool finite = std::isfinite(idle.rpm) && std::isfinite(loaded.torque) && std::isfinite(loaded.power);
        CHECK(finite, "%s non-finite", id.c_str());
        // 2 スト (不整燃焼) と外燃機関 (熱容量による遅れ) は許容幅を広げる
        float tol = (spec.cycle == Cycle::Otto2 || spec.cycle == Cycle::Stirling || spec.cycle == Cycle::Steam) ? 0.4f : 0.1f;
        CHECK(std::fabs(idle.rpm - idleTarget) < idleTarget * tol + 30, "%s idle %f (target %f)", id.c_str(), idle.rpm, idleTarget);
        CHECK(loaded.rpm > 0.1f * spec.redlineRpm, "%s stalled under load (%f rpm)", id.c_str(), loaded.rpm);
        std::string fa;
        for (auto& ch : spec.chambers) { char b[32]; std::snprintf(b, sizeof b, "%d:%.0f ", ch.number, ch.firingDeg); fa += b; if (fa.size() > 60) { fa += "..."; break; } }
        std::string fo;
        for (int n : spec.firingOrder) fo += std::to_string(n) + "-";
        std::printf("%-16s %5d %7.2f %6s | %7.0f %7.0f | %7.0f %7.1f %6.1f %6.2f %5.0f | %s\n", id.c_str(), spec.cylinders,
                    spec.displacement * 1000, "", idle.rpm, idleTarget, loaded.rpm, loaded.torque, loaded.power, loaded.eff,
                    tl.peakPressureBar, fa.c_str());
        if (!fo.empty()) std::printf("%-16s   firing order %s\n", "", fo.c_str());

        // 音響: 数秒生成して有限・無音でないことを確認
        {
            EngineSimulation s2;
            AudioFeed f2;
            s2.init(spec, &f2);
            EngineAcousticsDSP dsp;
            dsp.setSampleRate(48000);
            dsp.setConfig(AcousticConfig::fromSpec(spec));
            std::vector<float> all;
            const int block = 480;
            std::vector<float> buf(block * 2);
            Controls c = s2.controls();
            double rms = 0, peak = 0;
            int nblocks = 48000 * 6 / block;
            for (int b = 0; b < nblocks; ++b) {
                double t = b * block / 48000.0;
                c.targetRpm = spec.family == Family::Electric ? (t < 1 ? 0 : std::fmin(spec.redlineRpm * 0.6, (t - 1) * 3000))
                                                               : (t < 2 ? idleTarget : (t < 4.5 ? spec.redlineRpm * 0.8f : idleTarget));
                c.load = (t > 2 && t < 4.5) ? 0.3f : 0.0f;
                c.gear = 3;
                s2.setControls(c);
                s2.step(block / 48000.0);
                dsp.render(buf.data(), block, f2);
                for (float x : buf) { rms += x * x; peak = std::fmax(peak, std::fabs(x)); }
                if (wav) all.insert(all.end(), buf.begin(), buf.end());
            }
            rms = std::sqrt(rms / (nblocks * block * 2.0));
            std::printf("%-16s   audio rms %.3f peak %.3f\n", "", rms, peak);
            CHECK(std::isfinite(rms) && rms > 0.003 && rms < 0.6, "%s audio rms %f", id.c_str(), rms);
            if (wav) writeWav("/tmp/claude-0/-home-user-Engine/4353ef92-5816-57b5-a8ae-c283c09ab9b3/scratchpad/wav/" + id + ".wav", all, 48000);
        }
    }
    std::printf("\n%s (%d failures)\n", g_fail ? "FAILED" : "ALL PASSED", g_fail);
    return g_fail ? 1 : 0;
}
