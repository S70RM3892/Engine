#include "CustomEngine.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <sstream>
#include <vector>

#include "MathUtil.h"

namespace es {

namespace {

std::string num(double v) {
    char b[32];
    std::snprintf(b, sizeof b, "%.6g", v);
    return b;
}

std::string arr(const std::vector<double>& v) {
    std::string s = "[";
    for (size_t i = 0; i < v.size(); ++i) s += (i ? "," : "") + num(v[i]);
    return s + "]";
}

std::string esc(const std::string& s) {
    std::string o;
    for (char c : s) {
        if (c == '"' || c == '\\') o += '\\';
        if (static_cast<unsigned char>(c) >= 0x20) o += c;
    }
    return o;
}

double norm360(double a) { return wrapPos(a, 360.0); }

// 直列の等間隔点火ピン配置。
// 4 スト偶数: 対称ペア (i, n-1-i) で同じピン角、ペア m の TDC = m·720/n (直4 = 0/180/180/0, 直6 = 0/120/240/240/120/0)
// 4 スト奇数: TDC = j·720/n mod 360 が全て異なるので順に割り当てる
// 2 スト: TDC = j·360/n
std::vector<double> inlinePins(int n, bool twoStroke) {
    std::vector<double> pins(n, 0.0);
    if (twoStroke) {
        for (int i = 0; i < n; ++i) pins[i] = norm360(-360.0 * i / n);
        return pins;
    }
    if (n == 8) return {0, 180, 270, 90, 90, 270, 180, 0};  // 定番の 2-4-2 クランク
    if (n % 2 == 0) {
        for (int i = 0; i < n / 2; ++i) {
            double v = norm360(-720.0 * i / n);
            pins[i] = v;
            pins[n - 1 - i] = v;
        }
    } else {
        for (int i = 0; i < n; ++i) pins[i] = norm360(-720.0 * i / n);
    }
    return pins;
}

}  // namespace

bool parseCustomParams(const JsonValue& j, CustomEngineParams& p, std::string& err) {
    p.name = j.str("name", p.name);
    p.layout = j.str("layout", p.layout);
    p.cylinders = static_cast<int>(j.num("cylinders", p.cylinders));
    p.rows = static_cast<int>(j.num("rows", p.rows));
    p.bankAngle = static_cast<float>(j.num("bankAngle", p.bankAngle));
    p.evenFire = j.boolean("evenFire", p.evenFire);
    p.crossplane = j.boolean("crossplane", p.crossplane);
    p.cycle = j.str("cycle", p.cycle);
    p.boreMm = static_cast<float>(j.num("boreMm", p.boreMm));
    p.strokeMm = static_cast<float>(j.num("strokeMm", p.strokeMm));
    p.rodRatio = static_cast<float>(j.num("rodRatio", p.rodRatio));
    p.compressionRatio = static_cast<float>(j.num("compressionRatio", p.compressionRatio));
    p.idleRpm = static_cast<float>(j.num("idleRpm", p.idleRpm));
    p.redlineRpm = static_cast<float>(j.num("redlineRpm", p.redlineRpm));
    p.induction = j.str("induction", p.induction);
    p.boostBar = static_cast<float>(j.num("boostBar", p.boostBar));
    p.valvesPerCyl = static_cast<int>(j.num("valvesPerCyl", p.valvesPerCyl));
    (void)err;
    return true;
}

bool buildCustomEngineJson(const CustomEngineParams& in, std::string& out, std::string& err) {
    CustomEngineParams p = in;
    const std::string& L = p.layout;
    int n = p.cylinders;
    auto fail = [&](const std::string& m) { err = m; return false; };
    if (L == "inline" && (n < 1 || n > 16)) return fail("直列は 1〜16 気筒");
    if (L == "v" && (n < 2 || n > 24 || n % 2)) return fail("V 型は 2〜24 の偶数気筒");
    if (L == "flat" && (n < 2 || n > 16 || n % 2)) return fail("水平対向は 2〜16 の偶数気筒");
    if (L == "radial" && (n < 3 || n > 11 || p.rows < 1 || p.rows > 4)) return fail("星型は 1 列 3〜11 気筒 × 1〜4 列");
    if (L == "opposed" && (n < 1 || n > 12)) return fail("対向ピストンは 1〜12 気筒");
    if (L == "wankel" && (n < 1 || n > 4)) return fail("ロータリーは 1〜4 ローター");
    if (L != "inline" && L != "v" && L != "flat" && L != "radial" && L != "opposed" && L != "wankel") return fail("未知のレイアウト: " + L);
    if (p.boreMm < 20 || p.boreMm > 600 || p.strokeMm < 20 || p.strokeMm > 800) return fail("ボア/ストロークが範囲外");
    p.rodRatio = std::clamp(p.rodRatio, 1.3f, 4.0f);
    p.compressionRatio = std::clamp(p.compressionRatio, 3.0f, 25.0f);
    p.redlineRpm = std::clamp(p.redlineRpm, 500.0f, 25000.0f);
    p.idleRpm = std::clamp(p.idleRpm, 100.0f, p.redlineRpm * 0.5f);
    // 往復慣性を考慮した現実的な最高回転の目安 (平均ピストン速度 25m/s) を超えたら警告せず切り詰め
    double maxByPistonSpeed = 25.0 / (2.0 * p.strokeMm / 1000.0) * 60.0;
    if (L != "wankel") p.redlineRpm = std::min<float>(p.redlineRpm, static_cast<float>(maxByPistonSpeed));

    const bool twoStroke = p.cycle == "otto2" || L == "opposed";
    const double b = p.boreMm / 1000.0, s = p.strokeMm / 1000.0;
    int chambers = L == "radial" ? n * p.rows : (L == "wankel" ? 3 * n : n);
    if (chambers > 64) return fail("燃焼室数が 64 を超える");

    double cylVol = kPiD / 4 * b * b * s;
    double dispL = cylVol * chambers * 1000.0 * (L == "opposed" ? 2 : 1);
    if (L == "wankel") dispL = 0.654 * n;

    std::ostringstream j;
    j << "{\"id\":\"custom_" << L << n << (L == "radial" ? "x" + std::to_string(p.rows) : "") << "\",";
    j << "\"name\":\"" << esc(p.name) << "\",\"category\":\"カスタム (Custom)\",";
    j << "\"description\":\"ユーザー定義: " << esc(L) << " " << chambers << (L == "wankel" ? " 作動室" : " 気筒") << " / "
      << num(std::round(dispL * 100) / 100) << " L\",";
    if (L == "wankel") {
        j << "\"family\":\"wankel\",";
    } else {
        j << "\"family\":\"reciprocating\",";
        std::string cyc = L == "opposed" ? "diesel2" : p.cycle;
        j << "\"cycle\":\"" << cyc << "\",";
    }
    bool diesel = p.cycle == "diesel4" || L == "opposed";
    j << "\"bore\":" << num(b) << ",\"stroke\":" << num(s) << ",\"rodLength\":" << num(s * p.rodRatio) << ",";
    j << "\"compressionRatio\":" << num(diesel ? std::max(p.compressionRatio, 14.0f) : p.compressionRatio) << ",";
    j << "\"idleRpm\":" << num(p.idleRpm) << ",\"redlineRpm\":" << num(p.redlineRpm) << ",";
    // 気筒数が少ないほどトルク変動が大きいので重いフライホイールにする
    double inertia = (0.05 + 0.06 * dispL + (L == "radial" ? 0.1 * dispL : 0)) * (1.0 + 2.0 / std::max(1, chambers));
    j << "\"inertia\":" << num(inertia) << ",";
    j << "\"reciprocatingMass\":" << num(std::max(0.08, 700.0 * b * b * b + 0.1)) << ",";
    if (twoStroke) j << "\"sparkAdvance\":" << (diesel ? 6 : 18) << ",\"burnDuration\":45,";
    if (L == "wankel") j << "\"sparkAdvance\":12,\"burnDuration\":90,";

    if (L == "wankel") {
        std::vector<double> ph;
        const double four[4] = {0, 180, 90, 270};
        for (int r = 0; r < n; ++r) ph.push_back(n == 4 ? four[r] : 360.0 * r / n);
        j << "\"wankel\":{\"rotors\":" << n << ",\"rotorPhases\":" << arr(ph)
          << ",\"eccentricity\":0.015,\"generatingRadius\":0.105,\"width\":0.08},";
    } else {
        j << "\"layout\":{";
        if (L == "inline") {
            j << "\"type\":\"inline\",\"count\":" << n << ",\"throwAngles\":" << arr(inlinePins(n, twoStroke));
        } else if (L == "v") {
            int half = n / 2;
            std::vector<double> pins;
            if (p.crossplane && n == 8) pins = {0, 90, 270, 180};
            else pins = inlinePins(half, twoStroke);
            // バンク B の TDC がバンク A より 720/n (2 スト: 360/n) 遅れるようにピンをずらす
            double split = p.evenFire ? norm360(p.bankAngle - (twoStroke ? 360.0 : 720.0) / n) : 0.0;
            if (p.crossplane && n == 8) split = norm360(p.bankAngle - 90.0);
            if (split > 180) split -= 360;
            j << "\"type\":\"v\",\"count\":" << n << ",\"bankAngle\":" << num(p.bankAngle) << ",\"throwAngles\":" << arr(pins);
            if (std::fabs(split) > 0.01) j << ",\"splitPin\":" << num(split);
        } else if (L == "flat") {
            int half = n / 2;
            std::vector<double> pins, sides;
            for (int m = 0; m < half; ++m) {
                double v = twoStroke ? 360.0 * m / half : 720.0 * m / n;
                double pA = norm360(90.0 - v);
                pins.push_back(pA);
                pins.push_back(norm360(pA + 180.0));
                sides.push_back(1);
                sides.push_back(-1);
            }
            j << "\"type\":\"flat\",\"count\":" << n << ",\"throwAngles\":" << arr(pins) << ",\"sides\":" << arr(sides);
        } else if (L == "radial") {
            std::vector<double> rt;
            const double r4[4] = {0, 180, 90, 270};
            for (int r = 0; r < p.rows; ++r) rt.push_back(p.rows == 3 ? 120.0 * r : r4[r]);
            j << "\"type\":\"radial\",\"perRow\":" << n << ",\"rows\":" << p.rows << ",\"rowThrowAngles\":" << arr(rt);
        } else {  // opposed
            std::vector<double> pins;
            for (int i = 0; i < n; ++i) pins.push_back(norm360(360.0 * ((i * (n / 2 + 1)) % n) / n));
            j << "\"type\":\"opposed\",\"count\":" << n << ",\"throwAngles\":" << arr(pins) << ",\"exhaustLead\":12";
        }
        j << "},";
        bool ohv = L == "radial";
        int vIn = p.valvesPerCyl >= 4 ? 2 : 1, vEx = p.valvesPerCyl >= 3 ? 2 : 1;
        if (p.valvesPerCyl == 5) vIn = 3;
        j << "\"valvetrain\":{\"type\":\"" << (ohv ? "OHV" : "DOHC") << "\",\"intakeValves\":" << vIn << ",\"exhaustValves\":" << vEx << "},";
    }
    if (p.induction == "turbo" || p.induction == "roots")
        j << "\"induction\":{\"type\":\"" << p.induction << "\",\"maxBoostBar\":" << num(std::clamp(p.boostBar, 0.1f, 3.0f)) << "},";
    {
        std::vector<double> runners(chambers, 0.5);
        j << "\"exhaust\":{\"runnerLengths\":" << arr(runners) << ",\"collectorLength\":0.5,\"tailpipeLength\":1.6,\"mufflerVolume\":"
          << num(std::clamp(0.006 * dispL + 0.004, 0.003, 0.08)) << "},";
    }
    if (L == "radial") j << "\"drivetrain\":{\"type\":\"propeller\",\"reduction\":0.6,\"blades\":3,\"diameter\":3.2},";
    else j << "\"drivetrain\":{\"type\":\"gearbox\",\"gears\":[3.6,2.1,1.45,1.1,0.87,0.72],\"finalDrive\":3.9},";
    j << "\"vehicle\":{\"massKg\":" << num(std::clamp(900.0 + 180.0 * dispL, 900.0, 3000.0)) << "}";
    j << "}";
    out = j.str();
    return true;
}

}  // namespace es
