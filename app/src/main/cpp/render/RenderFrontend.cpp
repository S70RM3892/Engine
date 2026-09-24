#include "RenderFrontend.h"

#include <algorithm>
#include <cmath>

namespace es {

namespace {

// 部品温度カラーマップ (青→シアン→緑→黄→赤→白)
Vec3 heatRamp(float t) {
    t = clampf(t, 0.0f, 1.0f);
    const Vec3 stops[6] = {{0.08f, 0.15f, 0.75f}, {0.1f, 0.7f, 0.9f}, {0.2f, 0.85f, 0.3f},
                           {0.98f, 0.85f, 0.15f}, {0.95f, 0.2f, 0.08f}, {1.0f, 0.95f, 0.9f}};
    float x = t * 5.0f;
    int i = std::min(4, static_cast<int>(x));
    return lerp(stops[i], stops[i + 1], x - i);
}

// 燃焼ガス (黒体放射風: 暗青→紫→赤→橙→黄白)
Vec3 flameRamp(float t) {
    t = clampf(t, 0.0f, 1.0f);
    const Vec3 stops[5] = {{0.15f, 0.25f, 0.55f}, {0.45f, 0.2f, 0.6f}, {0.9f, 0.2f, 0.1f}, {1.0f, 0.6f, 0.1f}, {1.0f, 0.95f, 0.75f}};
    float x = t * 4.0f;
    int i = std::min(3, static_cast<int>(x));
    return lerp(stops[i], stops[i + 1], x - i);
}

// 応力カラーマップ (青→緑→黄→赤)
Vec3 stressRamp(float t) {
    t = clampf(t, 0.0f, 1.0f);
    const Vec3 stops[4] = {{0.1f, 0.25f, 0.9f}, {0.1f, 0.85f, 0.35f}, {0.95f, 0.9f, 0.1f}, {0.95f, 0.1f, 0.08f}};
    float x = t * 3.0f;
    int i = std::min(2, static_cast<int>(x));
    return lerp(stops[i], stops[i + 1], x - i);
}

// 部品温度 300K(青)〜1000K(白) / 燃焼ガス 300K〜2600K
float tempToT(float k) { return (k - 300.0f) / (1000.0f - 300.0f); }
float gasToT(float k) { return (k - 300.0f) / (2600.0f - 300.0f); }

void setModel(DrawItem& d, const Mat4& m) {
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 4; ++c) d.model[r * 4 + c] = m.at(r, c);
}

}  // namespace

float RenderFrontend::rnd() {
    seed_ = seed_ * 1664525u + 1013904223u;
    return static_cast<float>(seed_ >> 8) / 16777216.0f;
}

void RenderFrontend::setEngine(const EngineSpec& spec) {
    spec_ = spec;
    kin_.init(&spec_);
    buildScene(spec_, scene_);
    ++sceneVersion_;
    world_.clear();
    meshCenter_.clear();
    for (const MeshData& m : scene_.meshes) {
        Vec3 a, b;
        m.bounds(a, b);
        meshCenter_.push_back((a + b) * 0.5f);
    }
    haveLastTheta_ = false;
    outputAngle_ = 0;
    particles_.clear();
    lastEvo_.assign(spec_.chambers.size() + 1, 0.0);
    applyPreset(preset_, true);
}

