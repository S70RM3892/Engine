// ホスト (Mesa llvmpipe) の EGL サーフェスレス + GLES3 で実機と同じレンダラを描画し、
// 各エンジン/モード/プリセットのスクリーンショット (PPM) を出力する。
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

int main(int argc, char** argv) {
    if (argc < 3) { std::printf("usage: render_shots <outdir> <engine_id> [mode preset thetaTime]...\n"); return 1; }
    std::string outDir = argv[1];
    auto getDisplay = reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(eglGetProcAddress("eglGetPlatformDisplayEXT"));
    EGLDisplay dpy = getDisplay ? getDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr) : eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!eglInitialize(dpy, nullptr, nullptr)) { std::printf("eglInitialize failed\n"); return 1; }
    eglBindAPI(EGL_OPENGL_ES_API);
    EGLint cfgAttr[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
    EGLConfig cfg; EGLint n = 0;
    eglChooseConfig(dpy, cfgAttr, &cfg, 1, &n);
    EGLint ctxAttr[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 0, EGL_NONE};
    EGLContext ctx = eglCreateContext(dpy, n ? cfg : EGL_NO_CONFIG_KHR, EGL_NO_CONTEXT, ctxAttr);
    if (ctx == EGL_NO_CONTEXT) { std::printf("context failed 0x%x\n", eglGetError()); return 1; }
    eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx);
    std::printf("GL: %s | %s\n", glGetString(GL_RENDERER), glGetString(GL_VERSION));

    const int W = 960, H = 540;
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
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) { std::printf("fbo incomplete\n"); return 1; }

    std::string id = argv[2];
    EngineSpec spec;
    std::string err;
    if (!loadEngineSpecFromText(readFile(std::string(ASSET_DIR) + "/" + id + ".json"), spec, err)) { std::printf("%s\n", err.c_str()); return 1; }
    AudioFeed feed;
    EngineSimulation sim;
    sim.init(spec, &feed);
    Controls c = sim.controls();
    c.gear = 2;
    c.targetRpm = spec.family == Family::Electric ? 3000 : spec.idleRpm * 1.5f;
    sim.setControls(c);
    for (int i = 0; i < 120; ++i) { sim.step(1 / 60.0); sim.advanceVisual(1 / 60.0); }

    Renderer r;
    if (!r.initGL()) { std::printf("initGL failed\n"); return 1; }
    r.resize(W, H);
    r.setEngine(sim.spec());
    const char* modeNames[] = {"solid", "xray", "section", "thermal", "stress"};
    int serial = 0;
    for (int a = 3; a + 1 < argc; a += 2) {
        int mode = std::atoi(argv[a]);
        int preset = std::atoi(argv[a + 1]);
        ViewInput vi;
        vi.mode = mode;
        vi.preset = preset;
        vi.presetSerial = ++serial;
        // カメラが目標へ収束するまで数フレーム回す (映像はスロー)
        c.timeScale = 0.02f;
        sim.setControls(c);
        for (int f = 0; f < 90; ++f) {
            sim.step(1 / 60.0);
            sim.advanceVisual(1 / 60.0);
            r.render(sim, 1 / 60.0f, vi);
        }
        glFinish();
        std::vector<unsigned char> px(W * H * 4);
        glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
        char name[512];
        std::snprintf(name, sizeof name, "%s/%s_%s_p%d.ppm", outDir.c_str(), id.c_str(), modeNames[mode], preset);
        FILE* f = std::fopen(name, "wb");
        std::fprintf(f, "P6\n%d %d\n255\n", W, H);
        for (int y = H - 1; y >= 0; --y)
            for (int x = 0; x < W; ++x) std::fwrite(&px[(y * W + x) * 4], 1, 3, f);
        std::fclose(f);
        std::printf("wrote %s (glError 0x%x)\n", name, glGetError());
    }
    return 0;
}
