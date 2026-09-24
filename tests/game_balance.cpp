// ENGINE EMPIRE の経済バランス確認: ゲームの自動ダイナモ負荷 (GameController と同じ式) で
// 各エンジンを定常運転し、出力・燃料・純収入・回転域を表示する。
//   game_balance                 組み込みロスター (全開/30% の 2 条件)
//   game_balance a.json b.json   任意の定義 JSON (ゲームのチューン済み機など) を同条件で検証
#include <cmath>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "core/CustomEngine.h"
#include "sim/EngineSimulation.h"

using namespace es;

static std::string readFile(const std::string& p) {
    std::ifstream f(p);
    std::stringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

struct Entry { const char* key; const char* catalog; const char* custom; };

int main(int argc, char** argv) {
    const Entry roster[] = {
        {"tiller", nullptr, R"({"name":"t","layout":"inline","cylinders":1,"boreMm":70,"strokeMm":60,"compressionRatio":9.0,"idleRpm":1300,"redlineRpm":6500})"},
        {"twin", nullptr, R"({"name":"t","layout":"inline","cylinders":2,"boreMm":80,"strokeMm":70,"compressionRatio":11.5,"idleRpm":1200,"redlineRpm":9500})"},
        {"rotary", "wankel2_13b", nullptr},
        {"i4t", "i4_20t", nullptr},
        {"flat6", nullptr, R"({"name":"t","layout":"flat","cylinders":6,"boreMm":91,"strokeMm":76,"compressionRatio":12.5,"idleRpm":850,"redlineRpm":9000})"},
        {"pmsm", "motor_pmsm", nullptr},
        {"v8", nullptr, R"({"name":"t","layout":"v","cylinders":8,"bankAngle":90,"crossplane":true,"boreMm":93,"strokeMm":92.7,"compressionRatio":11.0,"idleRpm":650,"redlineRpm":7000})"},
        {"v12", nullptr, R"({"name":"t","layout":"v","cylinders":12,"bankAngle":60,"boreMm":89,"strokeMm":80,"compressionRatio":11.2,"idleRpm":800,"redlineRpm":8500})"},
        {"radial", "r9_r1820", nullptr},
        {"jumo", "jumo205", nullptr},
        {"deltic", "deltic18", nullptr},
        {"turboshaft", "turboshaft", nullptr},
        {"turbofan", "turbofan", nullptr},
    };
    int fails = 0;
    std::vector<std::string> files(argv + 1, argv + argc);
    std::vector<Entry> list(std::begin(roster), std::end(roster));
    if (!files.empty()) {
        list.clear();
        for (const auto& f : files) list.push_back({f.c_str(), f.c_str(), nullptr});
    }
    for (const Entry& e : list) {
        std::string json, err;
        if (!files.empty()) {
            json = readFile(e.catalog);
        } else if (e.custom) {
            JsonValue j;
            CustomEngineParams p;
            if (!parseJson(e.custom, j, err) || !parseCustomParams(j, p, err) || !buildCustomEngineJson(p, json, err)) {
                std::printf("%s: %s\n", e.key, err.c_str());
                return 1;
            }
        } else {
            json = readFile(std::string(ASSET_DIR) + "/" + e.catalog + ".json");
        }
        EngineSpec spec;
        if (!loadEngineSpecFromText(json, spec, err)) { std::printf("%s: %s\n", e.key, err.c_str()); return 1; }
        for (double thr : {1.0, 0.3}) {
            AudioFeed feed;
            EngineSimulation sim;
            sim.init(spec, &feed);
            Controls c = sim.controls();
            c.throttleLink = false;
            c.autoStart = true;
            c.gear = 1;
            double sumOut = 0, sumFuel = 0, sumRpm = 0;
            int n = 0;
            const double dt = 1 / 60.0;
            for (int i = 0; i < 60 * 25; ++i) {
                const Telemetry& t = sim.telemetry();
                double rpm = t.rpm;
                bool elec = spec.family == Family::Electric;
                double lo = elec ? 0.0 : spec.idleRpm;
                double x = std::clamp((rpm - lo) / std::max(1.0, 0.9 * spec.redlineRpm - lo), 0.0, 1.5);
                c.load = static_cast<float>(std::clamp(std::pow(x, 1.6) * 0.9, 0.0, 1.3));
                c.throttle = static_cast<float>(thr);
                sim.setControls(c);
                sim.step(dt);
                if (i > 60 * 15) {
                    const Telemetry& u = sim.telemetry();
                    double out = spec.family == Family::Turbine ? u.powerKW + u.thrustKN * 250.0 : u.loadNm * u.rpm * 2 * M_PI / 60 / 1000;
                    sumOut += out;
                    sumFuel += u.fuelKW;
                    sumRpm += u.rpm;
                    ++n;
                }
            }
            double out = sumOut / n, fuel = sumFuel / n, rpm = sumRpm / n;
            double price = spec.family == Family::Electric ? 0.35 : 0.12;
            double net = out - fuel * price;
            std::printf("%-11s thr %.1f  rpm %6.0f (%.2f red)  out %8.1f kW  fuel %9.1f kW  net %9.1f /s\n", e.key, thr, rpm,
                        rpm / spec.redlineRpm, out, fuel, net);
            if (thr == 1.0 && (net <= 0 || !std::isfinite(net) || rpm > spec.redlineRpm * 1.2)) ++fails;
        }
    }
    std::printf(fails ? "BALANCE FAIL (%d)\n" : "BALANCE OK\n", fails);
    return fails ? 1 : 0;
}
