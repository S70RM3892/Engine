// ENGINE EMPIRE の経済バランス確認: ゲームの自動ダイナモ負荷 (GameController と同じ式) で
// 各エンジンを定常運転し、出力・燃料・純収入・回転域を表示する。
//   game_balance                 ゲームのツリー全体 (全開/30% の 2 条件)
//   game_balance a.json b.json   任意の定義 JSON (ゲームのチューン済み機など) を同条件で検証
#include <algorithm>
#include <cmath>
#include <filesystem>
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
    // 既定: ゲームのツリー全体 = ルート (耕運機, n 気筒生成器) + 組み込みカタログ全機種
    std::vector<std::string> catalog;
    for (const auto& f : std::filesystem::directory_iterator(ASSET_DIR))
        if (f.path().extension() == ".json" && f.path().filename() != "index.json") catalog.push_back(f.path().stem().string());
    std::sort(catalog.begin(), catalog.end());
    std::vector<Entry> roster{{"tiller", nullptr,
        R"({"name":"t","layout":"inline","cylinders":1,"boreMm":70,"strokeMm":60,"compressionRatio":9.0,"idleRpm":1300,"redlineRpm":6500})"}};
    for (const auto& id : catalog) roster.push_back({id.c_str(), id.c_str(), nullptr});
    int fails = 0;
    std::vector<std::string> files(argv + 1, argv + argc);
    std::vector<Entry> list = roster;
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
                // GameController.dynoLoad と同じ式
                double x = std::clamp((rpm - lo) / std::max(1.0, 0.9 * spec.redlineRpm - lo), 0.0, 2.0);
                c.load = static_cast<float>(x <= 1.0 ? 0.9 * std::pow(x, 1.6) : std::min(3.0, 0.9 + 6.0 * (x - 1.0)));
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
            // GameController.economy と同じ: 蒸気は効率 10% で石炭 0.05、スターリングは熱源無料
            double price = spec.family == Family::Electric ? 0.35 : 0.12;
            if (spec.cycle == Cycle::Steam) { fuel = out / 0.10; price = 0.05; }
            if (spec.cycle == Cycle::Stirling) fuel = 0;
            double net = out - fuel * price;
            std::printf("%-11s thr %.1f  rpm %6.0f (%.2f red)  out %8.1f kW  fuel %9.1f kW  net %9.1f /s\n", e.key, thr, rpm,
                        rpm / spec.redlineRpm, out, fuel, net);
            if (thr == 1.0 && (net <= 0 || !std::isfinite(net) || rpm > spec.redlineRpm * 1.2)) ++fails;
        }
    }
    std::printf(fails ? "BALANCE FAIL (%d)\n" : "BALANCE OK\n", fails);
    return fails ? 1 : 0;
}
