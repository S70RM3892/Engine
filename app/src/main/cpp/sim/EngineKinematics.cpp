#include "EngineKinematics.h"

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace es {

namespace {

inline double crankAngleImpl(const EngineSpec& spec, int crank, double thetaDeg) {
    const CrankDef& c = spec.cranks[crank];
    return c.dir * thetaDeg + c.phaseDeg;
}

// スライダクランクの厳密解: q をクランク中心基準の大端位置, u を軸方向単位ベクトルとして
// |s u - q| = L を満たす外側の解 s = q.u + sqrt(L^2 - |q|^2 + (q.u)^2)
inline float sliderSolve(float qy, float qz, float uy, float uz, float L) {
    float qu = qy * uy + qz * uz;
    float qq = qy * qy + qz * qz;
    float disc = L * L - qq + qu * qu;
    return qu + std::sqrt(std::max(disc, 0.0f));
}

}  // namespace

EngineKinematics::Geo2 EngineKinematics::solvePistonImpl(const EngineSpec& spec, int p, double thetaDeg) {
    const PistonDef& pd = spec.pistons[p];
    const ThrowDef& th = spec.throws[pd.throwIdx];
    const CrankDef& cr = spec.cranks[th.crank];
    float uy = std::cos(deg2rad(pd.axisDeg));
    float uz = std::sin(deg2rad(pd.axisDeg));
    Geo2 g{};
    if (pd.rod == RodType::Articulated && pd.master >= 0) {
        Geo2 m = solvePistonImpl(spec, pd.master, thetaDeg);
        // ナックルピン: マスターロッド方向をナックル角だけ回した方向に knuckleRadius
        float b = deg2rad(pd.knuckleAngleDeg);
        float cb = std::cos(b), sb = std::sin(b);
        float ky = m.by + pd.knuckleRadius * (m.dy * cb - m.dz * sb);
        float kz = m.bz + pd.knuckleRadius * (m.dy * sb + m.dz * cb);
        g.by = ky;
        g.bz = kz;
    } else {
        double a = deg2radD(crankAngleImpl(spec, th.crank, thetaDeg) + th.pinAngleDeg);
        g.by = cr.y + cr.radius * static_cast<float>(std::cos(a));
        g.bz = cr.z + cr.radius * static_cast<float>(std::sin(a));
    }
    g.s = sliderSolve(g.by - cr.y, g.bz - cr.z, uy, uz, pd.rodLength);
    g.wy = cr.y + g.s * uy;
    g.wz = cr.z + g.s * uz;
    float dy = g.wy - g.by, dz = g.wz - g.bz;
    float l = std::sqrt(dy * dy + dz * dz);
    g.dy = l > 1e-9f ? dy / l : uy;
    g.dz = l > 1e-9f ? dz / l : uz;
    return g;
}

double EngineKinematics::chamberVolumeImpl(const EngineSpec& spec, int c, double thetaDeg) {
    const ChamberDef& ch = spec.chambers[c];
    if (spec.family == Family::Wankel) {
        const WankelDef& w = spec.wankel;
        double vd = 3.0 * std::sqrt(3.0) * w.e * w.R * w.width;
        double phi = deg2radD(thetaDeg - ch.wankelPhaseDeg);
        return ch.clearance + 0.5 * vd * (1.0 - std::cos(2.0 * phi / 3.0));
    }
    double v = ch.clearance;
    for (size_t i = 0; i < ch.pistons.size(); ++i) {
        int p = ch.pistons[i];
        const PistonDef& pd = spec.pistons[p];
        float s = solvePistonImpl(spec, p, thetaDeg).s;
        if (ch.signs[i] > 0) v += ch.area * (pd.sMax - s);
        else v += ch.area * (s - pd.sMin);
    }
    return v;
}

void EngineKinematics::prepare(EngineSpec& spec) {
    // 1) 各ピストンのストローク端を数値的に求める (アーティキュレーテッドロッドは 2r より僅かに長い)
    const int N = 3600;
    for (size_t p = 0; p < spec.pistons.size(); ++p) {
        float mn = 1e9f, mx = -1e9f;
        for (int i = 0; i < N; ++i) {
            float s = solvePistonImpl(spec, static_cast<int>(p), i * 360.0 / N).s;
            mn = std::min(mn, s);
            mx = std::max(mx, s);
        }
        spec.pistons[p].sMin = mn;
        spec.pistons[p].sMax = mx;
    }

    // 2) 燃焼室の隙間容積
    spec.displacement = 0;
    int combustionCount = 0;
    for (size_t c = 0; c < spec.chambers.size(); ++c) {
        ChamberDef& ch = spec.chambers[c];
        double vd;
        if (spec.family == Family::Wankel) {
            const WankelDef& w = spec.wankel;
            vd = 3.0 * std::sqrt(3.0) * w.e * w.R * w.width;
        } else {
            ch.clearance = 0;
            double mn = 1e9, mx = -1e9;
            for (int i = 0; i < N; ++i) {
                double v = chamberVolumeImpl(spec, static_cast<int>(c), i * 360.0 / N);
                mn = std::min(mn, v);
                mx = std::max(mx, v);
            }
            vd = mx - mn;
            // 基準面をずらして最小容積を 0 に正規化
            ch.clearance = static_cast<float>(-mn);
        }
        double cr = std::max(1.5f, spec.compressionRatio);
        double vc;
        switch (ch.kind) {
            case ChamberKind::Combustion: vc = vd / (cr - 1.0); break;
            case ChamberKind::SteamHead:
            case ChamberKind::SteamCrank: vc = 0.08 * vd; break;
            default: vc = 0.25 * vd; break;  // スターリング: デッドボリューム
        }
        ch.clearance += static_cast<float>(vc);
        if (ch.kind == ChamberKind::Combustion || ch.kind == ChamberKind::SteamHead || ch.kind == ChamberKind::StirlingHot) {
            spec.displacement += static_cast<float>(vd);
            ++combustionCount;
        }
    }
    if (spec.cylinders == 0) spec.cylinders = combustionCount;

    // 3) 点火 (燃焼上死点) 角
    if (spec.family == Family::Wankel) {
        // 慣例に合わせ排気量は「1 作動室容積 × ローター数」(13B = 654cc × 2)
        spec.displacement /= 3.0f;
        for (auto& ch : spec.chambers) ch.firingDeg = static_cast<float>(wrapPos(ch.wankelPhaseDeg, 1080.0));
        return;
    }
    auto minVolAngle = [&](int c) {
        double best = 0, bestV = 1e9;
        for (int i = 0; i < N; ++i) {
            double a = i * 360.0 / N;
            double v = chamberVolumeImpl(spec, c, a);
            if (v < bestV) { bestV = v; best = a; }
        }
        // 放物線補間で 0.1deg 未満に詰める
        double h = 360.0 / N;
        double v0 = chamberVolumeImpl(spec, c, best - h), v2 = chamberVolumeImpl(spec, c, best + h);
        double den = v0 - 2 * bestV + v2;
        if (std::fabs(den) > 1e-18) best += 0.5 * h * (v0 - v2) / den;
        return wrapPos(best, 360.0);
    };

    std::vector<int> comb;
    std::vector<double> tdc(spec.chambers.size(), 0.0);
    for (size_t c = 0; c < spec.chambers.size(); ++c) {
        tdc[c] = minVolAngle(static_cast<int>(c));
        if (spec.chambers[c].kind == ChamberKind::Combustion) comb.push_back(static_cast<int>(c));
        spec.chambers[c].firingDeg = static_cast<float>(tdc[c]);
    }
    if (!spec.isFourStroke() || comb.empty()) return;

    // 4 ストローク: TDC 候補 t, t+360 のどちらで点火するかを点火順序から決める
    bool assigned = false;
    if (spec.firingOrder.size() == comb.size()) {
        std::vector<int> byNumber(comb.size() + 1, -1);
        bool ok = true;
        for (int c : comb) {
            int n = spec.chambers[c].number;
            if (n < 1 || n > static_cast<int>(comb.size()) || byNumber[n] >= 0) { ok = false; break; }
            byNumber[n] = c;
        }
        for (int n : spec.firingOrder)
            if (n < 1 || n > static_cast<int>(comb.size()) || byNumber[n] < 0) ok = false;
        if (ok) {
            double prev = 0;
            for (size_t k = 0; k < spec.firingOrder.size(); ++k) {
                int c = byNumber[spec.firingOrder[k]];
                double t = tdc[c];
                double chosen;
                if (k == 0) {
                    chosen = t;
                } else {
                    double d0 = wrapPos(t - prev, 720.0), d1 = wrapPos(t + 360.0 - prev, 720.0);
                    if (d0 < 0.5) d0 += 720.0;
                    if (d1 < 0.5) d1 += 720.0;
                    chosen = d0 <= d1 ? t : t + 360.0;
                }
                spec.chambers[c].firingDeg = static_cast<float>(wrapPos(chosen, 720.0));
                prev = chosen;
            }
            assigned = true;
        }
    }
    if (!assigned) {
        // 点火順序が無い場合: 各気筒の TDC 候補 (t / t+360) の組合せを全探索し、
        // 点火間隔の分散が最小 (= 最も等間隔) になる割り当てを選ぶ。18 気筒超は交互割り当て。
        const size_t n = comb.size();
        std::vector<double> angles(n);
        const bool radial = std::any_of(spec.pistons.begin(), spec.pistons.end(),
                                        [](const PistonDef& p) { return p.rod == RodType::Articulated; });
        auto cost = [&](std::vector<double> a) {
            std::sort(a.begin(), a.end());
            double ideal = 720.0 / a.size(), c = 0;
            for (size_t k = 0; k < a.size(); ++k) {
                double iv = (k + 1 < a.size() ? a[k + 1] : a[0] + 720.0) - a[k];
                c += (iv - ideal) * (iv - ideal);
            }
            return c;
        };
        if (radial) {
            // 星型: 各列 (= クランクスロー) の中で TDC 順に 1 つ飛ばし点火 (1-3-5-..-2-4-..)、
            // 列どうしの位相 (0/360) だけを探索して全体を等間隔に近づける
            std::vector<std::vector<int>> rows;
            std::vector<int> rowThrow;
            for (int c : comb) {
                int t = spec.pistons[spec.chambers[c].pistons[0]].throwIdx;
                auto it = std::find(rowThrow.begin(), rowThrow.end(), t);
                if (it == rowThrow.end()) { rowThrow.push_back(t); rows.push_back({c}); }
                else rows[it - rowThrow.begin()].push_back(c);
            }
            std::vector<double> base(spec.chambers.size(), 0.0);
            for (auto& row : rows) {
                std::stable_sort(row.begin(), row.end(), [&](int a, int b) { return tdc[a] < tdc[b] - 1e-3; });
                for (size_t k = 0; k < row.size(); ++k) base[row[k]] = tdc[row[k]] + ((k % 2) ? 360.0 : 0.0);
            }
            uint32_t best = 0;
            double bestCost = 1e30;
            for (uint32_t mask = 0; mask < (1u << (rows.size() - 1)); ++mask) {
                std::vector<double> a;
                for (size_t r = 0; r < rows.size(); ++r)
                    for (int c : rows[r]) a.push_back(wrapPos(base[c] + ((r > 0 && ((mask >> (r - 1)) & 1u)) ? 360.0 : 0.0), 720.0));
                double cc = cost(a);
                if (cc < bestCost - 1e-6) { bestCost = cc; best = mask; }
            }
            for (size_t r = 0; r < rows.size(); ++r)
                for (int c : rows[r])
                    spec.chambers[c].firingDeg = static_cast<float>(wrapPos(base[c] + ((r > 0 && ((best >> (r - 1)) & 1u)) ? 360.0 : 0.0), 720.0));
        } else if (n <= 18) {
            const double ideal = 720.0 / n;
            double bestCost = 1e30;
            uint32_t bestMask = 0;
            for (uint32_t mask = 0; mask < (1u << (n - 1)); ++mask) {
                for (size_t k = 0; k < n; ++k)
                    angles[k] = tdc[comb[k]] + ((k > 0 && ((mask >> (k - 1)) & 1u)) ? 360.0 : 0.0);
                std::sort(angles.begin(), angles.end());
                double cost = 0;
                for (size_t k = 0; k < n; ++k) {
                    double iv = (k + 1 < n ? angles[k + 1] : angles[0] + 720.0) - angles[k];
                    cost += (iv - ideal) * (iv - ideal);
                }
                if (cost < bestCost - 1e-6) { bestCost = cost; bestMask = mask; }
            }
            for (size_t k = 0; k < n; ++k)
                spec.chambers[comb[k]].firingDeg =
                    static_cast<float>(tdc[comb[k]] + ((k > 0 && ((bestMask >> (k - 1)) & 1u)) ? 360.0 : 0.0));
        } else {
            std::vector<int> sorted = comb;
            std::stable_sort(sorted.begin(), sorted.end(), [&](int a, int b) { return tdc[a] < tdc[b] - 1e-3; });
            for (size_t k = 0; k < sorted.size(); ++k)
                spec.chambers[sorted[k]].firingDeg = static_cast<float>(tdc[sorted[k]] + ((k % 2) ? 360.0 : 0.0));
        }
        // 実際の点火順序を記録
        std::vector<int> order = comb;
        std::sort(order.begin(), order.end(), [&](int a, int b) { return spec.chambers[a].firingDeg < spec.chambers[b].firingDeg; });
        spec.firingOrder.clear();
        for (int c : order) spec.firingOrder.push_back(spec.chambers[c].number);
    }
}

void EngineKinematics::init(const EngineSpec* spec) { spec_ = spec; }

EngineKinematics::Geo2 EngineKinematics::solvePiston(int p, double thetaDeg) const {
    return solvePistonImpl(*spec_, p, thetaDeg);
}

float EngineKinematics::pistonS(int p, double thetaDeg) const { return solvePiston(p, thetaDeg).s; }

double EngineKinematics::crankAngleDeg(int crank, double thetaDeg) const {
    return crankAngleImpl(*spec_, crank, thetaDeg);
}

double EngineKinematics::chamberVolume(int c, double thetaDeg) const {
    return chamberVolumeImpl(*spec_, c, thetaDeg);
}

double EngineKinematics::chamberDVdTheta(int c, double thetaDeg) const {
    const double h = 0.05;  // deg
    double v1 = chamberVolumeImpl(*spec_, c, thetaDeg + h);
    double v0 = chamberVolumeImpl(*spec_, c, thetaDeg - h);
    return (v1 - v0) / deg2radD(2 * h);
}

float EngineKinematics::valveLift(int c, bool intake, double thetaDeg) const {
    const EngineSpec& s = *spec_;
    if (!s.isFourStroke() || s.family != Family::Reciprocating) return 0.0f;
    const ChamberDef& ch = s.chambers[c];
    if (ch.kind != ChamberKind::Combustion) return 0.0f;
    const ValveTrainDef& v = s.valves;
    double a = wrapPos(thetaDeg - ch.firingDeg, 720.0);
    double open = intake ? v.ivoDeg : v.evoDeg;
    double close = intake ? v.ivcDeg : v.evcDeg;
    double dur = wrapPos(close - open, 720.0);
    double u = wrapPos(a - open, 720.0) / dur;
    if (u >= 1.0) return 0.0f;
    double sn = std::sin(kPiD * u);
    return static_cast<float>((intake ? v.liftIn : v.liftEx) * sn * sn);
}

void EngineKinematics::evaluate(double thetaDeg, KinState& out) const {
    const EngineSpec& s = *spec_;
    out.thetaDeg = thetaDeg;
    out.crankAngleRad.resize(s.cranks.size());
    for (size_t c = 0; c < s.cranks.size(); ++c)
        out.crankAngleRad[c] = static_cast<float>(deg2radD(crankAngleImpl(s, static_cast<int>(c), thetaDeg)));

    out.pistons.resize(s.pistons.size());
    out.slideValve.resize(s.pistons.size());
    for (size_t p = 0; p < s.pistons.size(); ++p) {
        const PistonDef& pd = s.pistons[p];
        const ThrowDef& th = s.throws[pd.throwIdx];
        Geo2 g = solvePiston(static_cast<int>(p), thetaDeg);
        PistonPose& pp = out.pistons[p];
        float x = th.x + pd.xOffset;
        pp.s = g.s;
        pp.bigEnd = {x, g.by, g.bz};
        pp.wrist = {x, g.wy, g.wz};
        pp.axis = {0, std::cos(deg2rad(pd.axisDeg)), std::sin(deg2rad(pd.axisDeg))};
        pp.rodDir = {0, g.dy, g.dz};
        if (pd.crosshead) {
            // 偏心輪駆動スライドバルブ: クランクより 90°+先行角 進んだ単振動
            const CrankDef& cr = s.cranks[th.crank];
            double a = deg2radD(crankAngleImpl(s, th.crank, thetaDeg) + th.pinAngleDeg - pd.axisDeg + 90.0 + 20.0);
            out.slideValve[p] = static_cast<float>(0.28 * cr.radius * std::cos(a));
        }
    }

    out.chamberVolume.resize(s.chambers.size());
    out.liftIn.resize(s.chambers.size());
    out.liftEx.resize(s.chambers.size());
    for (size_t c = 0; c < s.chambers.size(); ++c) {
        out.chamberVolume[c] = static_cast<float>(chamberVolume(static_cast<int>(c), thetaDeg));
        out.liftIn[c] = valveLift(static_cast<int>(c), true, thetaDeg);
        out.liftEx[c] = valveLift(static_cast<int>(c), false, thetaDeg);
    }

    if (s.family == Family::Wankel) {
        const WankelDef& w = s.wankel;
        out.rotorCenter.resize(w.rotors);
        out.rotorAngleRad.resize(w.rotors);
        out.eccentricAngleRad = static_cast<float>(deg2radD(thetaDeg));
        for (int r = 0; r < w.rotors; ++r) {
            double phi = deg2radD(thetaDeg + (r < static_cast<int>(w.rotorPhaseDeg.size()) ? w.rotorPhaseDeg[r] : 0.0));
            float x = r * (w.width * 1.6f);
            // ローター中心は偏心量 e で公転, 自転は偏心軸の 1/3 (内歯車/外歯車比 3:2 による)
            out.rotorCenter[r] = {x, w.e * static_cast<float>(std::cos(phi)), w.e * static_cast<float>(std::sin(phi))};
            out.rotorAngleRad[r] = static_cast<float>(phi / 3.0);
        }
    }
}

}  // namespace es
