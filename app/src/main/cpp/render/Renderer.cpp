#include "Renderer.h"

#include <algorithm>
#include <cmath>
#include <cstdio>

#include "Shaders.h"

#ifdef __ANDROID__
#include <android/log.h>
#define ES_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "EngineSim", __VA_ARGS__)
#else
#define ES_LOGE(...) std::fprintf(stderr, __VA_ARGS__)
#endif

namespace es {

namespace {

GLuint compile(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[2048];
        glGetShaderInfoLog(s, sizeof(log), nullptr, log);
        ES_LOGE("shader compile error: %s\n", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

GLuint link(const char* vs, const char* fs) {
    GLuint v = compile(GL_VERTEX_SHADER, vs), f = compile(GL_FRAGMENT_SHADER, fs);
    if (!v || !f) return 0;
    GLuint p = glCreateProgram();
    glAttachShader(p, v);
    glAttachShader(p, f);
    glLinkProgram(p);
    glDeleteShader(v);
    glDeleteShader(f);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[2048];
        glGetProgramInfoLog(p, sizeof(log), nullptr, log);
        ES_LOGE("program link error: %s\n", log);
        glDeleteProgram(p);
        return 0;
    }
    return p;
}

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

}  // namespace

bool Renderer::initGL() {
    auto locs = [](Prog& p) {
        p.model = glGetUniformLocation(p.id, "uModel");
        p.viewProj = glGetUniformLocation(p.id, "uViewProj");
        p.camPos = glGetUniformLocation(p.id, "uCamPos");
        p.base = glGetUniformLocation(p.id, "uBase");
        p.metal = glGetUniformLocation(p.id, "uMetal");
        p.rough = glGetUniformLocation(p.id, "uRough");
        p.coat = glGetUniformLocation(p.id, "uCoat");
        p.alpha = glGetUniformLocation(p.id, "uAlpha");
        p.clip = glGetUniformLocation(p.id, "uClip");
        p.clipOn = glGetUniformLocation(p.id, "uClipOn");
        p.heat = glGetUniformLocation(p.id, "uHeat");
        p.emissive = glGetUniformLocation(p.id, "uEmissive");
        p.xray = glGetUniformLocation(p.id, "uXray");
        p.capColor = glGetUniformLocation(p.id, "uCapColor");
    };
    pbr_.id = link(shaders::kPbrVS, shaders::kPbrFS);
    cap_.id = link(shaders::kCapVS, shaders::kCapFS);
    bg_.id = link(shaders::kBgVS, shaders::kBgFS);
    if (!pbr_.id || !cap_.id || !bg_.id) return false;
    locs(pbr_);
    locs(cap_);
    glGenVertexArrays(1, &bgVao_);
    glGenVertexArrays(1, &capVao_);
    glGenBuffers(1, &capVbo_);
    glBindVertexArray(capVao_);
    glBindBuffer(GL_ARRAY_BUFFER, capVbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(float) * 12, nullptr, GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 12, nullptr);
    glBindVertexArray(0);
    glReady_ = true;
    gpu_.clear();
    if (!scene_.meshes.empty()) uploadScene();
    return true;
}

void Renderer::releaseGL() {
    glReady_ = false;
    gpu_.clear();
    pbr_ = cap_ = bg_ = Prog();
    bgVao_ = capVao_ = capVbo_ = 0;
}

void Renderer::resize(int w, int h) {
    w_ = std::max(1, w);
    h_ = std::max(1, h);
}

void Renderer::freeMeshes() {
    for (auto& g : gpu_) {
        glDeleteVertexArrays(1, &g.vao);
        glDeleteBuffers(1, &g.vbo);
        glDeleteBuffers(1, &g.ibo);
    }
    gpu_.clear();
}

void Renderer::uploadScene() {
    freeMeshes();
    gpu_.resize(scene_.meshes.size());
    for (size_t i = 0; i < scene_.meshes.size(); ++i) {
        const MeshData& m = scene_.meshes[i];
        GpuMesh& g = gpu_[i];
        glGenVertexArrays(1, &g.vao);
        glGenBuffers(1, &g.vbo);
        glGenBuffers(1, &g.ibo);
        glBindVertexArray(g.vao);
        glBindBuffer(GL_ARRAY_BUFFER, g.vbo);
        glBufferData(GL_ARRAY_BUFFER, m.vertices.size() * sizeof(float), m.vertices.data(), GL_STATIC_DRAW);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, g.ibo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, m.indices.size() * sizeof(uint32_t), m.indices.data(), GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 24, nullptr);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 24, reinterpret_cast<void*>(12));
        glBindVertexArray(0);
        g.count = static_cast<GLsizei>(m.indices.size());
        Vec3 a, b;
        m.bounds(a, b);
        g.center = (a + b) * 0.5f;
    }
    sceneDirty_ = false;
}

