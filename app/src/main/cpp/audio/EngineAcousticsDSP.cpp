#include "EngineAcousticsDSP.h"

#include <algorithm>
#include <cmath>

#include "../core/MathUtil.h"

namespace es {

namespace {
constexpr float kGasC = 520.0f;  // 高温排気中の音速 [m/s]
constexpr float kExhaustGain = 0.35f;

// 可聴域 (sr*0.42 以下) に入るまでオクターブ単位で折り返す。
// 実機のブレード通過音は超音波域に達するが、音程の上昇感を保つための聴感上の処理。
inline float foldAudible(float hz, float sr) {
    float lim = sr * 0.42f;
    while (hz > lim) hz *= 0.5f;
    return hz;
}
inline float smoothCoef(float tau, float sr) { return 1.0f - std::exp(-1.0f / (tau * sr)); }
}  // namespace

std::unique_ptr<AcousticConfig> AcousticConfig::fromSpec(const EngineSpec& s) {
    auto c = std::make_unique<AcousticConfig>();
    c->family = s.family;
    c->cycle = s.cycle;
    c->cycleDeg = s.cycleDeg();
    c->redlineRpm = s.redlineRpm;
    c->chambers = std::min<int>(static_cast<int>(s.chambers.size()), kMaxChambers);
    int cyl = std::max(1, s.cylinders);
    c->cylVolumeL = s.displacement / cyl * 1000.0f;
    c->ohv = !s.valves.overhead;
    c->poppetValves = s.family == Family::Reciprocating && s.isFourStroke();
    c->bankCount = (s.banks.size() == 2) ? 2 : 1;

    const float scale = s.cycleDeg() / 720.0f;
    for (int i = 0; i < c->chambers; ++i) {
        const ChamberDef& ch = s.chambers[i];
        auto add = [&](float a, EvType t) {
            c->events.push_back({static_cast<float>(wrapPos(ch.firingDeg + a, c->cycleDeg)), t, static_cast<uint8_t>(i)});
        };
        switch (ch.kind) {
            case ChamberKind::Combustion:
                if (s.isFourStroke()) {
                    add(s.valves.evoDeg * scale, ExhaustOpen);
                    add(s.valves.ivoDeg * scale, IntakeOpen);
                    if (c->poppetValves) {
                        add(s.valves.ivcDeg * scale, ValveClose);
                        add(s.valves.evcDeg * scale, ValveClose);
                    }
                } else {
                    add(s.valves.exhaustPortDeg, ExhaustOpen);
                    add(s.valves.transferPortDeg, IntakeOpen);
                }
                add(0, Slap);
                break;
            case ChamberKind::SteamHead:
                add(150, ExhaustOpen);
                add(0, Slap);
                break;
            case ChamberKind::SteamCrank:
                add(150, ExhaustOpen);
                break;
            case ChamberKind::StirlingHot:
                add(0, Slap);
                break;
            default: break;
        }
        c->bankOf[i] = c->bankCount == 2 ? (ch.bank & 1) : 0;
        float len = i < static_cast<int>(s.exhaust.runnerLengths.size()) ? s.exhaust.runnerLengths[i] : 0.5f;
        c->runnerDelaySec[i] = std::clamp(len / kGasC, 0.0002f, 0.035f);
    }
    std::sort(c->events.begin(), c->events.end(), [](const Event& a, const Event& b) { return a.angle < b.angle; });
    c->collectorDelaySec = std::clamp(s.exhaust.collectorLength / kGasC, 0.0002f, 0.035f);
    c->tailDelaySec = std::clamp(s.exhaust.tailpipeLength / kGasC, 0.0005f, 0.07f);
    c->mufflerCutoffHz = std::clamp(900.0f * std::sqrt(0.03f / std::max(0.001f, s.exhaust.mufflerVolume)), 500.0f, 6000.0f);

    c->induction = s.induction.type;
    c->compressorBlades = s.induction.compressorBlades;
    c->turboMaxRpm = s.induction.turboMaxRpm;
    c->rootsLobes = s.induction.rootsLobes;
    c->rootsRatio = s.induction.rootsDriveRatio;
    c->propeller = s.drivetrain.propeller;
    c->propBlades = s.drivetrain.propBlades;
    c->propReduction = s.drivetrain.propReduction;

    c->turbineKind = s.turbine.kind;
    c->fanBlades = s.turbine.fanBlades;
    c->turbineCompressorBlades = s.turbine.compressorBlades;
    c->n1MaxRpm = s.turbine.n1MaxRpm;
    c->n2MaxRpm = s.turbine.n2MaxRpm;
    c->motorKind = s.motor.kind;
    c->polePairs = s.motor.polePairs;
    c->statorSlots = s.motor.statorSlots;
    c->pwmHz = s.motor.pwmHz;
    if (s.family == Family::Electric) c->redlineRpm = s.motor.maxRpm;
    return c;
}

EngineAcousticsDSP::EngineAcousticsDSP() { runner_ = new dsp::Delay<2048>[kMaxChambers]; }

EngineAcousticsDSP::~EngineAcousticsDSP() {
    delete cfg_;
    delete pending_.exchange(nullptr);
    delete retired_.exchange(nullptr);
    delete[] runner_;
}

void EngineAcousticsDSP::setSampleRate(float sr) { sr_ = sr > 1000 ? sr : 48000; }

void EngineAcousticsDSP::setConfig(std::unique_ptr<AcousticConfig> cfg) {
    delete retired_.exchange(nullptr);
    AcousticConfig* old = pending_.exchange(cfg.release());
    delete old;  // オーディオスレッドが未取得のまま置き換えられた構成
}

void EngineAcousticsDSP::resetState() {
    theta_ = 0;
    nextEvent_ = 0;
    for (auto& p : pulse_) p = Pulse();
    for (auto& p : intake_) p = Pulse();
    for (int i = 0; i < kMaxChambers; ++i) runner_[i].clear();
    for (auto& d : collector_) d.clear();
    for (auto& d : tail_) d.clear();
    const AcousticConfig& c = *cfg_;
    for (int b = 0; b < 2; ++b) {
        muffler_[b][0].lowpass(c.mufflerCutoffHz, 0.707f, sr_);
        muffler_[b][1].lowpass(c.mufflerCutoffHz * 1.6f, 0.9f, sr_);
        mufflerRes_[b].peak(95.0f, 1.2f, 5.0f, sr_);
        muffler_[b][0].reset();
        muffler_[b][1].reset();
        mufflerRes_[b].reset();
        flowLp_[b].lowpass(450, 0.7f, sr_);
    }
    for (int k = 0; k < 4; ++k) tailLp_[k].setCutoff(3200.0f - 500.0f * k, sr_);
    intakeBp_.bandpass(260, 1.8f, sr_);
    intakeHiss_.bandpass(3200, 0.9f, sr_);
    throttleWhistle_.bandpass(2300, 9.0f, sr_);
    const float modeHz[5] = {2900, 4700, 7100, 1450, 820};
    const float modeQ[5] = {12, 14, 10, 9, 6};
    for (int i = 0; i < 5; ++i) {
        mechModes_[i].bandpass(modeHz[i], modeQ[i], sr_);
        mechModes_[i].reset();
        mechExc_[i] = 0;
    }
    bovBp_.bandpass(2600, 0.8f, sr_);
    jetHp_.highpass(40, 0.7f, sr_);
    motorWindage_.bandpass(1100, 1.0f, sr_);
    rumbleLp_.setCutoff(180, sr_);
    bovEnv_ = 0;
}

void EngineAcousticsDSP::pan(float x, float az, float& l, float& r) const {
    float rel = deg2rad(az - camYaw_);
    float p = std::sin(rel);
    float g = 0.82f + 0.18f * std::cos(rel);  // 背面側を僅かに減衰
    float a = (p + 1.0f) * 0.25f * dsp::kPi;
    l += x * std::cos(a) * g;
    r += x * std::sin(a) * g;
}

void EngineAcousticsDSP::triggerEvent(const AcousticConfig::Event& e, const AudioFeed& feed, float rpm) {
    const AcousticConfig& c = *cfg_;
    float red = std::max(1.0f, c.redlineRpm);
    float degPerSec = std::max(rpm, 1.0f) * 6.0f;
    switch (e.type) {
        case AcousticConfig::ExhaustOpen: {
            Pulse& p = pulse_[e.chamber];
            float volF = std::clamp(std::pow(c.cylVolumeL / 0.5f, 0.3f), 0.5f, 2.0f);
            p.amp = feed.pulseAmp[e.chamber].load(std::memory_order_relaxed) * volF;
            // ブローダウン継続 ~90°CA (排気弁開~下死点付近)。低回転ほど長く低い音になる
            float blowdownDeg = c.cycle == Cycle::Steam ? 110.0f : 90.0f;
            p.dur = std::clamp(blowdownDeg / degPerSec, 0.0015f, c.cycle == Cycle::Steam ? 0.12f : 0.025f);
            p.t = 0;
            p.active = true;
            break;
        }
        case AcousticConfig::IntakeOpen: {
            Pulse& p = intake_[e.chamber];
            p.amp = (0.25f + thrSm_) * std::min(1.0f, feed.intakeAmp[e.chamber].load(std::memory_order_relaxed) + 0.2f);
            p.dur = std::clamp(120.0f / degPerSec, 0.003f, 0.05f);
            p.t = 0;
            p.active = true;
            break;
        }
        case AcousticConfig::ValveClose: {
            float a = 0.35f * (0.25f + rpm / red);
            mechExc_[0] += a;
            mechExc_[1] += a * 0.7f;
            mechExc_[2] += a * 0.4f;
            if (c.ohv) mechExc_[3] += a * 0.9f;
            break;
        }
        case AcousticConfig::Slap: {
            float a = (c.cycle == Cycle::Steam ? 0.9f : 0.25f) * (0.3f + thrSm_) * slapGain_;
            mechExc_[4] += a;
            if (c.cycle == Cycle::Stirling) mechExc_[3] += 0.1f;
            break;
        }
    }
}

float EngineAcousticsDSP::renderCombustion(const AcousticConfig& c, const AudioFeed& feed, float rpm, float& L, float& R) {
    const float inv = 1.0f / sr_;
    const float red = std::max(1.0f, c.redlineRpm);
    // --- クランク角を進めてイベントを発火 ---
    double dth = rpm / 60.0 * 360.0 * inv;
    double prev = theta_;
    theta_ += dth;
    const int ne = static_cast<int>(c.events.size());
    if (ne > 0) {
        while (nextEvent_ < ne && c.events[nextEvent_].angle <= theta_) {
            if (c.events[nextEvent_].angle > prev) triggerEvent(c.events[nextEvent_], feed, rpm);
            ++nextEvent_;
        }
        if (theta_ >= c.cycleDeg) {
            theta_ -= c.cycleDeg;
            nextEvent_ = 0;
            while (nextEvent_ < ne && c.events[nextEvent_].angle <= theta_) {
                triggerEvent(c.events[nextEvent_], feed, rpm);
                ++nextEvent_;
            }
        }
    } else if (theta_ >= c.cycleDeg) {
        theta_ -= c.cycleDeg;
    }

    // --- 排気: ブローダウンパルス → ランナー → バンク集合管 ---
    float bank[2] = {0, 0};
    for (int i = 0; i < c.chambers; ++i) {
        float x = 0;
        Pulse& p = pulse_[i];
        if (p.active) {
            float att = 1.0f - std::exp(-p.t / 0.00012f);
            float dec = std::exp(-p.t / (p.dur * 0.45f));
            float turb = std::exp(-p.t / (p.dur * 0.9f));
            x = p.amp * (att * dec + 0.28f * noise_.white() * turb);
            p.t += inv;
            if (p.t > p.dur * 3.5f) p.active = false;
        }
        // ランナー: 開口端での負反射を持つ櫛形導波管 w[n] = x[n] - 0.42 w[n-2D], 出力 = w[n-D]
        float d = std::max(1.0f, c.runnerDelaySec[i] * sr_);
        float w = x - 0.42f * runner_[i].tap(2.0f * d);
        runner_[i].push(w);
        bank[c.bankOf[i]] += runner_[i].tap(d);
    }
    float flow = (rpm / red) * (0.35f + 0.65f * mapSm_) * 0.25f;
    float dc = std::max(1.0f, c.collectorDelaySec * sr_);
    for (int b = 0; b < c.bankCount; ++b) {
        float z = bank[b] + flowLp_[b].process(noise_.white() * flow) + 0.3f * collector_[b].tap(2.0f * dc);
        collector_[b].push(z);
        bank[b] = collector_[b].tap(dc);
    }
    // --- マフラー/テールパイプ: 4 本の部分反射導波管 (遅延を非整数比にして共鳴を分散) ---
    float mono = bank[0] + (c.bankCount == 2 ? bank[1] : 0.0f);
    const float ratios[4] = {1.0f, 1.37f, 1.79f, 2.23f};
    float tailSum = 0;
    float dt0 = std::max(1.0f, c.tailDelaySec * sr_);
    for (int k = 0; k < 4; ++k) {
        float dk = std::min(dt0 * ratios[k], 4000.0f);
        float w = mono * 0.5f - 0.55f * tailLp_[k].process(tail_[k].tap(2.0f * dk));
        tail_[k].push(w);
        tailSum += tail_[k].tap(dk) * 0.25f;
    }
    const float exGain = kExhaustGain;
    if (c.bankCount == 2) {
        for (int b = 0; b < 2; ++b) {
            float e = 0.65f * tailSum + 0.45f * bank[b];
            e = mufflerRes_[b].process(muffler_[b][1].process(muffler_[b][0].process(e)));
            pan(dsp::softClip(e * exGain * 1.4f), b == 0 ? -65.0f : 65.0f, L, R);
        }
    } else {
        float e = 0.7f * tailSum + 0.4f * bank[0];
        e = mufflerRes_[0].process(muffler_[0][1].process(muffler_[0][0].process(e)));
        pan(dsp::softClip(e * exGain * 1.4f), 110.0f, L, R);
    }

    // --- 吸気: バルブ開の吸入パルス + 乱流ノイズ + スロットル笛 ---
    float in = 0;
    for (int i = 0; i < c.chambers; ++i) {
        Pulse& p = intake_[i];
        if (!p.active) continue;
        float env = std::sin(dsp::kPi * std::min(1.0f, p.t / p.dur));
        in -= p.amp * env * (0.6f + 0.4f * noise_.white());
        p.t += inv;
        if (p.t > p.dur) p.active = false;
    }
    float rr = rpm / red;
    float intake = intakeBp_.process(in * 0.5f + noise_.white() * rr * (0.15f + 0.6f * thrSm_)) * 0.9f;
    intake += intakeHiss_.process(noise_.white()) * rr * thrSm_ * 0.12f;
    if (c.cycle == Cycle::Otto4 || c.cycle == Cycle::Otto2 || c.cycle == Cycle::Wankel4)
        intake += throttleWhistle_.process(noise_.white()) * std::max(0.0f, 0.8f - mapSm_) * rr * 0.35f;
    if (c.cycle == Cycle::Steam) intake = intakeHiss_.process(noise_.white()) * thrSm_ * 0.05f;
    pan(intake * 0.12f, -45.0f, L, R);

    // --- 機械音: 動弁打音/ピストンスラップ (モーダル共振) + チェーン/軸受ノイズ ---
    float mech = 0;
    for (int i = 0; i < 5; ++i) {
        mech += mechModes_[i].process(mechExc_[i] + (i == 0 ? noise_.white() * 0.004f * rr : 0.0f));
        mechExc_[i] = 0;
    }
    pan(mech * 0.35f, 0.0f, L, R);

    // --- 過給機 ---
    if (c.induction == InductionType::Turbo) {
        float shaftHz = turboSm_ * c.turboMaxRpm / 60.0f;
        float whine = turboOsc_.sine(foldAudible(shaftHz * c.compressorBlades, sr_), sr_);
        float tAmp = turboSm_ * turboSm_ * (0.25f + thrSm_) * 0.02f;
        turboWhoosh_.bandpass(900.0f + 3200.0f * turboSm_, 1.2f, sr_);
        float whoosh = turboWhoosh_.process(noise_.white()) * boostSm_ * 0.08f;
        uint32_t bov = feed.bovCount.load(std::memory_order_relaxed);
        if (bov != lastBov_) { lastBov_ = bov; bovEnv_ = 1.0f; }
        float bovS = bovBp_.process(noise_.white()) * bovEnv_ * 0.35f;
        bovEnv_ *= std::exp(-inv / 0.3f);
        pan(whine * tAmp + whoosh + bovS, 30.0f, L, R);
    } else if (c.induction == InductionType::Roots) {
        float rotorHz = rpm * c.rootsRatio / 60.0f;
        float f = rotorHz * c.rootsLobes * 2.0f;
        float w = rootsOsc_.sine(foldAudible(f, sr_), sr_) + 0.4f * rootsOsc2_.sine(foldAudible(2 * f, sr_), sr_);
        pan(w * (0.25f + boostSm_) * rr * 0.03f, 10.0f, L, R);
    }

    // --- ギア鳴き / プロペラ ---
    int gear = feed.gear.load(std::memory_order_relaxed);
    if (gear > 0 && !c.propeller) {
        float g = gearOsc_.sine(c.gearTeeth * rpm / 60.0f, sr_);
        pan(g * (0.15f + torqueSm_) * rr * 0.012f, 180.0f, L, R);
    }
    if (c.propeller) {
        float prpm = rpm * c.propReduction;
        float bpf = c.propBlades * prpm / 60.0f;
        float pr = prpm / (red * c.propReduction);
        float amp = 0.22f * pr * pr * (0.4f + 0.6f * loadSm_);
        const float h[4] = {1.0f, 0.55f, 0.3f, 0.18f};
        float v = 0;
        for (int k = 0; k < 4; ++k) v += h[k] * propOsc_[k].sine(bpf * (k + 1), sr_);
        v += rumbleLp_.process(noise_.white()) * pr * 0.8f;
        pan(v * amp, 0.0f, L, R);
    }
    return mono;
}

void EngineAcousticsDSP::renderTurbine(const AcousticConfig& c, const AudioFeed& feed, float& L, float& R) {
    float n1 = n1Sm_, n2 = n2Sm_;
    // ジェット混合騒音: 帯域制限ノイズ、推力とともに帯域・音量が上がる
    jetLp_.lowpass(250.0f + 2800.0f * n2, 0.6f, sr_);
    jetLp2_.lowpass(120.0f + 900.0f * n1, 0.7f, sr_);
    float wn = noise_.white();
    float jet = jetHp_.process(jetLp_.process(wn) * (0.3f + 0.7f * n2 * n2) + jetLp2_.process(wn) * n1 * n1 * 1.5f);
    float jetAmp = c.turbineKind == TurbineKind::Turboshaft ? 0.25f : 0.55f;
    pan(jet * jetAmp * n2, 180.0f, L, R);
    // 圧縮機ブレード通過音
    float compHz = c.turbineCompressorBlades * n2 * c.n2MaxRpm / 60.0f;
    float comp = compOsc_.sine(foldAudible(compHz, sr_), sr_) * 0.6f + compOsc2_.sine(foldAudible(compHz * 0.5f, sr_), sr_) * 0.3f;
    pan(comp * n2 * n2 * 0.05f, 0.0f, L, R);
    // ファン BPF とバズソー (動翼先端が超音速になる高 N1 域)
    if (c.fanBlades > 0) {
        float shaftHz = n1 * c.n1MaxRpm / 60.0f;
        float fan = fanOsc_.sine(c.fanBlades * shaftHz, sr_) * n1 * n1 * 0.09f;
        float buzz = 0;
        if (n1 > 0.72f) {
            const float amp[6] = {0.5f, 0.8f, 0.35f, 0.6f, 0.25f, 0.45f};
            for (int k = 0; k < 6; ++k) buzz += amp[k] * buzzOsc_[k].sine(shaftHz * (k + 3), sr_);
            buzz *= (n1 - 0.72f) * 0.12f;
        }
        pan(fan + buzz, 0.0f, L, R);
    }
    if (c.turbineKind == TurbineKind::Turboshaft) {
        float outRpm = outRpmSm_;
        float g = gearOsc_.sine(c.gearTeeth * outRpm * 12.0f / 60.0f, sr_);
        pan(g * n1 * 0.02f, 150.0f, L, R);
    }
    (void)feed;
}

void EngineAcousticsDSP::renderMotor(const AcousticConfig& c, const AudioFeed& feed, float& L, float& R) {
    float rpm = rpmSm_;
    float fe = ehzSm_;
    float tq = torqueSm_;
    float rr = rpm / std::max(1.0f, c.redlineRpm);
    bool on = feed.running.load(std::memory_order_relaxed);
    float v = 0;
    if (on) {
        // インバータ PWM: キャリア fc とその側帯波 fc ± 2fe, 2fc ± fe
        float fc = c.pwmHz;
        float pwmAmp = 0.012f * (0.25f + tq);
        v += pwmOsc_[0].sine(foldAudible(fc + 2 * fe, sr_), sr_) * pwmAmp;
        v += pwmOsc_[1].sine(foldAudible(std::fabs(fc - 2 * fe), sr_), sr_) * pwmAmp;
        v += pwmOsc_[2].sine(foldAudible(2 * fc + fe, sr_), sr_) * pwmAmp * 0.35f;
        // 電磁加振: PMSM は 6 次トルクリプル, 誘導機はスロット高調波
        float order = c.motorKind == MotorKind::PMSM ? 6.0f * fe : c.statorSlots * rpm / 60.0f + 2 * fe;
        v += emOsc_[0].sine(foldAudible(order, sr_), sr_) * tq * 0.03f;
        v += emOsc_[1].sine(foldAudible(2 * fe, sr_), sr_) * tq * 0.02f;
    }
    // 減速ギア鳴き + 風損
    v += gearOsc_.sine(foldAudible(c.gearTeeth * rpm / 60.0f, sr_), sr_) * (0.1f + tq) * rr * 0.03f;
    v += motorWindage_.process(noise_.white()) * rr * rr * 0.05f;
    pan(v * 3.0f, 0.0f, L, R);  // 電動機は実機では静かだが、聴取用に持ち上げる
}

void EngineAcousticsDSP::render(float* out, int frames, const AudioFeed& feed) {
    if (AcousticConfig* p = pending_.exchange(nullptr)) {
        AcousticConfig* old = cfg_;
        cfg_ = p;
        resetState();
        // 旧構成の解放は制御スレッド (次回 setConfig) に任せる。
        // 回収前に再度切り替わった稀なケースのみここで解放する。
        delete retired_.exchange(old);
    }
    if (!cfg_) {
        std::fill(out, out + frames * 2, 0.0f);
        return;
    }
    const AcousticConfig& c = *cfg_;
    // ブロック単位で共有値を読み、サンプル単位で平滑化
    const float tRpm = feed.rpm.load(std::memory_order_relaxed);
    const float tThr = feed.throttle.load(std::memory_order_relaxed);
    const float tMap = feed.mapBar.load(std::memory_order_relaxed);
    const float tBoost = feed.boostBar.load(std::memory_order_relaxed);
    const float tTurbo = feed.turboFrac.load(std::memory_order_relaxed);
    const float tLoad = feed.loadFrac.load(std::memory_order_relaxed);
    const float tTq = feed.torqueFrac.load(std::memory_order_relaxed);
    const float tN1 = feed.n1Frac.load(std::memory_order_relaxed);
    const float tN2 = feed.n2Frac.load(std::memory_order_relaxed);
    const float tOut = feed.outputRpm.load(std::memory_order_relaxed);
    const float tEhz = feed.electricalHz.load(std::memory_order_relaxed);
    const float gain = feed.masterGain.load(std::memory_order_relaxed);
    camYaw_ = feed.cameraYaw.load(std::memory_order_relaxed);
    const bool running = feed.running.load(std::memory_order_relaxed);
    slapGain_ = running ? 1.0f : 0.3f;
    const float k = smoothCoef(0.02f, sr_);
    const float kSlow = smoothCoef(0.08f, sr_);

    for (int i = 0; i < frames; ++i) {
        rpmSm_ += (tRpm - rpmSm_) * k;
        thrSm_ += (tThr - thrSm_) * k;
        mapSm_ += (tMap - mapSm_) * k;
        boostSm_ += (tBoost - boostSm_) * kSlow;
        turboSm_ += (tTurbo - turboSm_) * kSlow;
        loadSm_ += (tLoad - loadSm_) * kSlow;
        torqueSm_ += (tTq - torqueSm_) * kSlow;
        n1Sm_ += (tN1 - n1Sm_) * kSlow;
        n2Sm_ += (tN2 - n2Sm_) * kSlow;
        outRpmSm_ += (tOut - outRpmSm_) * kSlow;
        ehzSm_ += (tEhz - ehzSm_) * k;
        float L = 0, R = 0;
        switch (c.family) {
            case Family::Reciprocating:
            case Family::Wankel: renderCombustion(c, feed, std::max(0.0f, rpmSm_), L, R); break;
            case Family::Turbine: renderTurbine(c, feed, L, R); break;
            case Family::Electric: renderMotor(c, feed, L, R); break;
        }
        L = dsp::softClip(dc_[0].process(L) * gain);
        R = dsp::softClip(dc_[1].process(R) * gain);
        out[2 * i] = std::isfinite(L) ? L : 0.0f;
        out[2 * i + 1] = std::isfinite(R) ? R : 0.0f;
    }
}

}  // namespace es