void RenderFrontend::applyPreset(int preset, bool snap) {
    preset_ = preset;
    OrbitState s;
    const Scene& sc = scene_;
    const bool longitudinal = spec_.family == Family::Turbine || spec_.family == Family::Electric;
    switch (static_cast<CameraPreset>(preset)) {
        case CameraPreset::Overview:
            s.target = sc.center;
            s.yaw = 0.7f;
            s.pitch = 0.38f;
            s.dist = sc.radius * 2.9f;
            break;
        case CameraPreset::CylinderSection:
            s.target = sc.cyl1Center;
            // プロペラ機は後方から (プロペラ越しにならないように)
            s.yaw = longitudinal ? 0.0f : (spec_.drivetrain.propeller ? kPi * 0.5f : -kPi * 0.5f);
            s.pitch = 0.12f;
            s.dist = sc.cyl1Size * 1.5f;
            break;
        case CameraPreset::CrankFront:
            s.target = sc.center;
            s.yaw = -kPi * 0.5f;
            s.pitch = 0.05f;
            s.dist = sc.yzRadius * 3.2f;
            break;
        case CameraPreset::ValveFollow:
            s.target = sc.cyl1Center;
            s.yaw = -kPi * 0.5f + 0.55f;
            s.pitch = 0.35f;
            s.dist = std::max(sc.cyl1Size * 0.9f, spec_.bore * 3.0f);
            break;
        case CameraPreset::DriveOutput:
            s.target = sc.outputAnchor;
            s.yaw = 0.9f;
            s.pitch = 0.32f;
            s.dist = sc.outputSize * 2.2f;
            break;
    }
    cam_.setGoal(s, snap);
}

void RenderFrontend::shadeNode(size_t i, const EngineSimulation& sim, DrawItem& d, float alpha, bool xray, bool clip) const {
    const SceneNode& n = scene_.nodes[i];
    const Telemetry& tl = sim.telemetry();
    d.mesh = n.mesh;
    setModel(d, world_[i]);
    d.base[0] = n.mat.base.x; d.base[1] = n.mat.base.y; d.base[2] = n.mat.base.z; d.base[3] = n.mat.metallic;
    d.mat[0] = n.mat.roughness; d.mat[1] = n.mat.coat; d.mat[2] = alpha;
    d.mat[3] = static_cast<float>((clip ? kFlagClip : 0u) | (xray ? kFlagXray : 0u));
    Vec3 heat{0, 0, 0}, emissive{0, 0, 0};
    float heatW = 0;
    const double th = sim.visualThetaDeg();
    const int nch = static_cast<int>(spec_.chambers.size());
    auto gasT = [&](int c) { return (c >= 0 && c < nch) ? sim.chamberTemperature(c, th) : 300.0f; };

    if (n.gearIndex > 0 && n.gearIndex == sim.effectiveGear()) emissive = {0.03f, 0.10f, 0.22f};

    if (n.gas) {
        // 燃焼ガス: 温度で発色、燃焼中は発光
        float T = gasT(n.heatIndex);
        float burn = (n.heatIndex < nch) ? sim.chamberBurn(n.heatIndex, th) : 0.0f;
        if (spec_.cycle == Cycle::Steam) {
            float p = sim.chamberPressure(n.heatIndex, th) / std::max(1.0f, spec_.boilerPressure);
            heat = lerp(Vec3{0.3f, 0.4f, 0.55f}, Vec3{0.9f, 0.95f, 1.0f}, clampf(p, 0, 1));
        } else if (spec_.cycle == Cycle::Stirling) {
            heat = spec_.chambers[n.heatIndex].kind == ChamberKind::StirlingHot ? Vec3{1.0f, 0.35f, 0.1f} : Vec3{0.2f, 0.5f, 1.0f};
        } else {
            heat = flameRamp(gasToT(T));
        }
        heatW = 1.0f;
        emissive = heat * (0.15f + 1.6f * burn);
    } else if (mode_ == static_cast<int>(RenderMode::Thermal)) {
        float T = 0;
        switch (n.heat) {
            case HeatSrc::Gas: T = gasT(n.heatIndex); break;
            case HeatSrc::Head: T = sim.headTempK() + 0.05f * (gasT(n.heatIndex) - 700.0f); break;
            case HeatSrc::Piston: T = sim.pistonTempK(); break;
            case HeatSrc::Liner: T = tl.coolantK + 0.35f * (sim.headTempK() - tl.coolantK); break;
            case HeatSrc::Exhaust: T = tl.egtK * 0.92f; break;
            case HeatSrc::Oil: T = tl.oilK; break;
            case HeatSrc::Coolant: T = tl.coolantK; break;
            case HeatSrc::Rotor:
                if (spec_.family == Family::Wankel) {
                    float s = 0;
                    for (int k = 0; k < 3; ++k) s += gasT(n.heatIndex * 3 + k);
                    T = tl.coolantK + 0.25f * (s / 3.0f - tl.coolantK);
                } else {
                    T = 320.0f + 0.2f * tl.currentA;
                }
                break;
            case HeatSrc::Combustor: T = tl.egtK + 350.0f; break;
            case HeatSrc::TurbineHot: T = tl.egtK; break;
            case HeatSrc::Winding: T = 320.0f + 0.35f * tl.currentA; break;
            case HeatSrc::None: T = 300.0f; break;
        }
        heat = heatRamp(tempToT(T));
        heatW = n.heat == HeatSrc::None ? 0.5f : 0.92f;
        if (T > 900.0f) emissive = heat * ((T - 900.0f) / 1500.0f);
    } else if (mode_ == static_cast<int>(RenderMode::Stress)) {
        float s = 0;
        bool has = true;
        switch (n.stress) {
            case StressSrc::Piston:
            case StressSrc::Rod: s = sim.pistonLoadFraction(n.stressIndex, th); break;
            case StressSrc::Crank: s = stressTorque_; break;
            default: has = false; break;
        }
        heat = has ? stressRamp(s) : Vec3{0.35f, 0.36f, 0.38f};
        heatW = has ? 0.92f : 0.7f;
    }
    // ゲーム演出: 排気系・タービンの赤熱 (EGT に応じて発光)
    if (effects_ > 0 && (n.heat == HeatSrc::Exhaust || n.heat == HeatSrc::TurbineHot || n.heat == HeatSrc::Combustor)) {
        float g = clampf((tl.egtK - 720.0f) / 600.0f, 0.0f, 1.2f) * effects_;
        emissive += Vec3{1.0f, 0.28f, 0.05f} * (g * g * 1.6f);
    }
    d.heat[0] = heat.x; d.heat[1] = heat.y; d.heat[2] = heat.z; d.heat[3] = heatW;
    d.emissive[0] = emissive.x; d.emissive[1] = emissive.y; d.emissive[2] = emissive.z; d.emissive[3] = 0;
}