void Renderer::setEngine(const EngineSpec& spec) {
    spec_ = spec;
    kin_.init(&spec_);
    buildScene(spec_, scene_);
    world_.clear();
    haveLastTheta_ = false;
    outputAngle_ = 0;
    sceneDirty_ = true;
    applyPreset(preset_, true);
}

void Renderer::applyPreset(int preset, bool snap) {
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

void Renderer::nodeShading(size_t i, const EngineSimulation& sim, Vec3& heat, float& heatW, Vec3& emissive) const {
    const SceneNode& n = scene_.nodes[i];
    const Telemetry& tl = sim.telemetry();
    heat = {0, 0, 0};
    heatW = 0;
    emissive = {0, 0, 0};
    const double th = sim.visualThetaDeg();
    const int nch = static_cast<int>(spec_.chambers.size());
    auto gasT = [&](int c) { return (c >= 0 && c < nch) ? sim.chamberTemperature(c, th) : 300.0f; };

    if (n.gearIndex > 0 && n.gearIndex == sim.controls().gear) emissive = {0.03f, 0.10f, 0.22f};

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
        return;
    }
    const int mode = mode_;
    if (mode == static_cast<int>(RenderMode::Thermal)) {
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
    } else if (mode == static_cast<int>(RenderMode::Stress)) {
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
}

void Renderer::drawNode(size_t i, const Mat4& vp, float alpha, bool xray, const Vec3& heat, float heatW, const Vec3& emissive) {
    const SceneNode& n = scene_.nodes[i];
    const GpuMesh& g = gpu_[n.mesh];
    glUniformMatrix4fv(pbr_.model, 1, GL_FALSE, world_[i].m);
    glUniform3f(pbr_.base, n.mat.base.x, n.mat.base.y, n.mat.base.z);
    glUniform1f(pbr_.metal, n.mat.metallic);
    glUniform1f(pbr_.rough, n.mat.roughness);
    glUniform1f(pbr_.coat, n.mat.coat);
    glUniform1f(pbr_.alpha, alpha);
    glUniform1i(pbr_.xray, xray ? 1 : 0);
    glUniform4f(pbr_.heat, heat.x, heat.y, heat.z, heatW);
    glUniform3f(pbr_.emissive, emissive.x, emissive.y, emissive.z);
    glBindVertexArray(g.vao);
    glDrawElements(GL_TRIANGLES, g.count, GL_UNSIGNED_INT, nullptr);
    (void)vp;
}

void Renderer::render(const EngineSimulation& sim, float dt, const ViewInput& in) {
    if (!glReady_) return;
    if (sceneDirty_) uploadScene();

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
    // ギアボックス出力軸 (選択段で入力角を減速, N では停止)
    if (haveLastTheta_) {
        double period = (spec_.family == Family::Wankel ? 1080.0 : 720.0) * 360.0;
        double d = vt - lastVisTheta_;
        if (d < -0.5 * period) d += period;
        if (d > 0.5 * period) d -= period;
        int g = sim.controls().gear;
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

    // 応力表示用の瞬時クランクトルク
    if (mode_ == static_cast<int>(RenderMode::Stress) && spec_.hasCombustion()) {
        double T = 0;
        for (size_t c = 0; c < spec_.chambers.size(); ++c)
            T += (sim.chamberPressure(static_cast<int>(c), vt) - 101325.0) * kin_.chamberDVdTheta(static_cast<int>(c), vt);
        stressTorque_ = clampf(static_cast<float>(std::fabs(T) / (spec_.peakTorqueEstimate * 4.0f)), 0, 1);
    }

    // カメラ
    const Vec3* follow = nullptr;
    Vec3 followPt;
    if (preset_ == static_cast<int>(CameraPreset::ValveFollow) && scene_.followValveNode >= 0) {
        const SceneNode& fn = scene_.nodes[scene_.followValveNode];
        followPt = world_[scene_.followValveNode].transformPoint(gpu_[fn.mesh].center);
        follow = &followPt;
    }
    cam_.update(dt, in.yawRate, in.pitchRate, in.zoom, follow);
    float aspect = static_cast<float>(w_) / h_;
    Mat4 vp = cam_.proj(aspect) * cam_.view();
    Vec3 eye = cam_.eye();

    glViewport(0, 0, w_, h_);
    glClearColor(0.05f, 0.05f, 0.06f, 1.0f);
    glClearStencil(0);
    glDepthMask(GL_TRUE);
    glStencilMask(0xFF);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);

    // 背景
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_STENCIL_TEST);
    glUseProgram(bg_.id);
    glBindVertexArray(bgVao_);
    glDrawArrays(GL_TRIANGLES, 0, 3);

    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);
    glDisable(GL_CULL_FACE);
    glUseProgram(pbr_.id);
    glUniformMatrix4fv(pbr_.viewProj, 1, GL_FALSE, vp.m);
    glUniform3f(pbr_.camPos, eye.x, eye.y, eye.z);

    const RenderMode mode = static_cast<RenderMode>(mode_);
    const bool section = mode == RenderMode::Section;
    const bool housingTransparent = mode == RenderMode::XRay || mode == RenderMode::Thermal || mode == RenderMode::Stress;
    const bool showGas = mode != RenderMode::Solid;

    // 断面平面 (カメラ側を切り取る)
    // sectionAxis 0 = 自動 (往復/ロータリーはクランク軸に直交, タービン/モータは縦断), 1 = その逆
    const bool longitudinal = spec_.family == Family::Turbine || spec_.family == Family::Electric;
    const bool cutZ = (in.sectionAxis != 0) != longitudinal;
    Vec3 pn = cutZ ? Vec3{0, 0, 1} : Vec3{1, 0, 0};
    Vec3 p0 = cutZ ? Vec3{0, 0, scene_.center.z + in.sectionOffset * scene_.radius}
                   : Vec3{scene_.sectionX + in.sectionOffset * scene_.radius, 0, 0};
    if (dot(eye - p0, pn) < 0) pn = -pn;
    float pd = -dot(pn, p0);
    glUniform4f(pbr_.clip, pn.x, pn.y, pn.z, pd);

    // 1) 不透明パス
    for (size_t i = 0; i < scene_.nodes.size(); ++i) {
        const SceneNode& n = scene_.nodes[i];
        if (n.gas) continue;
        if (n.housing && housingTransparent) continue;
        glUniform1i(pbr_.clipOn, (section && n.sectionCut) ? 1 : 0);
        Vec3 h, e;
        float hw;
        nodeShading(i, sim, h, hw, e);
        drawNode(i, vp, 1.0f, false, h, hw, e);
    }

    // 2) 断面キャップ (ステンシルで内外判定: 切断面より奥の面の枚数が奇数なら内部)
    if (section) {
        glEnable(GL_STENCIL_TEST);
        glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);
        glDepthMask(GL_FALSE);
        glDisable(GL_DEPTH_TEST);
        glUniform1i(pbr_.clipOn, 1);
        glStencilFunc(GL_ALWAYS, 0, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_INVERT);
        int k = 0;
        std::vector<int> groupOf(scene_.nodes.size(), -1);
        for (size_t i = 0; i < scene_.nodes.size(); ++i) {
            const SceneNode& n = scene_.nodes[i];
            if (!n.sectionCut || n.gas) continue;
            int g = k++ % 8;
            groupOf[i] = g;
            glStencilMask(1u << g);
            drawNode(i, vp, 1.0f, false, {0, 0, 0}, 0, {0, 0, 0});
        }
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        glDepthMask(GL_TRUE);
        glEnable(GL_DEPTH_TEST);
        glStencilMask(0x00);
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        // 切断面を覆う四角形
        Vec3 u = normalize(cross(pn, std::fabs(pn.y) < 0.9f ? Vec3{0, 1, 0} : Vec3{1, 0, 0}));
        Vec3 v = cross(pn, u);
        float S = scene_.radius * 6.0f;
        Vec3 c = p0 + (scene_.center - p0 - pn * dot(scene_.center - p0, pn));
        Vec3 q[4] = {c - u * S - v * S, c + u * S - v * S, c - u * S + v * S, c + u * S + v * S};
        float verts[12];
        for (int j = 0; j < 4; ++j) { verts[j * 3] = q[j].x; verts[j * 3 + 1] = q[j].y; verts[j * 3 + 2] = q[j].z; }
        glUseProgram(cap_.id);
        glUniformMatrix4fv(cap_.viewProj, 1, GL_FALSE, vp.m);
        glBindVertexArray(capVao_);
        glBindBuffer(GL_ARRAY_BUFFER, capVbo_);
        glBufferSubData(GL_ARRAY_BUFFER, 0, sizeof(verts), verts);
        const Vec3 capCols[8] = {{0.78f, 0.22f, 0.18f}, {0.85f, 0.55f, 0.2f}, {0.7f, 0.7f, 0.72f}, {0.25f, 0.55f, 0.8f},
                                 {0.8f, 0.3f, 0.25f},  {0.6f, 0.62f, 0.66f}, {0.9f, 0.6f, 0.3f},  {0.35f, 0.6f, 0.75f}};
        for (int g = 0; g < 8; ++g) {
            glStencilFunc(GL_EQUAL, 1 << g, 1u << g);
            glUniform3f(cap_.capColor, capCols[g].x, capCols[g].y, capCols[g].z);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        }
        glDisable(GL_STENCIL_TEST);
        glUseProgram(pbr_.id);
    }

    // 3) 半透明パス: 燃焼ガス → 外殻
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDepthMask(GL_FALSE);
    if (showGas) {
        for (size_t i = 0; i < scene_.nodes.size(); ++i) {
            const SceneNode& n = scene_.nodes[i];
            if (!n.gas) continue;
            glUniform1i(pbr_.clipOn, section ? 1 : 0);
            Vec3 h, e;
            float hw;
            nodeShading(i, sim, h, hw, e);
            drawNode(i, vp, section ? 0.75f : 0.55f, false, h, hw, e);
        }
    }
    if (housingTransparent) {
        glUniform1i(pbr_.clipOn, 0);
        for (size_t i = 0; i < scene_.nodes.size(); ++i) {
            const SceneNode& n = scene_.nodes[i];
            if (!n.housing) continue;
            Vec3 h, e;
            float hw;
            nodeShading(i, sim, h, hw, e);
            drawNode(i, vp, mode == RenderMode::XRay ? 0.06f : 0.18f, true, h, hw, e);
        }
    }
    glDepthMask(GL_TRUE);
    glDisable(GL_BLEND);
    glBindVertexArray(0);
}

}  // namespace es
