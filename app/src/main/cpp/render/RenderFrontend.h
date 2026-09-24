// 描画 API に依存しない部分: キネマティクス評価 → ノード行列、カメラ/プリセット、表示モード、
// 熱・応力の色付け、断面平面、ゲーム用エフェクト (パーティクル/カメラシェイク/閃光)。
#pragma once

#include <vector>

#include "../sim/EngineSimulation.h"
#include "Camera.h"
#include "FrameData.h"
#include "Scene.h"

namespace es {

enum class RenderMode : int { Solid = 0, XRay = 1, Section = 2, Thermal = 3, Stress = 4 };

struct ViewInput {
    int preset = 0;
    int presetSerial = 0;     // 同じプリセットの再押下を検出
    int mode = 0;
    float yawRate = 0, pitchRate = 0;  // [rad/s] ジョイスティック
    float zoom = 1.0f;
    float sectionOffset = 0;  // -1..1 (シーン半径比)
    int sectionAxis = 0;      // 0: 自動, 1: 逆軸
    float autoOrbit = 0;      // ゲーム用: 自動周回速度 [rad/s]
};

class RenderFrontend {
public:
    void setEngine(const EngineSpec& spec);
    // 0 = 演出なし (シミュレータ), 1 = ゲーム演出 (炎・火花・煙・シェイク・閃光・排気管の赤熱)
    void setEffects(float level) { effects_ = level; }
    void build(const EngineSimulation& sim, float dt, const ViewInput& in, float aspect, FrameData& out);

    const Scene& scene() const { return scene_; }
    int sceneVersion() const { return sceneVersion_; }
    float cameraYawDeg() const { return rad2deg(cam_.yaw()); }
    int effectiveMode() const { return mode_; }

private:
    struct Particle {
        Vec3 pos, vel;
        float life = 0, maxLife = 1, size0 = 0.01f, size1 = 0.02f;
        Vec3 c0, c1;       // 色 (加算)
        float a0 = 1, a1 = 0;
        float drag = 1.5f, buoyancy = 0;
    };

    void applyPreset(int preset, bool snap);
    void shadeNode(size_t i, const EngineSimulation& sim, DrawItem& d, float alpha, bool xray, bool clip) const;
    void updateEffects(const EngineSimulation& sim, float dt);
    void emit(const Vec3& pos, const Vec3& vel, float life, float s0, float s1, const Vec3& c0, const Vec3& c1, float a0,
              float drag, float buoyancy);
    void buildParticles(const Mat4& view, FrameData& out) const;
    float rnd();

    EngineSpec spec_;
    EngineKinematics kin_;
    Scene scene_;
    KinState ks_;
    std::vector<Mat4> world_;
    std::vector<Vec3> meshCenter_;
    CameraRig cam_;
    int sceneVersion_ = 0;
    int lastPresetSerial_ = -1;
    int preset_ = 0;
    int mode_ = 0;
    int lastInMode_ = 0;
    bool forcedSection_ = false;
    float outputAngle_ = 0;
    double lastVisTheta_ = 0;
    bool haveLastTheta_ = false;
    float stressTorque_ = 0;

    // 演出
    float effects_ = 0;
    std::vector<Particle> particles_;
    std::vector<double> lastEvo_;
    uint32_t lastAfterfire_ = 0, lastShift_ = 0;
    uint32_t seed_ = 0x1234567u;
    float shake_ = 0, flash_ = 0, time_ = 0;
    Vec3 flashColor_{1.0f, 0.55f, 0.2f};
};

}  // namespace es
