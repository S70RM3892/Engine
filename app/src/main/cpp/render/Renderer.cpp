#include "Renderer.h"

namespace es {

bool Renderer::initGL() {
    gl_.release();
    if (!gl_.init()) return false;
    gl_.resize(w_, h_);
    api_ = Api::GLES;
    return true;
}

void Renderer::releaseGL() {
    gl_.release();
    if (api_ == Api::GLES) api_ = Api::None;
}

bool Renderer::initVulkan(ANativeWindow* window) {
    if (!vk_.initSurface(window)) return false;
    api_ = Api::Vulkan;
    w_ = vk_.width();
    h_ = vk_.height();
    return true;
}

void Renderer::releaseVulkan() {
    vk_.release();
    if (api_ == Api::Vulkan) api_ = Api::None;
}

bool Renderer::initVulkanHeadless(int w, int h, bool validation) {
    if (!vk_.initHeadless(w, h, validation)) return false;
    api_ = Api::Vulkan;
    w_ = w;
    h_ = h;
    return true;
}

void Renderer::resize(int w, int h) {
    w_ = w > 0 ? w : 1;
    h_ = h > 0 ? h : 1;
    gl_.resize(w_, h_);
    vk_.resize(w_, h_);
}

void Renderer::render(const EngineSimulation& sim, float dt, const ViewInput& in) {
    if (api_ == Api::None) return;
    int w = api_ == Api::Vulkan ? vk_.width() : w_;
    int h = api_ == Api::Vulkan ? vk_.height() : h_;
    front_.build(sim, dt, in, static_cast<float>(w) / std::max(1, h), frame_);
    if (api_ == Api::Vulkan) vk_.render(frame_, front_.scene(), front_.sceneVersion());
    else gl_.render(frame_, front_.scene(), front_.sceneVersion());
}

std::string Renderer::backendName() const {
    switch (api_) {
        case Api::Vulkan: return "Vulkan (" + vk_.deviceName() + ")";
        case Api::GLES: return "OpenGL ES 3.0";
        default: return "none";
    }
}

}  // namespace es
