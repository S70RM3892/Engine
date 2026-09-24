// OpenGL ES 3.0 バックエンド (Vulkan 非対応端末向けフォールバック)。
#pragma once

#include <GLES3/gl3.h>

#include <vector>

#include "FrameData.h"
#include "Scene.h"

namespace es {

class GLBackend {
public:
    bool init();
    void release();  // コンテキスト喪失時: GL 名を捨てる
    void resize(int w, int h) { w_ = w > 0 ? w : 1; h_ = h > 0 ? h : 1; }
    void render(const FrameData& f, const Scene& scene, int sceneVersion);
    bool ready() const { return ready_; }

private:
    struct GpuMesh { GLuint vao = 0, vbo = 0, ibo = 0; GLsizei count = 0; };
    struct Prog {
        GLuint id = 0;
        GLint model = -1, viewProj = -1, camPos = -1, base = -1, metal = -1, rough = -1, coat = -1, alpha = -1;
        GLint clip = -1, clipOn = -1, heat = -1, emissive = -1, xray = -1, capColor = -1, flash = -1;
    };
    void upload(const Scene& scene);
    void freeMeshes();
    void draw(const DrawItem& d);

    bool ready_ = false;
    int w_ = 1, h_ = 1;
    int uploadedVersion_ = -1;
    Prog pbr_, cap_, bg_, part_;
    GLuint bgVao_ = 0, capVao_ = 0, capVbo_ = 0, partVao_ = 0, partVbo_ = 0;
    size_t partCap_ = 0;
    std::vector<GpuMesh> gpu_;
};

}  // namespace es
