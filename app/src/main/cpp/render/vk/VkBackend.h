// Vulkan バックエンド。
//  - Android: ANativeWindow からサーフェス/スワップチェーンを作り FIFO で表示
//  - ホスト (テスト): オフスクリーン画像に描いて読み戻す (Mesa lavapipe で動作確認)
// 4xMSAA + depth/stencil、パイプラインは 背景 / PBR 不透明 / ステンシル偶奇 / 断面キャップ / 半透明 / パーティクル。
#pragma once

#ifdef __ANDROID__
#define VK_USE_PLATFORM_ANDROID_KHR
#endif
#include <vulkan/vulkan.h>

#include <string>
#include <vector>

#include "../FrameData.h"
#include "../Scene.h"

struct ANativeWindow;

namespace es {

class VkBackend {
public:
    ~VkBackend() { release(); }

    // Vulkan が使えそうか (インスタンス作成とグラフィックスキューを持つ GPU の有無)
    static bool probe();

    bool initSurface(ANativeWindow* window);          // Android
    bool initHeadless(int width, int height, bool validation);
    void release();
    void resize(int w, int h);
    bool render(const FrameData& f, const Scene& scene, int sceneVersion);
    bool readback(std::vector<uint8_t>& rgba, int& w, int& h);  // ヘッドレスのみ
    bool ready() const { return device_ != VK_NULL_HANDLE; }
    const std::string& deviceName() const { return deviceName_; }
    int width() const { return static_cast<int>(extent_.width); }
    int height() const { return static_cast<int>(extent_.height); }

private:
    struct Buffer {
        VkBuffer buf = VK_NULL_HANDLE;
        VkDeviceMemory mem = VK_NULL_HANDLE;
        void* map = nullptr;
        VkDeviceSize size = 0;
    };
    struct Image {
        VkImage img = VK_NULL_HANDLE;
        VkDeviceMemory mem = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
    };
    struct MeshRange { uint32_t firstIndex = 0, indexCount = 0; int32_t vertexOffset = 0; };
    struct Frame {
        VkCommandBuffer cmd = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        VkSemaphore imageAvailable = VK_NULL_HANDLE;
        Buffer dyn;  // UBO + キャップ四角形 + パーティクル頂点
        VkDescriptorSet set = VK_NULL_HANDLE;
    };

    bool createInstance(bool surface, bool validation);
    bool pickDevice();
    bool createDevice(bool swapchain);
    bool createCommon();
    bool createSwapchain();
    void destroySwapchain();
    bool createTargets(VkFormat finalFormat, const std::vector<VkImageView>& finalViews);
    void destroyTargets();
    bool createRenderPass(VkFormat finalFormat, VkImageLayout finalLayout);
    bool createPipelines();
    void destroyPipelines();
    bool createBuffer(VkDeviceSize size, VkBufferUsageFlags usage, Buffer& out);
    void destroyBuffer(Buffer& b);
    bool createImage(uint32_t w, uint32_t h, VkFormat fmt, VkImageUsageFlags usage, VkSampleCountFlagBits samples,
                     VkImageAspectFlags aspect, Image& out);
    void destroyImage(Image& img);
    uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags props) const;
    bool uploadScene(const Scene& scene);
    void record(Frame& fr, uint32_t imageIndex, const FrameData& f);
    VkShaderModule shader(const uint32_t* code, size_t bytes);

    VkInstance instance_ = VK_NULL_HANDLE;
    VkDebugUtilsMessengerEXT messenger_ = VK_NULL_HANDLE;
    VkPhysicalDevice phys_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = 0;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    std::vector<VkImage> swapImages_;
    std::vector<VkImageView> swapViews_;
    std::vector<VkSemaphore> renderDone_;  // スワップチェーン画像ごと
    VkFormat colorFormat_ = VK_FORMAT_R8G8B8A8_SRGB;
    VkFormat depthFormat_ = VK_FORMAT_D24_UNORM_S8_UINT;
    bool manualGamma_ = false;
    VkSampleCountFlagBits samples_ = VK_SAMPLE_COUNT_1_BIT;
    VkExtent2D extent_{1, 1};
    bool swapDirty_ = false;
    int reqW_ = 0, reqH_ = 0;
    bool headless_ = false;
    Image offscreen_;       // ヘッドレス時の最終画像
    Image msaaColor_, depth_;
    std::vector<VkFramebuffer> framebuffers_;
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout setLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipeLayout_ = VK_NULL_HANDLE;
    VkPipeline pBg_ = VK_NULL_HANDLE, pOpaque_ = VK_NULL_HANDLE, pStencil_ = VK_NULL_HANDLE, pCap_ = VK_NULL_HANDLE,
               pTransparent_ = VK_NULL_HANDLE, pParticle_ = VK_NULL_HANDLE;
    VkDescriptorPool descPool_ = VK_NULL_HANDLE;
    VkCommandPool cmdPool_ = VK_NULL_HANDLE;
    static constexpr int kFrames = 2;
    Frame frames_[kFrames];
    int frameIndex_ = 0;
    Buffer vbo_, ibo_;
    std::vector<MeshRange> meshes_;
    int uploadedVersion_ = -1;
    std::string deviceName_;
    VkPhysicalDeviceMemoryProperties memProps_{};
    static constexpr VkDeviceSize kDynBytes = 4u << 20;  // 4MB (パーティクル ~19000 頂点)
};

}  // namespace es
