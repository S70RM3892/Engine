// ホスト (Mesa) で実機と同じレンダラを描画し、スクリーンショット (PPM) を出力する。
//   render_shots <outdir> <engine_id> [--vk|--gl] [--fx] [--vehicle] [mode preset]...
//   --vk: Vulkan (lavapipe, 検証レイヤ有効), --gl: OpenGL ES 3 (EGL サーフェスレス)
//   --fx: ゲーム用エフェクト有効 (全開 + 燃料カットを繰り返して炎/火花を出す)
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "render/Renderer.h"
#include "sim/EngineSimulation.h"

using namespace es;

static std::string readFile(const std::string& p) {
    std::ifstream f(p);
    std::stringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

static bool setupGL(int W, int H) {
    auto getDisplay = reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(eglGetProcAddress("eglGetPlatformDisplayEXT"));
    EGLDisplay dpy = getDisplay ? getDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr) : eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!eglInitialize(dpy, nullptr, nullptr)) return false;
    eglBindAPI(EGL_OPENGL_ES_API);
    EGLint cfgAttr[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
    EGLConfig cfg;
    EGLint n = 0;
    eglChooseConfig(dpy, cfgAttr, &cfg, 1, &n);
    EGLint ctxAttr[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 0, EGL_NONE};
    EGLContext ctx = eglCreateContext(dpy, n ? cfg : EGL_NO_CONFIG_KHR, EGL_NO_CONTEXT, ctxAttr);
    if (ctx == EGL_NO_CONTEXT) return false;
    eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx);
    GLuint fbo, rbC, rbD;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glGenRenderbuffers(1, &rbC);
    glBindRenderbuffer(GL_RENDERBUFFER, rbC);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, W, H);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rbC);
    glGenRenderbuffers(1, &rbD);
    glBindRenderbuffer(GL_RENDERBUFFER, rbD);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, W, H);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, rbD);
    return glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
}

int main(int argc, char** argv) {
    if (argc < 3) { std::printf("usage: render_shots <outdir> <engine_id> [--vk|--gl] [--fx] [--vehicle] [mode preset]...\n"); return 1; }
    std::string outDir = argv[1];
    std::string id = argv[2];
    bool vk = true, fx = false, vehicle = false;
    std::vector<std::pair<int, int>> shots;
    for (int a = 3; a < argc; ++a) {
        if (!std::strcmp(argv[a], "--vk")) vk = true;
        else if (!std::strcmp(argv[a], "--gl")) vk = false;
        else if (!std::strcmp(argv[a], "--fx")) fx = true;
        else if (!std::strcmp(argv[a], "--vehicle")) vehicle = true;
        else if (a + 1 < argc) { shots.push_back({std::atoi(argv[a]), std::atoi(argv[a + 1])}); ++a; }
    }
    const int W = 960, H = 540;
    Renderer r;
    if (vk) {
        if (!r.initVulkanHeadless(W, H, true)) { std::printf("vulkan init failed\n"); return 1; }
    } else {
        if (!setupGL(W, H) || !r.initGL()) { std::printf("gl init failed\n"); return 1; }
        r.resize(W, H);
    }
    std::printf("backend: %s\n", r.backendName().c_str());

    EngineSpec spec;
    std::string err;
    std::string json = readFile(std::string(ASSET_DIR) + "/" + id + ".json");
    if (json.empty()) json = readFile(id);  // パス直接指定 (カスタム JSON)
    if (!loadEngineSpecFromText(json, spec, err)) { std::printf("%s\n", err.c_str()); return 1; }
    AudioFeed feed;
    EngineSimulation sim;
    sim.init(spec, &feed);
    Controls c = sim.controls();
    c.gear = 2;
    c.targetRpm = spec.family == Family::Electric ? 3000 : spec.idleRpm * 1.5f;
    if (vehicle) { c.driveMode = 2; c.gear = 1; c.throttleLink = false; c.throttle = 1; }
    sim.setControls(c);
    for (int i = 0; i < 120; ++i) { sim.step(1 / 60.0); sim.advanceVisual(1 / 60.0); }
    r.setEngine(sim.spec());
    r.setEffects(fx ? 1.0f : 0.0f);

    const char* modeNames[] = {"solid", "xray", "section", "thermal", "stress"};
    int serial = 0;
    for (auto [mode, preset] : shots) {
        ViewInput vi;
        vi.mode = mode;
        vi.preset = preset;
        vi.presetSerial = ++serial;
        c.timeScale = fx ? 1.0f : 0.02f;
        for (int f = 0; f < 90; ++f) {
            if (fx) {
                // 全開 → 燃料カット (アフターファイア) を交互に
                bool on = (f / 30) % 2 == 0;
                c.throttleLink = false;
                c.throttle = on ? 1.0f : 0.0f;
                sim.setControls(c);
            } else {
                sim.setControls(c);
            }
            sim.step(1 / 60.0);
            sim.advanceVisual(1 / 60.0);
            r.render(sim, 1 / 60.0f, vi);
        }
        std::vector<uint8_t> px;
        int w = W, h = H;
        bool flip = false;
        if (vk) {
            if (!r.readbackVulkan(px, w, h)) { std::printf("readback failed\n"); return 1; }
        } else {
            glFinish();
            px.resize(W * H * 4);
            glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
            flip = true;
        }
        char name[512];
        std::snprintf(name, sizeof name, "%s/%s_%s_%s_p%d%s.ppm", outDir.c_str(), id.c_str(), vk ? "vk" : "gl", modeNames[mode], preset,
                      fx ? "_fx" : "");
        FILE* f = std::fopen(name, "wb");
        std::fprintf(f, "P6\n%d %d\n255\n", w, h);
        for (int yy = 0; yy < h; ++yy) {
            int y = flip ? h - 1 - yy : yy;
            for (int x = 0; x < w; ++x) std::fwrite(&px[(y * w + x) * 4], 1, 3, f);
        }
        std::fclose(f);
        std::printf("wrote %s\n", name);
    }
    return 0;
}
