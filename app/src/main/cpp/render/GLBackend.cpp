#include "GLBackend.h"

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

}  // namespace

bool GLBackend::init() {
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
        p.flash = glGetUniformLocation(p.id, "uFlash");
    };
    pbr_.id = link(shaders::kPbrVS, shaders::kPbrFS);
    cap_.id = link(shaders::kCapVS, shaders::kCapFS);
    bg_.id = link(shaders::kBgVS, shaders::kBgFS);
    part_.id = link(shaders::kParticleVS, shaders::kParticleFS);
    if (!pbr_.id || !cap_.id || !bg_.id || !part_.id) return false;
    locs(pbr_);
    locs(cap_);
    locs(bg_);
    locs(part_);
    glGenVertexArrays(1, &bgVao_);
    glGenVertexArrays(1, &capVao_);
    glGenBuffers(1, &capVbo_);
    glBindVertexArray(capVao_);
    glBindBuffer(GL_ARRAY_BUFFER, capVbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(float) * 12, nullptr, GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 12, nullptr);
    glGenVertexArrays(1, &partVao_);
    glGenBuffers(1, &partVbo_);
    glBindVertexArray(partVao_);
    glBindBuffer(GL_ARRAY_BUFFER, partVbo_);
    partCap_ = 6 * 512;
    glBufferData(GL_ARRAY_BUFFER, partCap_ * sizeof(ParticleVertex), nullptr, GL_STREAM_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(ParticleVertex), nullptr);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, sizeof(ParticleVertex), reinterpret_cast<void*>(12));
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 4, GL_FLOAT, GL_FALSE, sizeof(ParticleVertex), reinterpret_cast<void*>(20));
    glBindVertexArray(0);
    gpu_.clear();
    uploadedVersion_ = -1;
    ready_ = true;
    return true;
}

void GLBackend::release() {
    ready_ = false;
    gpu_.clear();
    pbr_ = cap_ = bg_ = part_ = Prog();
    bgVao_ = capVao_ = capVbo_ = partVao_ = partVbo_ = 0;
    uploadedVersion_ = -1;
}

void GLBackend::freeMeshes() {
    for (auto& g : gpu_) {
        glDeleteVertexArrays(1, &g.vao);
        glDeleteBuffers(1, &g.vbo);
        glDeleteBuffers(1, &g.ibo);
    }
    gpu_.clear();
}

void GLBackend::upload(const Scene& scene) {
    freeMeshes();
    gpu_.resize(scene.meshes.size());
    for (size_t i = 0; i < scene.meshes.size(); ++i) {
        const MeshData& m = scene.meshes[i];
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
    }
}

void GLBackend::draw(const DrawItem& d) {
    if (d.mesh < 0 || d.mesh >= static_cast<int>(gpu_.size())) return;
    float m[16];
    for (int c = 0; c < 4; ++c) {
        for (int r = 0; r < 3; ++r) m[c * 4 + r] = d.model[r * 4 + c];
        m[c * 4 + 3] = c == 3 ? 1.0f : 0.0f;
    }
    uint32_t flags = static_cast<uint32_t>(d.mat[3]);
    glUniformMatrix4fv(pbr_.model, 1, GL_FALSE, m);
    glUniform3f(pbr_.base, d.base[0], d.base[1], d.base[2]);
    glUniform1f(pbr_.metal, d.base[3]);
    glUniform1f(pbr_.rough, d.mat[0]);
    glUniform1f(pbr_.coat, d.mat[1]);
    glUniform1f(pbr_.alpha, d.mat[2]);
    glUniform1i(pbr_.clipOn, (flags & kFlagClip) ? 1 : 0);
    glUniform1i(pbr_.xray, (flags & kFlagXray) ? 1 : 0);
    glUniform4f(pbr_.heat, d.heat[0], d.heat[1], d.heat[2], d.heat[3]);
    glUniform3f(pbr_.emissive, d.emissive[0], d.emissive[1], d.emissive[2]);
    const GpuMesh& g = gpu_[d.mesh];
    glBindVertexArray(g.vao);
    glDrawElements(GL_TRIANGLES, g.count, GL_UNSIGNED_INT, nullptr);
}

void GLBackend::render(const FrameData& f, const Scene& scene, int sceneVersion) {
    if (!ready_) return;
    if (sceneVersion != uploadedVersion_) {
        upload(scene);
        uploadedVersion_ = sceneVersion;
    }
    glViewport(0, 0, w_, h_);
    glClearColor(0.05f, 0.05f, 0.06f, 1.0f);
    glClearStencil(0);
    glDepthMask(GL_TRUE);
    glStencilMask(0xFF);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);

    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_CULL_FACE);
    glUseProgram(bg_.id);
    glUniform4fv(bg_.flash, 1, f.flash);
    glBindVertexArray(bgVao_);
    glDrawArrays(GL_TRIANGLES, 0, 3);

    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);
    glUseProgram(pbr_.id);
    glUniformMatrix4fv(pbr_.viewProj, 1, GL_FALSE, f.viewProj.m);
    glUniform3f(pbr_.camPos, f.camPos.x, f.camPos.y, f.camPos.z);
    glUniform4fv(pbr_.clip, 1, f.clip);
    glUniform4fv(pbr_.flash, 1, f.flash);
    for (const DrawItem& d : f.opaque) draw(d);

    if (f.section && !f.stencil.empty()) {
        // 断面キャップ: 切断面より奥の面の枚数の偶奇をステンシルの各ビットに数える
        glEnable(GL_STENCIL_TEST);
        glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);
        glDepthMask(GL_FALSE);
        glDisable(GL_DEPTH_TEST);
        glStencilFunc(GL_ALWAYS, 0, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_INVERT);
        for (size_t i = 0; i < f.stencil.size(); ++i) {
            glStencilMask(1u << f.stencilGroup[i]);
            draw(f.stencil[i]);
        }
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        glDepthMask(GL_TRUE);
        glEnable(GL_DEPTH_TEST);
        glStencilMask(0x00);
        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        glUseProgram(cap_.id);
        glUniformMatrix4fv(cap_.viewProj, 1, GL_FALSE, f.viewProj.m);
        glBindVertexArray(capVao_);
        glBindBuffer(GL_ARRAY_BUFFER, capVbo_);
        glBufferSubData(GL_ARRAY_BUFFER, 0, sizeof(f.capQuad), f.capQuad);
        for (int g = 0; g < 8; ++g) {
            glStencilFunc(GL_EQUAL, 1 << g, 1u << g);
            glUniform3f(cap_.capColor, f.capColor[g].x, f.capColor[g].y, f.capColor[g].z);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        }
        glDisable(GL_STENCIL_TEST);
        glUseProgram(pbr_.id);
    }

    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDepthMask(GL_FALSE);
    for (const DrawItem& d : f.transparent) draw(d);

    if (!f.particles.empty()) {
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(part_.id);
        glUniformMatrix4fv(part_.viewProj, 1, GL_FALSE, f.viewProj.m);
        glBindVertexArray(partVao_);
        glBindBuffer(GL_ARRAY_BUFFER, partVbo_);
        if (f.particles.size() > partCap_) {
            partCap_ = f.particles.size() * 2;
            glBufferData(GL_ARRAY_BUFFER, partCap_ * sizeof(ParticleVertex), nullptr, GL_STREAM_DRAW);
        }
        glBufferSubData(GL_ARRAY_BUFFER, 0, f.particles.size() * sizeof(ParticleVertex), f.particles.data());
        glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(f.particles.size()));
    }
    glDepthMask(GL_TRUE);
    glDisable(GL_BLEND);
    glBindVertexArray(0);
}

}  // namespace es