void RenderFrontend::emit(const Vec3& pos, const Vec3& vel, float life, float s0, float s1, const Vec3& c0, const Vec3& c1,
                          float a0, float drag, float buoyancy) {
    if (particles_.size() >= 3000) return;
    Particle p;
    p.pos = pos;
    p.vel = vel;
    p.maxLife = life;
    p.life = life;
    p.size0 = s0;
    p.size1 = s1;
    p.c0 = c0;
    p.c1 = c1;
    p.a0 = a0;
    p.drag = drag;
    p.buoyancy = buoyancy;
    particles_.push_back(p);
}

void RenderFrontend::updateEffects(const EngineSimulation& sim, float dt) {
    time_ += dt;
    shake_ *= std::exp(-dt * 6.0f);
    flash_ *= std::exp(-dt * 7.0f);
    const Telemetry& tl = sim.telemetry();
    // 粒子の運動
    for (Particle& p : particles_) {
        p.life -= dt;
        p.vel = p.vel * std::exp(-p.drag * dt);
        p.vel.y += p.buoyancy * dt;
        p.pos += p.vel * dt;
    }
    particles_.erase(std::remove_if(particles_.begin(), particles_.end(), [](const Particle& p) { return p.life <= 0; }),
                     particles_.end());
    if (effects_ <= 0) return;

    const float S = scene_.exhaustScale;
    const float rr = clampf(tl.rpm / std::max(1.0f, spec_.redlineRpm), 0.0f, 1.2f);
    const float thr = tl.throttle;
    auto jitter = [&](float k) { return Vec3{(rnd() - 0.5f) * k, (rnd() - 0.5f) * k, (rnd() - 0.5f) * k}; };

    if (spec_.family == Family::Turbine) {
        // ノズルからの連続噴流 (N2 とともに長く明るく)
        if (!scene_.exhaustPort.empty()) {
            float n2 = tl.n2 / 100.0f;
            int count = static_cast<int>(effects_ * (2 + 10 * n2 * n2));
            for (int k = 0; k < count; ++k) {
                Vec3 v = scene_.exhaustDir[0] * (S * (20.0f + 60.0f * n2)) + jitter(S * 6);
                float hot = clampf((n2 - 0.5f) * 2.0f, 0.0f, 1.0f);
                emit(scene_.exhaustPort[0] + jitter(S * 0.6f), v, 0.18f + 0.1f * rnd(), S * 0.8f, S * (2.0f + 2.5f * n2),
                     lerp(Vec3{0.25f, 0.35f, 0.9f}, Vec3{1.0f, 0.55f, 0.15f}, hot), Vec3{0.05f, 0.02f, 0.02f}, 0.0f, 2.0f, 0);
            }
        }
    } else if (spec_.family == Family::Electric) {
        // 大電流時の青いアーク
        float k = tl.currentA / 350.0f;
        if (rnd() < k * 0.6f * effects_) {
            float a = rnd() * kTwoPi;
            float R = spec_.motor.radius * 0.8f;
            Vec3 p{spec_.motor.length * (rnd()), R * std::cos(a), R * std::sin(a)};
            for (int i = 0; i < 6; ++i)
                emit(p, jitter(R * 8), 0.12f, R * 0.04f, R * 0.01f, {0.5f, 0.7f, 1.0f}, {0.2f, 0.3f, 1.0f}, 0, 3, 0);
        }
    } else {
        // 排気弁開ごとの排気炎 / 蒸気 / ディーゼル黒煙
        const int nch = std::min<int>(static_cast<int>(scene_.exhaustPort.size()), static_cast<int>(spec_.chambers.size()));
        for (int c = 0; c < nch; ++c) {
            double t = sim.lastExhaustTime(c);
            if (t <= lastEvo_[c]) continue;
            lastEvo_[c] = t;
            float amp = sim.lastExhaustAmp(c);
            const Vec3& port = scene_.exhaustPort[c];
            const Vec3& dir = scene_.exhaustDir[c];
            if (spec_.cycle == Cycle::Steam) {
                for (int k = 0; k < 4; ++k)
                    emit(port + jitter(S * 0.3f), (dir * 0.6f + Vec3{0, 1, 0}) * (S * 8.0f) + jitter(S * 3), 1.4f, S * 0.8f, S * 5.0f,
                         {0.55f, 0.55f, 0.58f}, {0.25f, 0.25f, 0.27f}, 0.45f, 1.2f, S * 4.0f);
                continue;
            }
            if (spec_.cycle == Cycle::Stirling) continue;
            float load = clampf(amp / 4.0f, 0.0f, 1.5f);
            int count = 1 + static_cast<int>(effects_ * (1 + 3 * load * thr));
            for (int k = 0; k < count; ++k) {
                Vec3 v = dir * (S * (10.0f + 25.0f * load)) + jitter(S * 4);
                Vec3 hot = lerp(Vec3{0.2f, 0.35f, 1.0f}, Vec3{1.0f, 0.5f, 0.12f}, clampf(load * thr * 1.3f, 0.0f, 1.0f));
                emit(port + jitter(S * 0.2f), v, 0.07f + 0.08f * load, S * 0.6f, S * (1.2f + 1.5f * load), hot,
                     Vec3{0.1f, 0.03f, 0.01f}, 0.0f, 6.0f, 0);
            }
            // ディーゼルは高負荷で黒煙
            if (spec_.isDiesel() && thr > 0.55f && rnd() < 0.5f)
                emit(port, dir * (S * 6) + Vec3{0, S * 3, 0}, 1.0f, S * 1.2f, S * 6.0f, {0.05f, 0.05f, 0.05f},
                     {0.02f, 0.02f, 0.02f}, 0.35f, 1.0f, S * 2.0f);
        }
        // 後燃え (アフターファイア): 火の玉 + 火花 + 閃光 + シェイク
        if (tl.afterfireCount != lastAfterfire_ && nch > 0) {
            int bursts = static_cast<int>(std::min<uint32_t>(3, tl.afterfireCount - lastAfterfire_));
            for (int b = 0; b < bursts; ++b) {
                int c = static_cast<int>(rnd() * nch) % nch;
                const Vec3& port = scene_.exhaustPort[c];
                const Vec3& dir = scene_.exhaustDir[c];
                for (int k = 0; k < 14; ++k)
                    emit(port, dir * (S * 25) + jitter(S * 18), 0.25f + 0.15f * rnd(), S * 1.5f, S * 4.5f, {1.0f, 0.6f, 0.15f},
                         {0.4f, 0.05f, 0.0f}, 0.0f, 4.0f, S * 3);
                for (int k = 0; k < 18; ++k)
                    emit(port, dir * (S * 30) + jitter(S * 40), 0.5f + 0.4f * rnd(), S * 0.12f, S * 0.05f, {1.0f, 0.85f, 0.4f},
                         {1.0f, 0.3f, 0.05f}, 0.0f, 0.8f, -9.81f);
            }
            flash_ = std::min(1.0f, flash_ + 0.6f);
            flashColor_ = {1.0f, 0.5f, 0.15f};
            shake_ += 0.012f * scene_.radius;
        }
        // リミッタ: 火花
        if (tl.limiter && nch > 0 && rnd() < 0.5f * effects_) {
            int c = static_cast<int>(rnd() * nch) % nch;
            for (int k = 0; k < 5; ++k)
                emit(scene_.exhaustPort[c], jitter(S * 30), 0.4f, S * 0.1f, S * 0.04f, {1.0f, 0.9f, 0.5f}, {1.0f, 0.3f, 0.0f}, 0, 0.8f, -9.81f);
        }
    }
    lastAfterfire_ = tl.afterfireCount;
    if (tl.shiftCount != lastShift_) {
        shake_ += 0.006f * scene_.radius;
        flash_ = std::min(1.0f, flash_ + 0.25f);
        flashColor_ = {0.4f, 0.7f, 1.0f};
        lastShift_ = tl.shiftCount;
    }
    // 燃焼パルスによる常時の微振動
    float base = effects_ * (0.0015f + 0.004f * rr * (0.3f + thr)) * scene_.radius;
    shake_ = std::max(shake_, base);
}

