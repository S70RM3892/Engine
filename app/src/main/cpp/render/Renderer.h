// OpenGL ES 3.0 レンダラ。PBR / X 線 / 断面 (ステンシルキャップ) / 熱・応力ヒートマップ。
#pragma once

#include <GLES3/gl3.h>

#include <vector>

#include "../sim/EngineSimulation.h"
#include "Camera.h"
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
    int sectionAxis = 0;      // 0: クランク軸に直交 (X), 1: クランク軸を含む縦断 (Z)
};

class Renderer {
public:
    bool initGL();
    void releaseGL();  // コンテキスト喪失時: GL 名を捨てる (glDelete しない)
    void resize(int w, int h);
    void setEngine(const EngineSpec& spec);
    void render(const EngineSimulation& sim, float dt, const ViewInput& in);
    float cameraYawDeg() const { return rad2deg(cam_.yaw()); }
    // 実際に使われている描画モード (プリセットで自動切替された場合も反映)
    int effectiveMode() const { return mode_; }

private:
    struct GpuMesh { GLuint vao = 0, vbo = 0, ibo = 0; GLsizei count = 0; Vec3 center; };
    struct Prog {
        GLuint id = 0;
        GLint model = -1, viewProj = -1, camPos = -1, base = -1, metal = -1, rough = -1, coat = -1, alpha = -1;
        GLint clip = -1, clipOn = -1, heat = -1, emissive = -1, xray = -1, capColor = -1;
    };
    void uploadScene();
    void freeMeshes();
    void applyPreset(int preset, bool snap);
    void drawNode(size_t i, const Mat4& vp, float alpha, bool xray, const Vec3& heat, float heatW, const Vec3& emissive);
    void nodeShading(size_t i, const EngineSimulation& sim, Vec3& heat, float& heatW, Vec3& emissive) const;

    bool glReady_ = false;
    int w_ = 1, h_ = 1;
    Prog pbr_, cap_, bg_;
    GLuint bgVao_ = 0, capVao_ = 0, capVbo_ = 0;
    std::vector<GpuMesh> gpu_;
    bool sceneDirty_ = false;

    EngineSpec spec_;
    EngineKinematics kin_;
    Scene scene_;
    KinState ks_;
    std::vector<Mat4> world_;
    CameraRig cam_;
    int lastPresetSerial_ = -1;
    int preset_ = 0;
    int mode_ = 0;
    int lastInMode_ = 0;
    bool forcedSection_ = false;
    float outputAngle_ = 0;
    double lastVisTheta_ = 0;
    bool haveLastTheta_ = false;
    float stressTorque_ = 0;
};

}  // namespace es
