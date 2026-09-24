// 描画のファサード: フロントエンド (API 非依存) + バックエンド (Vulkan 優先, 非対応端末は GLES 3.0)。
#pragma once

#include <memory>
#include <string>

#include "../sim/EngineSimulation.h"
#include "FrameData.h"
#include "GLBackend.h"
#include "RenderFrontend.h"
#include "vk/VkBackend.h"

struct ANativeWindow;

namespace es {

class Renderer {
public:
    enum class Api { None, Vulkan, GLES };

    // GLES (GLSurfaceView の GL スレッドから)
    bool initGL();
    void releaseGL();
    // Vulkan (SurfaceView の描画スレッドから)
    bool initVulkan(ANativeWindow* window);
    void releaseVulkan();
    // ホストテスト用
    bool initVulkanHeadless(int w, int h, bool validation);
    bool readbackVulkan(std::vector<uint8_t>& rgba, int& w, int& h) { return vk_.readback(rgba, w, h); }

    void resize(int w, int h);
    void setEngine(const EngineSpec& spec) { front_.setEngine(spec); }
    void setEffects(float level) { front_.setEffects(level); }
    void render(const EngineSimulation& sim, float dt, const ViewInput& in);

    float cameraYawDeg() const { return front_.cameraYawDeg(); }
    int effectiveMode() const { return front_.effectiveMode(); }
    Api api() const { return api_; }
    std::string backendName() const;

private:
    RenderFrontend front_;
    FrameData frame_;
    GLBackend gl_;
    VkBackend vk_;
    Api api_ = Api::None;
    int w_ = 1, h_ = 1;
};

}  // namespace es