void RenderFrontend::buildParticles(const Mat4& view, FrameData& out) const {
    out.particles.clear();
    if (particles_.empty()) return;
    Vec3 right{view.at(0, 0), view.at(0, 1), view.at(0, 2)};
    Vec3 up{view.at(1, 0), view.at(1, 1), view.at(1, 2)};
    out.particles.reserve(particles_.size() * 6);
    for (const Particle& p : particles_) {
        float u = 1.0f - p.life / p.maxLife;  // 0 → 1
        float size = p.size0 + (p.size1 - p.size0) * u;
        Vec3 col = lerp(p.c0, p.c1, u);
        float a = p.a0 * (1.0f - u);
        float fade = (1.0f - u) * std::min(1.0f, u * 8.0f + 0.3f);
        // プリマルチプライド: rgb = 発光 + 煙色×α, a = 遮蔽
        float cr = col.x * fade, cg = col.y * fade, cb = col.z * fade;
        Vec3 r = right * size, t = up * size;
        Vec3 q[4] = {p.pos - r - t, p.pos + r - t, p.pos + r + t, p.pos - r + t};
        const float uv[4][2] = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        const int idx[6] = {0, 1, 2, 0, 2, 3};
        for (int k : idx) {
            ParticleVertex v;
            v.pos[0] = q[k].x; v.pos[1] = q[k].y; v.pos[2] = q[k].z;
            v.uv[0] = uv[k][0]; v.uv[1] = uv[k][1];
            v.color[0] = cr; v.color[1] = cg; v.color[2] = cb; v.color[3] = a;
            out.particles.push_back(v);
        }
    }
}

