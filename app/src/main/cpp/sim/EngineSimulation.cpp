#include "EngineSimulation.h"

#include <algorithm>
#include <cmath>

namespace es {

namespace {

constexpr double kPAtm = 101325.0;
constexpr double kR = 287.0;         // 空気の気体定数 [J/kg/K]
constexpr double kGamma = 1.33;      // 圧縮/膨張の平均比熱比
constexpr double kTIntake = 315.0;
constexpr double kLossFrac = 0.20;  // 壁面熱損失として冷却水へ逃げる割合

inline double rpmToRad(double rpm) { return rpm * kTwoPiD / 60.0; }
inline double radToRpm(double w) { return w * 60.0 / kTwoPiD; }
inline double ema(double prev, double x, double dt, double tau) {
    return prev + (x - prev) * (1.0 - std::exp(-dt / std::max(tau, 1e-6)));
}
inline bool inWindow(double a, double open, double close, double period) {
    return wrapPos(a - open, period) < wrapPos(close - open, period);
}
// a0 → a1 (周期 period で折り返しあり) の間に x を通過したか
inline bool crossed(double a0, double a1, double x, double period) {
    double span = wrapPos(a1 - a0, period);
    double d = wrapPos(x - a0, period);
    return d > 0.0 && d <= span;
}
inline double wiebe(double aSym, double adv, double dur) {
    double u = (aSym + adv) / dur;
    if (u <= 0) return 0.0;
    return 1.0 - std::exp(-5.0 * u * u * u);
}

}  // namespace

void EngineSimulation::init(const EngineSpec& spec, AudioFeed* feed) {
    spec_ = spec;
    kin_.init(&spec_);
    feed_ = feed;
    controls_ = Controls();
    controls_.targetRpm = spec_.idleRpm;
    tel_ = Telemetry();
    theta_ = 0;
    visTheta_ = 0;
    for (float& a : visSpool_) a = 0;
    govInteg_ = 0;
    throttle_ = 0.1;
    boost_ = 0;
    turboFrac_ = 0.1;
    map_ = spec_.isDiesel() ? kPAtm : 0.4 * kPAtm;
    coolantK_ = 355;
    oilK_ = 362;
    egtK_ = 750;
    torqueEma_ = fuelEma_ = indEma_ = fricEma_ = coolEma_ = 0;
    limiterCut_ = false;
    motorTorque_ = 0;

    // 負荷スライダのスケール = 推定最大トルク (平均有効圧 × 行程容積)
    double vd = spec_.displacement;
    double boostFactor = 1.0 + 0.6 * spec_.induction.maxBoostBar;
    switch (spec_.cycle) {
        case Cycle::Otto4: peakTorqueRef_ = 12.5e5 * vd / (4 * kPiD) * boostFactor; break;
        case Cycle::Diesel4: peakTorqueRef_ = 15e5 * vd / (4 * kPiD) * boostFactor; break;
        case Cycle::Otto2: peakTorqueRef_ = 7e5 * vd / (2 * kPiD) * boostFactor; break;
        case Cycle::Diesel2: peakTorqueRef_ = 9e5 * vd / (2 * kPiD) * boostFactor; break;
        case Cycle::Wankel4: peakTorqueRef_ = 10e5 * vd / (2 * kPiD) * boostFactor; break;
        case Cycle::Steam: peakTorqueRef_ = 0.45 * (spec_.boilerPressure - kPAtm) * vd * 2 / (2 * kPiD); break;
        case Cycle::Stirling: peakTorqueRef_ = 0.12 * spec_.stirlingMeanPressure * vd / (2 * kPiD); break;
        case Cycle::None:
            if (spec_.family == Family::Electric) peakTorqueRef_ = spec_.motor.ratedTorque;
            else peakTorqueRef_ = std::max(100.0, spec_.turbine.maxShaftPowerW / rpmToRad(spec_.redlineRpm));
            break;
    }
    peakTorqueRef_ = std::max(1.0, peakTorqueRef_);
    spec_.peakTorqueEstimate = static_cast<float>(peakTorqueRef_);

    ch_.assign(spec_.chambers.size(), ChamberState());
    lastEvoTime_.assign(spec_.chambers.size(), -1.0);
    lastEvoAmp_.assign(spec_.chambers.size(), 0.0f);
    vehV_ = vehDist_ = 0;
    autoGear_ = 1;
    shiftTimer_ = shiftCut_ = slip_ = 0;
    lockup_ = false;
    simTime_ = 0;
    v0Ref_.assign(spec_.chambers.size(), 1e-3);
    for (size_t c = 0; c < ch_.size(); ++c) {
        double vmax = 0;
        for (int i = 0; i < 72; ++i) vmax = std::max(vmax, kin_.chamberVolume(static_cast<int>(c), i * spec_.cycleDeg() / 72.0));
        v0Ref_[c] = vmax;
    }
    for (size_t c = 0; c < ch_.size(); ++c) {
        ChamberState& s = ch_[c];
        s.V = kin_.chamberVolume(static_cast<int>(c), 0.0);
        s.p = kPAtm;
        s.T = 320;
        s.m = s.p * s.V / (kR * s.T);
        s.lastLocal = wrapPos(0.0 - spec_.chambers[c].firingDeg, spec_.cycleDeg());
        s.pTab.assign(kTableBins, static_cast<float>(kPAtm));
        s.tTab.assign(kTableBins, 320.0f);
        s.bTab.assign(kTableBins, 0.0f);
    }
    if (spec_.cycle == Cycle::Stirling) {
        stirlingHotK_ = spec_.stirlingColdK + 0.12 * (spec_.stirlingHotK - spec_.stirlingColdK);
        double vh = 0, vc = 0, vr = 0;
        for (size_t c = 0; c < ch_.size(); ++c) {
            double vmean = 0;
            for (int i = 0; i < 36; ++i) vmean += kin_.chamberVolume(static_cast<int>(c), i * 10.0) / 36.0;
            if (spec_.chambers[c].kind == ChamberKind::StirlingHot) vh += vmean; else vc += vmean;
            vr += 0.4 * vmean;
        }
        double tr = (spec_.stirlingHotK - spec_.stirlingColdK) / std::log(spec_.stirlingHotK / std::max(1.0f, spec_.stirlingColdK));
        stirlingMass_ = spec_.stirlingMeanPressure * (vh / spec_.stirlingHotK + vr / tr + vc / spec_.stirlingColdK) / kR;
    }

    // 初期状態: アイドル回転で始動済み (電動機のみ停止から)
    if (spec_.family == Family::Electric) {
        omega_ = 0;
        controls_.targetRpm = 0;
    } else {
        omega_ = rpmToRad(spec_.idleRpm);
    }
    ncore_ = spec_.family == Family::Turbine ? spec_.idleRpm / spec_.turbine.n2MaxRpm : 0;
    nfan_ = 0;
    // 1 サイクル分空回ししてテーブルと圧力状態を定常に近づける
    if (spec_.family == Family::Reciprocating || spec_.family == Family::Wankel) {
        for (int i = 0; i < 40; ++i) step(0.02);
    }
}

double EngineSimulation::exhaustBackPressure() const {
    double r = omega_ / rpmToRad(spec_.redlineRpm);
    return kPAtm * (1.0 + 0.22 * r * r * spec_.tuning.backpressureScale) + 0.75 * boost_;
}

double EngineSimulation::frictionTorque() const {
    return frictionTorqueBase() * spec_.tuning.frictionScale;
}

double EngineSimulation::frictionTorqueBase() const {
    double rpm = radToRpm(omega_);
    double n = rpm / 1000.0;
    double coldFactor = 1.0 + 0.8 * std::clamp((355.0 - oilK_) / 60.0, 0.0, 1.0);
    switch (spec_.cycle) {
        case Cycle::Otto4:
        case Cycle::Diesel4: {
            double fmep = (95.0 + 10.0 * n + 1.2 * n * n) * 1e3 * coldFactor * (spec_.isDiesel() ? 1.3 : 1.0);
            // 補機 (オルタネータ/オイル・ウォーターポンプ) 負荷 ~6Nm/L を加える
            return fmep * spec_.displacement / (4 * kPiD) + 6000.0 * spec_.displacement;
        }
        case Cycle::Otto2:
        case Cycle::Diesel2: {
            double fmep = (60.0 + 10.0 * n + 2.0 * n * n) * 1e3 * coldFactor;
            return fmep * spec_.displacement / (2 * kPiD);
        }
        case Cycle::Wankel4: {
            double fmep = (80.0 + 10.0 * n + 2.0 * n * n) * 1e3 * coldFactor;
            return fmep * spec_.displacement / (2 * kPiD);
        }
        case Cycle::Stirling:
            return peakTorqueRef_ * (0.12 + 0.12 * n);  // ピストンシール/軸受摩擦が相対的に大きい
        default:
            return peakTorqueRef_ * (0.04 + 0.03 * n);
    }
}

double EngineSimulation::idleControl(double dt) {
    if (!controls_.ignition || spec_.cycle == Cycle::Steam || spec_.cycle == Cycle::Stirling) return 0.0;
    double rpm = radToRpm(omega_);
    double err = spec_.idleRpm - rpm;
    iscInteg_ = std::clamp(iscInteg_ + err * 2.0 / spec_.redlineRpm * dt, 0.0, 0.3);
    if (rpm > spec_.idleRpm * 1.6) iscInteg_ *= std::exp(-dt / 0.5);
    return std::clamp(iscInteg_ + err * 2.5 / spec_.redlineRpm, 0.0, 0.35);
}

double EngineSimulation::governor(double dt) {
    double rpm = radToRpm(omega_);
    double red = spec_.redlineRpm;
    double err = controls_.targetRpm - rpm;
    // ゲインスケジューリング: アイドル付近はトルク感度が高いので弱く、高回転指令ほど強く
    double f = std::clamp(controls_.targetRpm / red, 0.0, 1.0);
    bool electric = spec_.family == Family::Electric;
    double kp = (electric ? 2.5 : 0.35 + 2.2 * f) / red;
    double ki = (electric ? 4.0 : 0.45 + 3.5 * f) / red;
    double kd = (electric ? 0.0 : 0.06 + 0.1 * f) / red;
    double rate = (rpm - govPrevRpm_) / std::max(dt, 1e-4);
    govPrevRpm_ = rpm;
    govRate_ += (rate - govRate_) * std::min(1.0, dt / 0.05);
    double lo = electric ? -1.0 : 0.0;
    double integ = govInteg_ + ki * err * dt;
    double u = kp * err + integ - kd * govRate_;
    if (u > 1.0) { u = 1.0; if (err < 0) govInteg_ = integ; }
    else if (u < lo) { u = lo; if (err > 0) govInteg_ = integ; }
    else govInteg_ = integ;
    govInteg_ = std::clamp(govInteg_, lo - 0.5, 1.5);
    return u;
}

double EngineSimulation::stepChamber(int c, double theta0, double theta1, double h, double& qReleased) {
    const ChamberDef& cd = spec_.chambers[c];
    ChamberState& s = ch_[c];
    const double cyc = spec_.cycleDeg();
    double local1 = wrapPos(theta1 - cd.firingDeg, cyc);
    double local0 = s.lastLocal;
    s.lastLocal = local1;
    double vNew = kin_.chamberVolume(c, theta1);
    double v = s.V;
    double p = s.p, T = s.T;
    double pNew, tNew;
    float burnVis = 0;

    if (cd.kind == ChamberKind::SteamHead || cd.kind == ChamberKind::SteamCrank) {
        double aCut = rad2deg(static_cast<float>(std::acos(std::clamp(1.0 - 2.0 * spec_.cutoff, -1.0, 1.0))));
        double pAdm = kPAtm + throttle_ * (spec_.boilerPressure - kPAtm);
        double pExh = 1.12e5;
        bool admit = inWindow(local1, 355.0, aCut, 360.0);
        bool exhaust = inWindow(local1, 150.0, 325.0, 360.0);
        if (crossed(local0, local1, 150.0, 360.0) && feed_ && c < kMaxChambers)
            feed_->pulseAmp[c].store(static_cast<float>(std::max(0.0, (p - pExh) / 1e5) * 0.8));
        double poly = p * std::pow(v / vNew, 1.1);
        if (admit) pNew = pAdm + (poly - pAdm) * std::exp(-h / 0.0008);
        else if (exhaust) pNew = pExh + (poly - pExh) * std::exp(-h / 0.002);
        else pNew = poly;
        tNew = 373.0 + pNew / 1e6 * 80.0;
        burnVis = admit ? 0.5f : 0.0f;
    } else {
        // 燃焼室 (4 ストローク/2 ストローク/ヴァンケル)
        const bool four = spec_.isFourStroke();
        const double scale = four ? 720.0 / cyc : 1.0;
        const double per = four ? 720.0 : 360.0;
        double a0 = local0 * scale, a1 = local1 * scale;
        const ValveTrainDef& vt = spec_.valves;
        bool inOpen, exOpen;
        double trapAngle, evoAngle;
        if (four) {
            inOpen = inWindow(a1, vt.ivoDeg, vt.ivcDeg, 720.0);
            exOpen = inWindow(a1, vt.evoDeg, vt.evcDeg, 720.0);
            trapAngle = vt.ivcDeg;
            evoAngle = vt.evoDeg;
        } else {
            inOpen = inWindow(a1, vt.transferPortDeg, 360.0 - vt.transferPortDeg, 360.0);
            exOpen = inWindow(a1, vt.exhaustPortDeg, 360.0 - vt.exhaustPortDeg, 360.0);
            trapAngle = 360.0 - vt.exhaustPortDeg;
            evoAngle = vt.exhaustPortDeg;
        }
        double pExh = exhaustBackPressure();
        double pIn = four ? map_ : map_ * 1.12 + 0.15 * boost_;  // 2 ストロークはクランクケース/ブロワ掃気

        if (crossed(a0, a1, evoAngle, per)) {
            if (s.fueled && s.qTotal > 0) s.fresh = 0.0;  // 燃焼済みガス (失火/燃料カット時は新気のまま)
            // ブローダウン: 排気開時のシリンダ圧が排気パルスの強さ (音源) になる
            // 音源強度 = ブローダウン (圧力差) + 排気行程でピストンが押し出す質量流 (筒内質量比例)
            double mRef = kPAtm * v0Ref_[c] / (kR * 300.0);
            double amp = std::max(0.0, (p - pExh) / 1e5) + 0.5 * std::min(2.0, s.m / std::max(mRef, 1e-12));
            if (feed_ && c < kMaxChambers) feed_->pulseAmp[c].store(static_cast<float>(amp));
            lastEvoTime_[c] = simTime_;
            lastEvoAmp_[c] = static_cast<float>(amp);
            // 後燃え (アフターファイア): 燃料カット中の未燃焼行程に確率的に発生 (演出/音用)
            if (!s.fueled && omega_ > rpmToRad(1.5 * spec_.idleRpm) && spec_.hasCombustion() && !spec_.isDiesel()) {
                afterfireSeed_ = afterfireSeed_ * 1664525u + 1013904223u;
                if ((afterfireSeed_ >> 24) < 40) ++afterfireCount_;
            }
            egtK_ = ema(egtK_, T * 0.82, 1.0, 6.0);
        }
        if (crossed(a0, a1, trapAngle, per)) {
            // 吸気閉: 捕捉空気量から燃料投入量を決める
            double mTrap = std::max(0.0, p * v / (kR * std::max(T, 250.0)));
            s.m = mTrap;
            double mAir = mTrap * std::clamp(s.fresh, 0.0, 1.0);  // 残留ガスを除いた新気質量
            if (feed_ && c < kMaxChambers) feed_->intakeAmp[c].store(static_cast<float>(std::min(1.0, p / kPAtm)));
            s.burnPrev = 0;
            bool cut = (controls_.cylinderCutMask >> ((cd.number - 1) & 63)) & 1ull;
            // 減速時燃料カット (DFCO): 火花点火機でスロットル全閉かつアイドルより十分高い回転
            bool dfco = !spec_.isDiesel() && throttle_ < 0.01 && omega_ > rpmToRad(1.6 * spec_.idleRpm);
            s.fueled = controls_.ignition && !limiterCut_ && !cut && !dfco;
            double q = 0;
            if (s.fueled) {
                if (spec_.isDiesel()) {
                    double rack = std::max(0.03, throttle_);
                    q = mAir / 19.0 * rack * 43e6 * 0.96;
                } else {
                    q = mAir / 14.7 * 44e6 * 0.95;
                }
            }
            s.qTotal = q;
        }

        double poly = p * std::pow(v / vNew, kGamma);
        if (inOpen || exOpen) {
            // 充填/排出の緩和時定数 tau ~ V / (Cd A c): 有効開口 (リフト) が小さいほど遅く、
            // 容積が大きいほど遅い。基準値は 0.5L 級シリンダ・最大リフトでの概算。
            double sizeK = std::max(0.3, static_cast<double>(spec_.bore) / 0.086);
            double vRel = std::clamp(vNew / std::max(1e-9, v0Ref_[c]), 0.15, 1.3);
            double liftIn = 1.0, liftEx = 1.0;
            if (four && spec_.family == Family::Reciprocating) {
                liftIn = std::max(0.12, static_cast<double>(kin_.valveLift(c, true, theta1) / std::max(1e-6f, spec_.valves.liftIn)));
                liftEx = std::max(0.12, static_cast<double>(kin_.valveLift(c, false, theta1) / std::max(1e-6f, spec_.valves.liftEx)));
            }
            double tauIn = 0.00018 * sizeK * vRel / liftIn / spec_.tuning.breathingScale;
            double tauEx = 0.00015 * sizeK * vRel / liftEx / spec_.tuning.breathingScale;
            double target, tau;
            if (inOpen && exOpen) { target = 0.5 * (pIn + pExh); tau = 0.5 * (tauIn + tauEx); }
            else if (exOpen) { target = pExh; tau = tauEx; }
            else { target = pIn; tau = tauIn; }
            pNew = target + (poly - target) * std::exp(-h / tau);
            // 開放系の第一法則: d(m cv T) = h_src dm - p dV, pV = mRT より
            //   dm = (p' V' - p V + (γ-1) p_avg dV) / (γ R T_src)
            // 流入は吸気温度 (吸気弁側) または排気温度で、流出は筒内温度で持ち出される。
            double pAvg = 0.5 * (p + pNew);
            double num = pNew * vNew - p * v + (kGamma - 1.0) * pAvg * (vNew - v);
            // 同時開 (オーバーラップ/掃気) では圧力の高い側から流入する
            bool fromIntake = inOpen && (!exOpen || pIn >= pExh);
            double tSrc = num > 0 ? (fromIntake ? kTIntake : std::max(T, 500.0)) : T;
            double dm = num / (kGamma * kR * tSrc);
            if (s.m <= 1e-12) s.m = p * v / (kR * T);
            double mOld = s.m;
            s.m = std::max(s.m + dm, 1e-9);
            // 新気割合: 吸気側からの流入で増える
            if (dm > 0 && fromIntake) s.fresh = (s.fresh * mOld + dm) / s.m;
            else if (dm > 0) s.fresh = s.fresh * mOld / s.m;  // 排気の逆流は既燃ガス
            // 掃気 (吸排気同時開): 圧力変化を伴わない通り抜け流で残留ガスを押し出す
            double tNewRaw = pNew * vNew / (s.m * kR);
            if (inOpen && exOpen && pIn > pExh * 0.95) {
                double x = 1.0 - std::exp(-h / (2.5 * tau));
                s.fresh += (1.0 - s.fresh) * x;
                tNewRaw += (kTIntake - tNewRaw) * x;
                s.m = pNew * vNew / (kR * tNewRaw);
            }
            tNew = std::clamp(tNewRaw, 200.0, 3500.0);
        } else {
            double dq = 0;
            if (s.fueled && s.qTotal > 0) {
                double adv = spec_.sparkAdvanceDeg + controls_.sparkOffsetDeg;
                // 低負荷 (残留ガス希釈) ほど燃焼が遅い
                double dur = spec_.burnDurationDeg * (spec_.isDiesel() ? 1.0 : 1.0 + 0.6 * std::clamp(1.0 - map_ / kPAtm, 0.0, 1.0));
                double aSym = wrapSym(local1, cyc);
                double x = wiebe(aSym, adv, dur);
                if (x > s.burnPrev) {
                    dq = s.qTotal * (x - s.burnPrev);
                    s.burnPrev = x;
                }
                if (x > 0.001 && x < 0.999) burnVis = static_cast<float>(4.0 * x * (1.0 - x));
            }
            qReleased += dq;
            double dqEff = dq * (1.0 - kLossFrac);
            pNew = poly + (kGamma - 1.0) * dqEff / vNew;
            if (s.m <= 1e-9) s.m = p * v / (kR * T);
            tNew = pNew * vNew / (s.m * kR);
        }
        s.pMax = std::max(s.pMax, static_cast<float>(pNew));
    }

    int bin = std::min(kTableBins - 1, static_cast<int>(local1 / cyc * kTableBins));
    s.pTab[bin] = static_cast<float>(pNew);
    s.tTab[bin] = static_cast<float>(tNew);
    s.bTab[bin] = burnVis;

    double work = (0.5 * (p + pNew) - kPAtm) * (vNew - v);
    s.p = pNew;
    s.T = tNew;
    s.V = vNew;
    return work;
}

void EngineSimulation::stepReciprocating(double dt) {
    const double red = rpmToRad(spec_.redlineRpm);
    double rpm = radToRpm(omega_);

    // エンスト判定 (自動始動用): アイドルの 25% 未満で停止扱い, 70% 以上で解除
    if (rpm < 0.25 * spec_.idleRpm) stalled_ = true;
    else if (rpm > 0.7 * spec_.idleRpm) stalled_ = false;

    // スロットル (ガバナ連動 or 手動)。手動時もアイドル回転制御 (ISC) が最低開度を保持する
    double thr = controls_.throttleLink ? governor(dt) : std::max<double>(controls_.throttle, idleControl(dt));
    throttle_ = std::clamp(thr, 0.0, 1.0);

    // レブリミッタ (燃料カット, ヒステリシス付き)
    if (rpm > spec_.limiterRpm) limiterCut_ = true;
    else if (rpm < spec_.limiterRpm - 180) limiterCut_ = false;

    // 過給
    const InductionDef& ind = spec_.induction;
    if (ind.type == InductionType::Turbo) {
        double exhaustW = fuelEma_ * 0.30;
        double ref = peakTorqueRef_ * red * 0.55;
        double target = std::sqrt(std::clamp(exhaustW / ref, 0.0, 1.3));
        turboFrac_ = ema(turboFrac_, std::max(0.08, target), dt, ind.spoolTimeConst);
        double cap = ind.maxBoostBar * 1e5;
        double b = std::min(cap, cap * 1.7 * turboFrac_ * turboFrac_) * std::sqrt(throttle_);
        if (prevThrottle_ - throttle_ > 0.35 * dt / 0.05 && boost_ > 0.3e5) {
            ++bovCount_;  // ブローオフバルブ開放
            boost_ *= 0.2;
        }
        boost_ = ema(boost_, b, dt, 0.12);
    } else if (ind.type == InductionType::Roots) {
        turboFrac_ = rpm / spec_.redlineRpm;
        double b = ind.maxBoostBar * 1e5 * std::clamp(rpm / (0.35 * spec_.redlineRpm), 0.0, 1.0) * std::pow(throttle_, 1.5);
        boost_ = ema(boost_, b, dt, 0.05);
    } else {
        boost_ = 0;
    }
    prevThrottle_ = throttle_;

    // 吸気マニホールド圧
    double pUp = kPAtm + boost_;
    double mapTarget;
    if (spec_.isDiesel() || spec_.cycle == Cycle::Steam || spec_.cycle == Cycle::Stirling) {
        mapTarget = pUp;
    } else {
        double ta = 0.004 + 0.996 * throttle_ * throttle_;
        double d = std::max(0.02, rpm / spec_.redlineRpm);
        mapTarget = std::max(0.12e5, pUp * ta / std::sqrt(ta * ta + 0.09 * d * d));
    }
    map_ = ema(map_, mapTarget, dt, 0.04);

    // スターリング: ヒーター温度がスロットル (加熱量) に追従
    if (spec_.cycle == Cycle::Stirling) {
        double tHot = spec_.stirlingColdK + (spec_.stirlingHotK - spec_.stirlingColdK) * (controls_.ignition ? throttle_ : 0.0);
        stirlingHotK_ = ema(stirlingHotK_, std::max<double>(tHot, spec_.stirlingColdK + 1), dt, 2.0);
    }

    // サブステップ: 1 ステップあたりクランク角 ~1° (気筒数が多いときは 2°)
    const double maxStepDeg = spec_.chambers.size() > 16 ? 2.0 : 1.0;
    double estDeg = std::fabs(omega_) * dt * 180.0 / kPiD;
    int n = std::clamp(static_cast<int>(std::ceil(estDeg / maxStepDeg)), 1, 4000);
    double h = dt / n;
    const double J = spec_.inertia;
    double workFrame = 0, qFrame = 0, fricWork = 0, loadWork = 0, angleFrame = 0;
    double loadTorqueRef = 0;

    for (int i = 0; i < n; ++i) {
        rpm = radToRpm(omega_);
        double dThetaRad = omega_ * h;
        double theta0 = theta_;
        double theta1 = theta_ + dThetaRad * 180.0 / kPiD;

        double work = 0;
        if (spec_.cycle == Cycle::Stirling) {
            // シュミット理論 (等温) による一様圧力
            double vh = 0, vc = 0, vOld = 0, vNewT = 0;
            for (size_t c = 0; c < ch_.size(); ++c) {
                double vn = kin_.chamberVolume(static_cast<int>(c), theta1);
                vOld += ch_[c].V;
                vNewT += vn;
                if (spec_.chambers[c].kind == ChamberKind::StirlingHot) vh += vn; else vc += vn;
                ch_[c].V = vn;
            }
            // 熱交換器の伝熱律速: 高回転ほどガスが実効的に受け取る温度差が縮む
            double nr = rpm / (0.8 * spec_.redlineRpm);
            double tc = spec_.stirlingColdK, th = tc + (stirlingHotK_ - tc) / (1.0 + nr * nr);
            double tr = th - tc > 0.5 ? (th - tc) / std::log(th / tc) : 0.5 * (th + tc);  // 再生器の対数平均温度
            double vr = 0.4 * (vh + vc);
            double pOld = ch_[0].p > 0.5 * spec_.stirlingMeanPressure ? ch_[0].p : spec_.stirlingMeanPressure;
            double pNew = stirlingMass_ * kR / (vh / th + vr / tr + vc / tc);
            work = (0.5 * (pOld + pNew) - spec_.stirlingMeanPressure) * (vNewT - vOld);
            for (size_t c = 0; c < ch_.size(); ++c) {
                ch_[c].p = pNew;
                ch_[c].T = spec_.chambers[c].kind == ChamberKind::StirlingHot ? th : tc;
                double local1 = wrapPos(theta1 - spec_.chambers[c].firingDeg, 360.0);
                int bin = std::min(kTableBins - 1, static_cast<int>(local1 / 360.0 * kTableBins));
                ch_[c].pTab[bin] = static_cast<float>(pNew);
                ch_[c].tTab[bin] = static_cast<float>(ch_[c].T);
                ch_[c].pMax = std::max(ch_[c].pMax, static_cast<float>(pNew));
            }
        } else {
            for (size_t c = 0; c < ch_.size(); ++c) work += stepChamber(static_cast<int>(c), theta0, theta1, h, qFrame);
        }

        double tf = frictionTorque();
        // 渦電流ダイナモ: 低速では吸収トルクが立ち上がらない (アイドル付近でのエンスト防止)
        double tl = controls_.load * peakTorqueRef_ * std::clamp((rpm - 0.6 * spec_.idleRpm) / (1.2 * spec_.idleRpm), 0.0, 1.0);
        if (spec_.drivetrain.propeller) {
            double rr = rpm / spec_.redlineRpm;
            tl = peakTorqueRef_ * (0.45 + 0.75 * controls_.load) * rr * rr;
        }
        tl += vehicleCoupling(omega_, h);
        loadTorqueRef = tl;
        double crankLimit = std::max(0.45 * spec_.idleRpm, 60.0);
        bool cranking = controls_.starter || (controls_.autoStart && controls_.ignition && stalled_);
        // スタータ: 圧縮反力に打ち勝てるだけの減速ギア付きトルク
        double ts = (cranking && rpm < crankLimit) ? (0.9 * peakTorqueRef_ * (1.0 - rpm / crankLimit) + tf) : 0.0;

        // クランキング中はオートデコンプ (排気弁を僅かに開けて圧縮反力を逃がす) を模擬
        const bool decomp = ts > 0.0;
        if (decomp && work < 0) work *= 0.3;
        if (omega_ > 3.0) {
            // エネルギー形式: 1/2 J w^2 の変化 = ガス仕事 - 摩擦仕事 - 負荷仕事
            double e = 0.5 * J * omega_ * omega_ + work + (ts - tf - tl) * dThetaRad;
            omega_ = e > 0 ? std::sqrt(2.0 * e / J) : 0.0;
        } else {
            // 低速域: トルク形式 (静止摩擦で逆転しない)
            double tg = 0;
            if (spec_.cycle != Cycle::Stirling)
                for (size_t c = 0; c < ch_.size(); ++c)
                    tg += (ch_[c].p - kPAtm) * kin_.chamberDVdTheta(static_cast<int>(c), theta1);
            if (decomp && tg < 0) tg *= 0.3;
            double net = tg + ts - tl - tf * (omega_ > 0.1 ? 1.0 : 0.5);
            omega_ = std::max(0.0, omega_ + net / J * h);
        }
        theta_ = wrapPos(theta1, spec_.cycleDeg());
        workFrame += work;
        fricWork += tf * dThetaRad;
        loadWork += tl * dThetaRad;
        angleFrame += dThetaRad;
    }

    double indicatedW = workFrame / dt;
    double frictionW = fricWork / dt;
    double fuelW = qFrame / dt;
    double brakeT = angleFrame > 1e-9 ? (workFrame - fricWork) / angleFrame : 0.0;
    tel_.torqueInstNm = static_cast<float>(angleFrame > 1e-9 ? workFrame / angleFrame : 0.0);
    tel_.loadNm = static_cast<float>(loadTorqueRef);
    (void)loadWork;
    updateCommon(dt, brakeT, fuelW, indicatedW, frictionW);
}

void EngineSimulation::stepTurbine(double dt) {
    const TurbineDef& t = spec_.turbine;
    double idleFrac = spec_.idleRpm / t.n2MaxRpm;
    double thr = controls_.throttleLink ? governor(dt) : controls_.throttle;
    throttle_ = std::clamp(thr, 0.0, 1.0);
    double target = controls_.ignition ? idleFrac + (1.02 - idleFrac) * throttle_ : (controls_.starter ? 0.22 : 0.0);
    double tau = t.spoolTimeConst * (target > ncore_ ? (1.6 - ncore_) : 0.7);
    double prev = ncore_;
    ncore_ = ema(ncore_, target, dt, tau);
    egtBump_ = ema(egtBump_, std::max(0.0, (ncore_ - prev) / dt) * 900.0, dt, 0.6);
    if (t.kind == TurbineKind::Turbojet) nfan_ = ncore_;
    else nfan_ = std::pow(std::clamp((ncore_ - 0.35) / 0.65, 0.0, 1.05), 1.15);
    omega_ = rpmToRad(ncore_ * t.n2MaxRpm);

    double thrust = 0, shaftW = 0;
    if (t.kind == TurbineKind::Turboshaft) {
        shaftW = t.maxShaftPowerW * std::pow(std::clamp((ncore_ - idleFrac) / (1.0 - idleFrac), 0.0, 1.05), 1.6);
        thrust = 0.03 * t.maxThrustN * ncore_;
    } else if (t.kind == TurbineKind::Turbofan) {
        thrust = t.maxThrustN * nfan_ * nfan_;
    } else {
        thrust = t.maxThrustN * std::pow(std::clamp((ncore_ - 0.3) / 0.7, 0.0, 1.05), 2.2);
    }
    double fuelW = (0.04 + 0.96 * std::pow(ncore_, 3.0)) * std::max(t.maxShaftPowerW / 0.3, t.maxThrustN * 900.0) * (controls_.ignition ? 1 : 0);
    tel_.n1 = static_cast<float>(nfan_ * 100.0);
    tel_.n2 = static_cast<float>(ncore_ * 100.0);
    tel_.thrustKN = static_cast<float>(thrust / 1000.0);
    egtK_ = controls_.ignition ? 620 + 330 * ncore_ + egtBump_ : ema(egtK_, 300, dt, 8);
    double outW = rpmToRad(nfan_ * t.n1MaxRpm);
    double brakeT = outW > 1 ? shaftW / outW : 0;
    tel_.torqueInstNm = static_cast<float>(brakeT);
    updateCommon(dt, brakeT * (outW / std::max(omega_, 1.0)), fuelW, shaftW + thrust * 150.0, fuelW * 0.02);
    tel_.powerKW = static_cast<float>(shaftW / 1000.0);
    tel_.outputRpm = static_cast<float>(nfan_ * t.n1MaxRpm / std::max(0.01f, t.outputReduction));
}

void EngineSimulation::stepElectric(double dt) {
    const MotorDef& m = spec_.motor;
    double u = controls_.throttleLink ? governor(dt) : controls_.throttle;
    if (!controls_.ignition) u = 0;
    const int n = std::max(1, static_cast<int>(dt / 0.0005));
    const double h = dt / n;
    double wBase = m.ratedPowerW / m.ratedTorque;
    double tl = 0;
    for (int i = 0; i < n; ++i) {
        double w = omega_;
        double tMax = w < wBase ? m.ratedTorque : m.ratedPowerW / w;
        if (radToRpm(w) > m.maxRpm) tMax *= std::max(0.0, 1.0 - (radToRpm(w) - m.maxRpm) / 300.0);
        motorTorque_ = std::clamp(u, -1.0, 1.0) * tMax;
        if (w < 0.5 && motorTorque_ < 0) motorTorque_ = 0;  // 停止中の回生は無し
        double tf = 0.004 * m.ratedTorque + 2e-5 * w * w * m.ratedTorque / 300.0;
        tl = controls_.load * m.ratedTorque * std::clamp(w / 20.0, 0.0, 1.0);
        tl += vehicleCoupling(omega_, h);
        omega_ = std::max(0.0, omega_ + (motorTorque_ - tf - tl) / spec_.inertia * h);
        theta_ = wrapPos(theta_ + omega_ * h * 180.0 / kPiD, 360.0);
    }
    throttle_ = std::fabs(u);
    double rpm = radToRpm(omega_);
    double slip = 0;
    if (m.kind == MotorKind::Induction) slip = std::clamp(m.slipRated * motorTorque_ / m.ratedTorque, -0.2, 0.2);
    double fe = m.polePairs * rpm / 60.0 / (1.0 - slip);
    tel_.electricalHz = static_cast<float>(fe);
    tel_.slip = static_cast<float>(slip);
    tel_.currentA = static_cast<float>(std::fabs(motorTorque_) / m.ratedTorque * 350.0);
    tel_.loadNm = static_cast<float>(tl);
    double mechW = motorTorque_ * omega_;
    double loss = std::fabs(mechW) * 0.06 + std::fabs(motorTorque_) / m.ratedTorque * 1500.0;
    tel_.torqueInstNm = static_cast<float>(motorTorque_);
    updateCommon(dt, motorTorque_, std::fabs(mechW) + loss, mechW, loss);
}

void EngineSimulation::updateCommon(double dt, double brakeTorque, double fuelW, double indicatedW, double frictionW) {
    double rpm = radToRpm(omega_);
    double cycleTime = omega_ > 1 ? spec_.cycleDeg() / 360.0 * 60.0 / std::max(rpm, 1.0) : 0.5;
    double tau = std::clamp(cycleTime * 2.0, 0.08, 0.6);
    torqueEma_ = ema(torqueEma_, brakeTorque, dt, tau);
    fuelEma_ = ema(fuelEma_, fuelW, dt, tau);
    indEma_ = ema(indEma_, indicatedW, dt, tau);
    fricEma_ = ema(fricEma_, frictionW, dt, tau);

    // 冷却系 (サーモスタット付き 1 次系)
    double coolantW = fuelEma_ * kLossFrac + fricEma_ * 0.5;
    coolEma_ = coolantW;
    double refFuelW = peakTorqueRef_ * rpmToRad(spec_.redlineRpm) / 0.3;
    double ua = 0.25 * refFuelW / 65.0 * spec_.tuning.coolingScale;
    double open = std::clamp((coolantK_ - 356.0) / 10.0, 0.05, 1.0);
    coolantK_ += (coolantW - ua * open * (coolantK_ - 300.0)) / 60000.0 * dt;
    oilK_ = ema(oilK_, coolantK_ + 8.0 + 20.0 * rpm / spec_.redlineRpm, dt, 30.0);
    double loadFactor = std::clamp(fuelEma_ / std::max(refFuelW, 1.0), 0.0, 1.5);
    pistonTempK_ = static_cast<float>(ema(pistonTempK_, coolantK_ + 60.0 + 260.0 * loadFactor, dt, 3.0));
    headTempK_ = static_cast<float>(ema(headTempK_, coolantK_ + 25.0 + 150.0 * loadFactor, dt, 5.0));
    if (spec_.family != Family::Turbine && !(fuelEma_ > 1.0)) egtK_ = ema(egtK_, 450.0, dt, 5.0);

    tel_.rpm = static_cast<float>(rpm);
    tel_.targetRpm = controls_.targetRpm;
    tel_.throttle = static_cast<float>(throttle_);
    tel_.torqueNm = static_cast<float>(torqueEma_);
    tel_.powerKW = static_cast<float>(torqueEma_ * omega_ / 1000.0);
    tel_.bmepBar = spec_.displacement > 0
                       ? static_cast<float>(torqueEma_ * (spec_.isFourStroke() && spec_.cycle != Cycle::Wankel4 ? 4 * kPiD : 2 * kPiD) / spec_.displacement / 1e5)
                       : 0.0f;
    tel_.mapKPa = static_cast<float>(map_ / 1000.0);
    tel_.boostBar = static_cast<float>(boost_ / 1e5);
    tel_.turboRpm = static_cast<float>(spec_.induction.type == InductionType::Turbo ? turboFrac_ * spec_.induction.turboMaxRpm
                                        : (spec_.induction.type == InductionType::Roots ? rpm * spec_.induction.rootsDriveRatio : 0.0));
    tel_.egtK = static_cast<float>(egtK_);
    tel_.coolantK = static_cast<float>(coolantK_);
    tel_.oilK = static_cast<float>(oilK_);
    tel_.fuelKW = static_cast<float>(fuelEma_ / 1000.0);
    tel_.brakeKW = static_cast<float>(std::max(0.0, indEma_ - fricEma_) / 1000.0);
    tel_.frictionKW = static_cast<float>(fricEma_ / 1000.0);
    tel_.coolantKW = static_cast<float>(coolEma_ / 1000.0);
    tel_.exhaustKW = static_cast<float>(std::max(0.0, fuelEma_ - indEma_ - fuelEma_ * kLossFrac) / 1000.0);
    tel_.efficiency = fuelEma_ > 1.0 ? static_cast<float>(std::clamp((indEma_ - fricEma_) / fuelEma_, -1.0, 1.0)) : 0.0f;
    float pk = 0;
    for (auto& c : ch_) { pk = std::max(pk, c.pMax); c.pMax *= 0.995f; }
    tel_.peakPressureBar = pk / 1e5f;
    tel_.gear = controls_.gear;
    tel_.effectiveGear = effectiveGear();
    tel_.speedKmh = static_cast<float>(vehV_ * 3.6);
    tel_.shifting = shiftCut_ > 0;
    tel_.lockup = lockup_;
    tel_.slipRatio = static_cast<float>(slip_);
    tel_.shiftCount = shiftCount_;
    tel_.afterfireCount = afterfireCount_;
    tel_.distanceM = static_cast<float>(vehDist_);
    if (feed_) {
        feed_->shiftCount.store(shiftCount_);
        feed_->afterfireCount.store(afterfireCount_);
    }
    int g = effectiveGear();
    const auto& gears = spec_.drivetrain.gears;
    if (spec_.drivetrain.propeller) {
        tel_.outputRpm = static_cast<float>(rpm * spec_.drivetrain.propReduction);
        tel_.outputTorqueNm = static_cast<float>(torqueEma_ / std::max(0.05f, spec_.drivetrain.propReduction));
    } else if (vehicleActive()) {
        // 車両モード: プロペラシャフト回転 = 車輪回転 × 最終減速比
        tel_.outputRpm = static_cast<float>(radToRpm(vehV_ / spec_.vehicle.wheelRadius * spec_.drivetrain.finalDrive));
        tel_.outputTorqueNm = static_cast<float>(g > 0 ? torqueEma_ * gears[std::min<size_t>(g, gears.size()) - 1] : 0.0);
    } else if (spec_.family != Family::Turbine) {
        if (g > 0 && g <= static_cast<int>(gears.size())) {
            double ratio = gears[g - 1] * spec_.drivetrain.finalDrive;
            tel_.outputRpm = static_cast<float>(rpm / ratio);
            tel_.outputTorqueNm = static_cast<float>(torqueEma_ * ratio * 0.95);
        } else {
            tel_.outputRpm = 0;
            tel_.outputTorqueNm = 0;
        }
    }
    tel_.limiter = limiterCut_;
    tel_.running = spec_.family == Family::Electric ? controls_.ignition : (controls_.ignition && rpm > 0.35 * spec_.idleRpm);
    tel_.thetaDeg = static_cast<float>(theta_);
    publishAudio();
}

void EngineSimulation::publishAudio() {
    if (!feed_) return;
    feed_->rpm.store(tel_.rpm);
    feed_->throttle.store(static_cast<float>(throttle_));
    feed_->mapBar.store(static_cast<float>(map_ / 1e5));
    feed_->boostBar.store(static_cast<float>(boost_ / 1e5));
    feed_->turboFrac.store(static_cast<float>(turboFrac_));
    feed_->loadFrac.store(controls_.load);
    feed_->outputRpm.store(tel_.outputRpm);
    feed_->n1Frac.store(static_cast<float>(nfan_));
    feed_->n2Frac.store(static_cast<float>(ncore_));
    float tq = spec_.family == Family::Electric ? static_cast<float>(std::fabs(motorTorque_) / spec_.motor.ratedTorque)
                                                : static_cast<float>(std::clamp(std::fabs(torqueEma_) / peakTorqueRef_, 0.0, 1.5));
    feed_->torqueFrac.store(tq);
    feed_->electricalHz.store(tel_.electricalHz);
    feed_->slip.store(tel_.slip);
    feed_->gear.store(controls_.gear);
    feed_->bovCount.store(bovCount_);
    feed_->running.store(tel_.running || tel_.rpm > 30);
}

bool EngineSimulation::vehicleActive() const {
    return controls_.driveMode != 0 && !spec_.drivetrain.propeller && spec_.family != Family::Turbine &&
           !spec_.drivetrain.gears.empty();
}

int EngineSimulation::effectiveGear() const {
    int n = static_cast<int>(spec_.drivetrain.gears.size());
    if (controls_.gear <= 0) return 0;
    if (controls_.driveMode == 2) return std::clamp(autoGear_, 1, std::max(1, n));
    return std::clamp(controls_.gear, 0, n);
}

double EngineSimulation::totalRatio(int g) const {
    const auto& gs = spec_.drivetrain.gears;
    if (g <= 0 || gs.empty()) return 0;
    return gs[std::min<size_t>(g, gs.size()) - 1] * spec_.drivetrain.finalDrive;
}

void EngineSimulation::updateTransmission(double dt) {
    shiftTimer_ += dt;
    shiftCut_ = std::max(0.0, shiftCut_ - dt);
    if (!vehicleActive()) { lastDriveMode_ = controls_.driveMode; return; }
    const int n = static_cast<int>(spec_.drivetrain.gears.size());
    const double r = spec_.vehicle.wheelRadius;
    if (controls_.driveMode == 2) {
        if (lastDriveMode_ != 2) { autoGear_ = 1; lockup_ = false; }
        if (controls_.gear > 0 && n > 1) {
            // 変速スケジュール: 入力軸回転 (車速 × 変速比) で判定。踏み込むほど高回転まで引っ張り、キックダウンも早い
            double thr = throttle_;
            double idle = spec_.idleRpm, red = spec_.redlineRpm;
            double up = idle + (red * 0.93 - idle) * (0.28 + 0.7 * thr);
            double down = idle * 1.25 + (red * 0.55 - idle * 1.25) * thr * thr;
            double rpmIn = radToRpm(vehV_ / r * totalRatio(autoGear_));
            if (shiftTimer_ > 0.9) {
                if (rpmIn > up && autoGear_ < n) {
                    ++autoGear_; ++shiftCount_; shiftTimer_ = 0; shiftCut_ = 0.12; lockup_ = false;
                } else if (autoGear_ > 1 && rpmIn < down) {
                    double predicted = rpmIn * totalRatio(autoGear_ - 1) / totalRatio(autoGear_);
                    if (predicted < up * 0.92) { --autoGear_; ++shiftCount_; shiftTimer_ = 0; shiftCut_ = 0.12; lockup_ = false; }
                }
            }
            // ロックアップ: 2 速以上で速度比が高いとき
            double we = std::max(omega_, 1.0);
            double sr = vehV_ / r * totalRatio(autoGear_) / we;
            if (autoGear_ >= 2 && sr > 0.9 && thr < 0.9) lockup_ = true;
            else if (sr < 0.8 || thr > 0.95) lockup_ = false;
        }
    } else {
        if (controls_.gear != lastManualGear_) {
            // MT: 変速操作中はクラッチを切る
            if (lastManualGear_ != 0 || controls_.gear != 0) { ++shiftCount_; shiftCut_ = 0.18; }
            lastManualGear_ = controls_.gear;
        }
        lockup_ = true;
    }
    lastDriveMode_ = controls_.driveMode;
}

double EngineSimulation::vehicleCoupling(double omegaE, double h) {
    if (!vehicleActive()) return 0.0;
    const VehicleDef& vd = spec_.vehicle;
    const double m = vd.massKg, r = vd.wheelRadius;
    const int g = effectiveGear();
    const double ratio = totalRatio(g);
    double tc = 0, tout = 0;
    if (g > 0 && shiftCut_ <= 0) {
        double wIn = vehV_ / r * ratio;
        double slip = omegaE - wIn;
        double peak = peakTorqueRef_;
        double kLock = peak * 0.05;  // 締結時の剛性 [Nm/(rad/s)]
        if (controls_.driveMode == 2 && !lockup_ && spec_.family != Family::Electric) {
            // トルクコンバータ: 伝達トルクは滑りとポンプ回転に比例、ストール域でトルク増幅
            double wIdle = rpmToRad(std::max(300.0f, spec_.idleRpm));
            // 容量係数は自然吸気相当のトルクで決める (過給分でクリープが過大にならないように)
            double naPeak = peak / (1.0 + 0.6 * spec_.induction.maxBoostBar);
            tc = 0.0015 * naPeak * slip * std::max(omegaE, wIdle) / wIdle;
            double sr = std::clamp(wIn / std::max(omegaE, 1.0), 0.0, 1.0);
            tout = tc * (1.0 + 0.9 * (1.0 - sr));
            slip_ = 1.0 - sr;
        } else {
            // 自動クラッチ: 低回転では伝達容量を絞ってエンストを防ぐ (半クラッチ)
            double rpmE = radToRpm(omegaE);
            double idle = spec_.family == Family::Electric ? 0.0 : spec_.idleRpm;
            // アイドル付近ではクラッチを切る (停車してもエンストしない)
            double cap = spec_.family == Family::Electric ? peak * 3.0
                                                          : peak * 1.6 * std::clamp((rpmE - 0.95 * idle) / 1500.0, 0.0, 1.0);
            tc = std::clamp(kLock * slip, -cap, cap);
            tout = tc;
            slip_ = std::clamp(std::fabs(slip) / std::max(omegaE, 1.0), 0.0, 1.0);
        }
    }
    // 車両の運動: m dv/dt = 駆動力 − 転がり − 空気 − 勾配 − ブレーキ
    double v = vehV_;
    double drive = tout * ratio * 0.92 / r;
    double roll = v > 0.05 ? vd.rollingCoeff * m * 9.81 : 0.0;
    double aero = 0.5 * 1.2 * vd.cdA * v * v;
    double grade = m * 9.81 * 0.15 * std::clamp(controls_.grade, 0.0f, 1.0f);
    double brake = v > 0.01 ? std::clamp(controls_.brake, 0.0f, 1.0f) * 0.95 * m * 9.81 : 0.0;
    double mEff = m * 1.04;  // 車輪等の回転慣性分
    vehV_ = std::max(0.0, v + (drive - roll - aero - grade - brake) / mEff * h);
    vehDist_ += vehV_ * h;
    return tc;
}

void EngineSimulation::step(double dt) {
    dt = std::clamp(dt, 1e-4, 0.05);
    simTime_ += dt;
    updateTransmission(dt);
    switch (spec_.family) {
        case Family::Reciprocating:
        case Family::Wankel: stepReciprocating(dt); break;
        case Family::Turbine: stepTurbine(dt); break;
        case Family::Electric: stepElectric(dt); break;
    }
}

void EngineSimulation::advanceVisual(double dt) {
    double ts = std::clamp(static_cast<double>(controls_.timeScale), 0.0, 1.0);
    double k = dt * ts * 180.0 / kPiD;  // rad/s → deg
    double cyc = spec_.family == Family::Wankel ? 1080.0 : 720.0;
    visTheta_ = wrapPos(visTheta_ + omega_ * k, cyc * 360.0);
    auto adv = [&](int i, double rpm) {
        visSpool_[i] = static_cast<float>(wrapPos(visSpool_[i] + rpmToRad(rpm) * dt * ts, kTwoPiD));
    };
    double rpm = radToRpm(omega_);
    if (spec_.family == Family::Turbine) {
        adv(0, nfan_ * spec_.turbine.n1MaxRpm);
        adv(1, ncore_ * spec_.turbine.n2MaxRpm);
        adv(2, nfan_ * spec_.turbine.n1MaxRpm / std::max(0.01f, spec_.turbine.outputReduction));
    } else {
        adv(2, tel_.outputRpm);
        adv(3, tel_.turboRpm);
        adv(4, rpm * spec_.drivetrain.propReduction);
        adv(5, rpm);
    }
}

float EngineSimulation::chamberPressure(int c, double thetaDeg) const {
    const ChamberState& s = ch_[c];
    double cyc = spec_.cycleDeg();
    double local = wrapPos(thetaDeg - spec_.chambers[c].firingDeg, cyc);
    int bin = std::min(kTableBins - 1, static_cast<int>(local / cyc * kTableBins));
    return s.pTab[bin];
}

float EngineSimulation::chamberTemperature(int c, double thetaDeg) const {
    const ChamberState& s = ch_[c];
    double cyc = spec_.cycleDeg();
    double local = wrapPos(thetaDeg - spec_.chambers[c].firingDeg, cyc);
    int bin = std::min(kTableBins - 1, static_cast<int>(local / cyc * kTableBins));
    return s.tTab[bin];
}

float EngineSimulation::chamberBurn(int c, double thetaDeg) const {
    const ChamberState& s = ch_[c];
    double cyc = spec_.cycleDeg();
    double local = wrapPos(thetaDeg - spec_.chambers[c].firingDeg, cyc);
    int bin = std::min(kTableBins - 1, static_cast<int>(local / cyc * kTableBins));
    return s.bTab[bin];
}

int EngineSimulation::pvDiagram(int c, float* volumeL, float* pressureBar, int maxN) const {
    if (c < 0 || c >= static_cast<int>(ch_.size())) return 0;
    const ChamberState& s = ch_[c];
    double cyc = spec_.cycleDeg();
    int n = std::min(maxN, kTableBins);
    for (int i = 0; i < n; ++i) {
        int bin = i * kTableBins / n;
        double theta = spec_.chambers[c].firingDeg + (bin + 0.5) * cyc / kTableBins;
        volumeL[i] = static_cast<float>(kin_.chamberVolume(c, theta) * 1000.0);
        pressureBar[i] = s.pTab[bin] / 1e5f;
    }
    return n;
}

float EngineSimulation::pistonLoadFraction(int p, double thetaDeg) const {
    if (p < 0 || p >= static_cast<int>(spec_.pistons.size())) return 0;
    double gas = 0, area = kPi * 0.25 * spec_.bore * spec_.bore;
    for (size_t c = 0; c < spec_.chambers.size(); ++c) {
        const ChamberDef& cd = spec_.chambers[c];
        for (size_t k = 0; k < cd.pistons.size(); ++k)
            if (cd.pistons[k] == p) gas += (chamberPressure(static_cast<int>(c), thetaDeg) - kPAtm) * cd.area * cd.signs[k];
    }
    const double h = 0.5;
    double s0 = kin_.pistonS(p, thetaDeg - h), s1 = kin_.pistonS(p, thetaDeg), s2 = kin_.pistonS(p, thetaDeg + h);
    double d2 = (s2 - 2 * s1 + s0) / (deg2radD(h) * deg2radD(h));
    double accel = omega_ * omega_ * d2;
    double inertia = -spec_.reciprocatingMass * accel;
    double ref = 70e5 * area;
    return static_cast<float>(std::clamp(std::fabs(gas + inertia) / ref, 0.0, 1.0));
}

}  // namespace es