void RenderFrontend::build(const EngineSimulation& sim, float dt, const ViewInput& in, float aspect, FrameData& out) {
    out.clearLists();
    // プリセット/モード: 「シリンダー断面」プリセットは断面モードを自動で有効にし、
    // ユーザーが描画モードを選び直したら解除する
    if (in.presetSerial != lastPresetSerial_) {
        bool first = lastPresetSerial_ < 0;
        lastPresetSerial_ = in.presetSerial;
        applyPreset(in.preset, first);
        forcedSection_ = in.preset == static_cast<int>(CameraPreset::CylinderSection);
    }
    if (in.mode != lastInMode_) forcedSection_ = false;
    lastInMode_ = in.mode;
    mode_ = forcedSection_ ? static_cast<int>(RenderMode::Section) : in.mode;

    // キネマティクス (映像クランク角)
    double vt = sim.visualThetaDeg();
    kin_.evaluate(vt, ks_);
    if (haveLastTheta_) {
        double period = (spec_.family == Family::Wankel ? 1080.0 : 720.0) * 360.0;
        double d = vt - lastVisTheta_;
        if (d < -0.5 * period) d += period;
        if (d > 0.5 * period) d -= period;
        int g = sim.effectiveGear();
        const auto& gears = spec_.drivetrain.gears;
        if (g > 0 && g <= static_cast<int>(gears.size())) outputAngle_ += static_cast<float>(deg2radD(d) / gears[g - 1]);
        else if (gears.size() == 1) outputAngle_ += static_cast<float>(deg2radD(d) / gears[0]);
        outputAngle_ = static_cast<float>(wrapPos(outputAngle_, kTwoPiD * 1000.0));
    }
    lastVisTheta_ = vt;
    haveLastTheta_ = true;
    VisualAngles va;
    for (int i = 0; i < 6; ++i) va.spool[i] = sim.visualSpoolAngle(i);
    va.outputShaft = outputAngle_;
    evaluateScene(scene_, spec_, ks_, va, world_);

    if (mode_ == static_cast<int>(RenderMode::Stress) && spec_.hasCombustion()) {
        double T = 0;
        for (size_t c = 0; c < spec_.chambers.size(); ++c)
            T += (sim.chamberPressure(static_cast<int>(c), vt) - 101325.0) * kin_.chamberDVdTheta(static_cast<int>(c), vt);
        stressTorque_ = clampf(static_cast<float>(std::fabs(T) / (spec_.peakTorqueEstimate * 4.0f)), 0, 1);
    }

    updateEffects(sim, dt);

    // カメラ (バルブ追従 / ゲームの自動周回 / シェイク)
    const Vec3* follow = nullptr;
    Vec3 followPt;
    if (preset_ == static_cast<int>(CameraPreset::ValveFollow) && scene_.followValveNode >= 0) {
        const SceneNode& fn = scene_.nodes[scene_.followValveNode];
        followPt = world_[scene_.followValveNode].transformPoint(meshCenter_[fn.mesh]);
        follow = &followPt;
    }
    cam_.update(dt, in.yawRate + in.autoOrbit, in.pitchRate, in.zoom, follow);
    Mat4 view = cam_.view();
    if (shake_ > 1e-6f) {
        Vec3 j{(rnd() - 0.5f) * 2 * shake_, (rnd() - 0.5f) * 2 * shake_, (rnd() - 0.5f) * 2 * shake_};
        view = view * Mat4::translate(j);
    }
    out.viewProj = cam_.proj(aspect) * view;
    Vec3 eye = cam_.eye();
    out.camPos = eye;
    out.time = time_;
    out.flash[0] = flashColor_.x; out.flash[1] = flashColor_.y; out.flash[2] = flashColor_.z; out.flash[3] = flash_ * effects_;

    const RenderMode mode = static_cast<RenderMode>(mode_);
    const bool section = mode == RenderMode::Section;
    const bool housingTransparent = mode == RenderMode::XRay || mode == RenderMode::Thermal || mode == RenderMode::Stress;
    const bool showGas = mode != RenderMode::Solid;
    out.section = section;

    // 断面平面 (カメラ側を切り取る)。sectionAxis 0 = 自動 (往復/ロータリーはクランク軸に直交, タービン/モータは縦断)
    const bool longitudinal = spec_.family == Family::Turbine || spec_.family == Family::Electric;
    const bool cutZ = (in.sectionAxis != 0) != longitudinal;
    Vec3 pn = cutZ ? Vec3{0, 0, 1} : Vec3{1, 0, 0};
    Vec3 p0 = cutZ ? Vec3{0, 0, scene_.center.z + in.sectionOffset * scene_.radius}
                   : Vec3{scene_.sectionX + in.sectionOffset * scene_.radius, 0, 0};
    if (dot(eye - p0, pn) < 0) pn = -pn;
    out.clip[0] = pn.x; out.clip[1] = pn.y; out.clip[2] = pn.z; out.clip[3] = -dot(pn, p0);

    DrawItem d;
    for (size_t i = 0; i < scene_.nodes.size(); ++i) {
        const SceneNode& n = scene_.nodes[i];
        if (n.gas) continue;
        if (n.housing && housingTransparent) continue;
        shadeNode(i, sim, d, 1.0f, false, section && n.sectionCut);
        out.opaque.push_back(d);
    }
    if (section) {
        int k = 0;
        for (size_t i = 0; i < scene_.nodes.size(); ++i) {
            const SceneNode& n = scene_.nodes[i];
            if (!n.sectionCut || n.gas) continue;
            shadeNode(i, sim, d, 1.0f, false, true);
            out.stencil.push_back(d);
            out.stencilGroup.push_back(static_cast<uint8_t>(k++ % 8));
        }
        Vec3 u = normalize(cross(pn, std::fabs(pn.y) < 0.9f ? Vec3{0, 1, 0} : Vec3{1, 0, 0}));
        Vec3 v = cross(pn, u);
        float S = scene_.radius * 6.0f;
        Vec3 c = p0 + (scene_.center - p0 - pn * dot(scene_.center - p0, pn));
        Vec3 q[4] = {c - u * S - v * S, c + u * S - v * S, c - u * S + v * S, c + u * S + v * S};
        for (int j = 0; j < 4; ++j) { out.capQuad[j * 3] = q[j].x; out.capQuad[j * 3 + 1] = q[j].y; out.capQuad[j * 3 + 2] = q[j].z; }
        const Vec3 capCols[8] = {{0.78f, 0.22f, 0.18f}, {0.85f, 0.55f, 0.2f}, {0.7f, 0.7f, 0.72f}, {0.25f, 0.55f, 0.8f},
                                 {0.8f, 0.3f, 0.25f},  {0.6f, 0.62f, 0.66f}, {0.9f, 0.6f, 0.3f},  {0.35f, 0.6f, 0.75f}};
        for (int g = 0; g < 8; ++g) out.capColor[g] = capCols[g];
    }
    if (showGas) {
        for (size_t i = 0; i < scene_.nodes.size(); ++i) {
            if (!scene_.nodes[i].gas) continue;
            shadeNode(i, sim, d, section ? 0.75f : 0.55f, false, section);
            out.transparent.push_back(d);
        }
    }
    if (housingTransparent) {
        for (size_t i = 0; i < scene_.nodes.size(); ++i) {
            if (!scene_.nodes[i].housing) continue;
            shadeNode(i, sim, d, mode == RenderMode::XRay ? 0.06f : 0.18f, true, false);
            out.transparent.push_back(d);
        }
    }
    buildParticles(view, out);
}

}  // namespace es
