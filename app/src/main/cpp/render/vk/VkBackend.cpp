#include "VkBackend.h"

#include <algorithm>
#include <cstdio>
#include <cstring>

#ifdef __ANDROID__
#include <android/log.h>
#include <android/native_window.h>
#define VLOG(...) __android_log_print(ANDROID_LOG_INFO, "EngineSimVk", __VA_ARGS__)
#else
#define VLOG(...) (std::fprintf(stderr, __VA_ARGS__), std::fprintf(stderr, "\n"))
#endif

namespace es {

namespace {

// glslc -mfmt=c で生成した SPIR-V (CMake がビルド時にコンパイル)
const uint32_t kPbrVert[] =
#include "pbr.vert.inc"
    ;
const uint32_t kPbrFrag[] =
#include "pbr.frag.inc"
    ;
const uint32_t kCapVert[] =
#include "cap.vert.inc"
    ;
const uint32_t kCapFrag[] =
#include "cap.frag.inc"
    ;
const uint32_t kBgVert[] =
#include "bg.vert.inc"
    ;
const uint32_t kBgFrag[] =
#include "bg.frag.inc"
    ;
const uint32_t kPartVert[] =
#include "particle.vert.inc"
    ;
const uint32_t kPartFrag[] =
#include "particle.frag.inc"
    ;

#define VKC(x)                                                     \
    do {                                                           \
        VkResult r_ = (x);                                         \
        if (r_ != VK_SUCCESS) {                                    \
            VLOG("Vulkan error %d at %s:%d", r_, __FILE__, __LINE__); \
            return false;                                          \
        }                                                          \
    } while (0)

struct FrameUbo {
    float viewProj[16];
    float camPos[4];
    float clip[4];
    float flash[4];
    float misc[4];
};

constexpr VkDeviceSize kUboOffset = 0;
constexpr VkDeviceSize kCapOffset = 256;
constexpr VkDeviceSize kPartOffset = 512;

VKAPI_ATTR VkBool32 VKAPI_CALL debugCb(VkDebugUtilsMessageSeverityFlagBitsEXT sev, VkDebugUtilsMessageTypeFlagsEXT,
                                       const VkDebugUtilsMessengerCallbackDataEXT* data, void*) {
    if (sev >= VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) VLOG("[validation] %s", data->pMessage);
    return VK_FALSE;
}

}  // namespace

bool VkBackend::probe() {
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &app;
    VkInstance inst = VK_NULL_HANDLE;
    if (vkCreateInstance(&ci, nullptr, &inst) != VK_SUCCESS) return false;
    uint32_t n = 0;
    vkEnumeratePhysicalDevices(inst, &n, nullptr);
    bool ok = false;
    std::vector<VkPhysicalDevice> devs(n);
    if (n) vkEnumeratePhysicalDevices(inst, &n, devs.data());
    for (VkPhysicalDevice d : devs) {
        uint32_t qn = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(d, &qn, nullptr);
        std::vector<VkQueueFamilyProperties> q(qn);
        vkGetPhysicalDeviceQueueFamilyProperties(d, &qn, q.data());
        for (auto& f : q)
            if (f.queueFlags & VK_QUEUE_GRAPHICS_BIT) ok = true;
    }
    vkDestroyInstance(inst, nullptr);
    return ok;
}

bool VkBackend::createInstance(bool surface, bool validation) {
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "EngineSim";
    app.apiVersion = VK_API_VERSION_1_0;
    std::vector<const char*> ext;
    std::vector<const char*> layers;
    if (surface) {
        ext.push_back(VK_KHR_SURFACE_EXTENSION_NAME);
#ifdef __ANDROID__
        ext.push_back(VK_KHR_ANDROID_SURFACE_EXTENSION_NAME);
#endif
    }
    if (validation) {
        uint32_t n = 0;
        vkEnumerateInstanceLayerProperties(&n, nullptr);
        std::vector<VkLayerProperties> lp(n);
        vkEnumerateInstanceLayerProperties(&n, lp.data());
        for (auto& l : lp)
            if (std::strcmp(l.layerName, "VK_LAYER_KHRONOS_validation") == 0) {
                layers.push_back("VK_LAYER_KHRONOS_validation");
                ext.push_back(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            }
    }
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &app;
    ci.enabledExtensionCount = static_cast<uint32_t>(ext.size());
    ci.ppEnabledExtensionNames = ext.data();
    ci.enabledLayerCount = static_cast<uint32_t>(layers.size());
    ci.ppEnabledLayerNames = layers.data();
    VKC(vkCreateInstance(&ci, nullptr, &instance_));
    if (!layers.empty()) {
        auto create = reinterpret_cast<PFN_vkCreateDebugUtilsMessengerEXT>(vkGetInstanceProcAddr(instance_, "vkCreateDebugUtilsMessengerEXT"));
        if (create) {
            VkDebugUtilsMessengerCreateInfoEXT mi{VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT};
            mi.messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
            mi.messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                             VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
            mi.pfnUserCallback = debugCb;
            create(instance_, &mi, nullptr, &messenger_);
        }
    }
    return true;
}

bool VkBackend::pickDevice() {
    uint32_t n = 0;
    vkEnumeratePhysicalDevices(instance_, &n, nullptr);
    if (!n) return false;
    std::vector<VkPhysicalDevice> devs(n);
    vkEnumeratePhysicalDevices(instance_, &n, devs.data());
    int bestScore = -1;
    for (VkPhysicalDevice d : devs) {
        uint32_t qn = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(d, &qn, nullptr);
        std::vector<VkQueueFamilyProperties> q(qn);
        vkGetPhysicalDeviceQueueFamilyProperties(d, &qn, q.data());
        for (uint32_t i = 0; i < qn; ++i) {
            if (!(q[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)) continue;
            if (surface_) {
                VkBool32 present = VK_FALSE;
                vkGetPhysicalDeviceSurfaceSupportKHR(d, i, surface_, &present);
                if (!present) continue;
            }
            VkPhysicalDeviceProperties p;
            vkGetPhysicalDeviceProperties(d, &p);
            int score = p.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 3 : p.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU ? 2 : 1;
            if (score > bestScore) {
                bestScore = score;
                phys_ = d;
                queueFamily_ = i;
                deviceName_ = p.deviceName;
            }
            break;
        }
    }
    if (!phys_) return false;
    vkGetPhysicalDeviceMemoryProperties(phys_, &memProps_);
    VkPhysicalDeviceProperties p;
    vkGetPhysicalDeviceProperties(phys_, &p);
    VkSampleCountFlags counts = p.limits.framebufferColorSampleCounts & p.limits.framebufferDepthSampleCounts &
                                p.limits.framebufferStencilSampleCounts;
    samples_ = (counts & VK_SAMPLE_COUNT_4_BIT) ? VK_SAMPLE_COUNT_4_BIT : VK_SAMPLE_COUNT_1_BIT;
    const VkFormat depthCands[] = {VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D16_UNORM_S8_UINT};
    depthFormat_ = VK_FORMAT_UNDEFINED;
    for (VkFormat f : depthCands) {
        VkFormatProperties fp;
        vkGetPhysicalDeviceFormatProperties(phys_, f, &fp);
        if (fp.optimalTilingFeatures & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) { depthFormat_ = f; break; }
    }
    if (depthFormat_ == VK_FORMAT_UNDEFINED) return false;
    VLOG("Vulkan device: %s (samples %d)", deviceName_.c_str(), static_cast<int>(samples_));
    return true;
}

bool VkBackend::createDevice(bool swapchain) {
    float prio = 1.0f;
    VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qi.queueFamilyIndex = queueFamily_;
    qi.queueCount = 1;
    qi.pQueuePriorities = &prio;
    const char* ext[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkDeviceCreateInfo ci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    ci.queueCreateInfoCount = 1;
    ci.pQueueCreateInfos = &qi;
    ci.enabledExtensionCount = swapchain ? 1 : 0;
    ci.ppEnabledExtensionNames = ext;
    VKC(vkCreateDevice(phys_, &ci, nullptr, &device_));
    vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);
    return true;
}

uint32_t VkBackend::memoryType(uint32_t bits, VkMemoryPropertyFlags props) const {
    for (uint32_t i = 0; i < memProps_.memoryTypeCount; ++i)
        if ((bits & (1u << i)) && (memProps_.memoryTypes[i].propertyFlags & props) == props) return i;
    return UINT32_MAX;
}

bool VkBackend::createBuffer(VkDeviceSize size, VkBufferUsageFlags usage, Buffer& out) {
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bi.size = size;
    bi.usage = usage;
    bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VKC(vkCreateBuffer(device_, &bi, nullptr, &out.buf));
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(device_, out.buf, &req);
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = req.size;
    // モバイルは統合メモリなので HOST_VISIBLE で直接書き込む (ステージング不要)
    ai.memoryTypeIndex = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (ai.memoryTypeIndex == UINT32_MAX) return false;
    VKC(vkAllocateMemory(device_, &ai, nullptr, &out.mem));
    VKC(vkBindBufferMemory(device_, out.buf, out.mem, 0));
    VKC(vkMapMemory(device_, out.mem, 0, VK_WHOLE_SIZE, 0, &out.map));
    out.size = size;
    return true;
}

void VkBackend::destroyBuffer(Buffer& b) {
    if (b.mem) vkFreeMemory(device_, b.mem, nullptr);
    if (b.buf) vkDestroyBuffer(device_, b.buf, nullptr);
    b = Buffer();
}

bool VkBackend::createImage(uint32_t w, uint32_t h, VkFormat fmt, VkImageUsageFlags usage, VkSampleCountFlagBits samples,
                            VkImageAspectFlags aspect, Image& out) {
    VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ii.imageType = VK_IMAGE_TYPE_2D;
    ii.format = fmt;
    ii.extent = {w, h, 1};
    ii.mipLevels = 1;
    ii.arrayLayers = 1;
    ii.samples = samples;
    ii.tiling = VK_IMAGE_TILING_OPTIMAL;
    ii.usage = usage;
    ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VKC(vkCreateImage(device_, &ii, nullptr, &out.img));
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(device_, out.img, &req);
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = req.size;
    // MSAA/深度は一時アタッチメント (タイルメモリ上だけで完結できる LAZILY_ALLOCATED を優先)
    ai.memoryTypeIndex = UINT32_MAX;
    if (usage & VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT)
        ai.memoryTypeIndex = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT);
    if (ai.memoryTypeIndex == UINT32_MAX) ai.memoryTypeIndex = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (ai.memoryTypeIndex == UINT32_MAX) ai.memoryTypeIndex = memoryType(req.memoryTypeBits, 0);
    VKC(vkAllocateMemory(device_, &ai, nullptr, &out.mem));
    VKC(vkBindImageMemory(device_, out.img, out.mem, 0));
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = out.img;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = fmt;
    vi.subresourceRange = {aspect, 0, 1, 0, 1};
    VKC(vkCreateImageView(device_, &vi, nullptr, &out.view));
    return true;
}

void VkBackend::destroyImage(Image& img) {
    if (img.view) vkDestroyImageView(device_, img.view, nullptr);
    if (img.img) vkDestroyImage(device_, img.img, nullptr);
    if (img.mem) vkFreeMemory(device_, img.mem, nullptr);
    img = Image();
}

VkShaderModule VkBackend::shader(const uint32_t* code, size_t bytes) {
    VkShaderModuleCreateInfo ci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    ci.codeSize = bytes;
    ci.pCode = code;
    VkShaderModule m = VK_NULL_HANDLE;
    vkCreateShaderModule(device_, &ci, nullptr, &m);
    return m;
}

bool VkBackend::createRenderPass(VkFormat finalFormat, VkImageLayout finalLayout) {
    const bool msaa = samples_ != VK_SAMPLE_COUNT_1_BIT;
    VkAttachmentDescription att[3]{};
    // 0: カラー (MSAA 時は一時, それ以外は最終画像)
    att[0].format = finalFormat;
    att[0].samples = samples_;
    att[0].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    att[0].storeOp = msaa ? VK_ATTACHMENT_STORE_OP_DONT_CARE : VK_ATTACHMENT_STORE_OP_STORE;
    att[0].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att[0].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att[0].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    att[0].finalLayout = msaa ? VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL : finalLayout;
    // 1: 深度/ステンシル
    att[1].format = depthFormat_;
    att[1].samples = samples_;
    att[1].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    att[1].storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att[1].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    att[1].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att[1].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    att[1].finalLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
    // 2: MSAA 解決先
    att[2].format = finalFormat;
    att[2].samples = VK_SAMPLE_COUNT_1_BIT;
    att[2].loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att[2].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    att[2].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att[2].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att[2].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    att[2].finalLayout = finalLayout;

    VkAttachmentReference colorRef{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkAttachmentReference depthRef{1, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL};
    VkAttachmentReference resolveRef{2, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sp{};
    sp.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sp.colorAttachmentCount = 1;
    sp.pColorAttachments = &colorRef;
    sp.pDepthStencilAttachment = &depthRef;
    sp.pResolveAttachments = msaa ? &resolveRef : nullptr;
    VkSubpassDependency dep{};
    dep.srcSubpass = VK_SUBPASS_EXTERNAL;
    dep.dstSubpass = 0;
    dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
    dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
    dep.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
    dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT |
                        VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT;
    VkRenderPassCreateInfo ci{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    ci.attachmentCount = msaa ? 3 : 2;
    ci.pAttachments = att;
    ci.subpassCount = 1;
    ci.pSubpasses = &sp;
    ci.dependencyCount = 1;
    ci.pDependencies = &dep;
    VKC(vkCreateRenderPass(device_, &ci, nullptr, &renderPass_));
    return true;
}

bool VkBackend::createTargets(VkFormat finalFormat, const std::vector<VkImageView>& finalViews) {
    const bool msaa = samples_ != VK_SAMPLE_COUNT_1_BIT;
    if (msaa && !createImage(extent_.width, extent_.height, finalFormat,
                             VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT, samples_,
                             VK_IMAGE_ASPECT_COLOR_BIT, msaaColor_))
        return false;
    if (!createImage(extent_.width, extent_.height, depthFormat_,
                     VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT, samples_,
                     VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT, depth_))
        return false;
    framebuffers_.resize(finalViews.size());
    for (size_t i = 0; i < finalViews.size(); ++i) {
        VkImageView views[3] = {msaa ? msaaColor_.view : finalViews[i], depth_.view, finalViews[i]};
        VkFramebufferCreateInfo fi{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        fi.renderPass = renderPass_;
        fi.attachmentCount = msaa ? 3 : 2;
        fi.pAttachments = views;
        fi.width = extent_.width;
        fi.height = extent_.height;
        fi.layers = 1;
        VKC(vkCreateFramebuffer(device_, &fi, nullptr, &framebuffers_[i]));
    }
    return true;
}

void VkBackend::destroyTargets() {
    for (VkFramebuffer fb : framebuffers_) vkDestroyFramebuffer(device_, fb, nullptr);
    framebuffers_.clear();
    destroyImage(msaaColor_);
    destroyImage(depth_);
}

bool VkBackend::createPipelines() {
    VkShaderModule pbrV = shader(kPbrVert, sizeof(kPbrVert)), pbrF = shader(kPbrFrag, sizeof(kPbrFrag));
    VkShaderModule capV = shader(kCapVert, sizeof(kCapVert)), capF = shader(kCapFrag, sizeof(kCapFrag));
    VkShaderModule bgV = shader(kBgVert, sizeof(kBgVert)), bgF = shader(kBgFrag, sizeof(kBgFrag));
    VkShaderModule ptV = shader(kPartVert, sizeof(kPartVert)), ptF = shader(kPartFrag, sizeof(kPartFrag));

    enum Kind { Bg, Opaque, Stencil, Cap, Transparent, Particle };
    auto make = [&](Kind k, VkPipeline& out) -> bool {
        VkShaderModule vs = k == Bg ? bgV : k == Cap ? capV : k == Particle ? ptV : pbrV;
        VkShaderModule fs = k == Bg ? bgF : k == Cap ? capF : k == Particle ? ptF : pbrF;
        VkPipelineShaderStageCreateInfo st[2]{};
        st[0].sType = st[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        st[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        st[0].module = vs;
        st[0].pName = "main";
        st[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        st[1].module = fs;
        st[1].pName = "main";

        VkVertexInputBindingDescription bind{};
        VkVertexInputAttributeDescription attrs[3]{};
        VkPipelineVertexInputStateCreateInfo vi{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        if (k == Opaque || k == Stencil || k == Transparent) {
            bind = {0, 24, VK_VERTEX_INPUT_RATE_VERTEX};
            attrs[0] = {0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0};
            attrs[1] = {1, 0, VK_FORMAT_R32G32B32_SFLOAT, 12};
            vi.vertexBindingDescriptionCount = 1;
            vi.vertexAttributeDescriptionCount = 2;
        } else if (k == Cap) {
            bind = {0, 12, VK_VERTEX_INPUT_RATE_VERTEX};
            attrs[0] = {0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0};
            vi.vertexBindingDescriptionCount = 1;
            vi.vertexAttributeDescriptionCount = 1;
        } else if (k == Particle) {
            bind = {0, sizeof(ParticleVertex), VK_VERTEX_INPUT_RATE_VERTEX};
            attrs[0] = {0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0};
            attrs[1] = {1, 0, VK_FORMAT_R32G32_SFLOAT, 12};
            attrs[2] = {2, 0, VK_FORMAT_R32G32B32A32_SFLOAT, 20};
            vi.vertexBindingDescriptionCount = 1;
            vi.vertexAttributeDescriptionCount = 3;
        }
        vi.pVertexBindingDescriptions = &bind;
        vi.pVertexAttributeDescriptions = attrs;

        VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
        ia.topology = k == Cap ? VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP : VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
        vp.viewportCount = 1;
        vp.scissorCount = 1;
        VkPipelineRasterizationStateCreateInfo rs{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
        rs.polygonMode = VK_POLYGON_MODE_FILL;
        rs.cullMode = VK_CULL_MODE_NONE;
        // 投影で Y を反転しているので巻き方向も反転 (gl_FrontFacing を GL と一致させる)
        rs.frontFace = VK_FRONT_FACE_CLOCKWISE;
        rs.lineWidth = 1.0f;
        VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
        ms.rasterizationSamples = samples_;
        VkPipelineDepthStencilStateCreateInfo ds{VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO};
        ds.depthTestEnable = (k == Bg || k == Stencil) ? VK_FALSE : VK_TRUE;
        ds.depthWriteEnable = (k == Opaque || k == Cap) ? VK_TRUE : VK_FALSE;
        ds.depthCompareOp = VK_COMPARE_OP_LESS_OR_EQUAL;
        if (k == Stencil) {
            // 偶奇カウント: 描画のたびに (動的な書込みマスクのビットを) 反転
            ds.stencilTestEnable = VK_TRUE;
            VkStencilOpState s{VK_STENCIL_OP_KEEP, VK_STENCIL_OP_INVERT, VK_STENCIL_OP_KEEP, VK_COMPARE_OP_ALWAYS, 0xFF, 0x01, 0};
            ds.front = ds.back = s;
        } else if (k == Cap) {
            ds.stencilTestEnable = VK_TRUE;
            VkStencilOpState s{VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, VK_STENCIL_OP_KEEP, VK_COMPARE_OP_EQUAL, 0x01, 0, 0x01};
            ds.front = ds.back = s;
        }
        VkPipelineColorBlendAttachmentState cb{};
        cb.colorWriteMask = k == Stencil ? 0 : (VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
        if (k == Transparent) {
            cb.blendEnable = VK_TRUE;
            cb.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
            cb.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            cb.colorBlendOp = VK_BLEND_OP_ADD;
            cb.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
            cb.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            cb.alphaBlendOp = VK_BLEND_OP_ADD;
        } else if (k == Particle) {
            cb.blendEnable = VK_TRUE;
            cb.srcColorBlendFactor = VK_BLEND_FACTOR_ONE;
            cb.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            cb.colorBlendOp = VK_BLEND_OP_ADD;
            cb.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
            cb.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            cb.alphaBlendOp = VK_BLEND_OP_ADD;
        }
        VkPipelineColorBlendStateCreateInfo cbs{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
        cbs.attachmentCount = 1;
        cbs.pAttachments = &cb;
        VkDynamicState dyn[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_STENCIL_COMPARE_MASK,
                                VK_DYNAMIC_STATE_STENCIL_WRITE_MASK, VK_DYNAMIC_STATE_STENCIL_REFERENCE};
        VkPipelineDynamicStateCreateInfo dsi{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
        dsi.dynamicStateCount = (k == Stencil || k == Cap) ? 5 : 2;
        dsi.pDynamicStates = dyn;
        VkGraphicsPipelineCreateInfo pi{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
        pi.stageCount = 2;
        pi.pStages = st;
        pi.pVertexInputState = &vi;
        pi.pInputAssemblyState = &ia;
        pi.pViewportState = &vp;
        pi.pRasterizationState = &rs;
        pi.pMultisampleState = &ms;
        pi.pDepthStencilState = &ds;
        pi.pColorBlendState = &cbs;
        pi.pDynamicState = &dsi;
        pi.layout = pipeLayout_;
        pi.renderPass = renderPass_;
        pi.subpass = 0;
        VKC(vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &pi, nullptr, &out));
        return true;
    };
    bool ok = make(Bg, pBg_) && make(Opaque, pOpaque_) && make(Stencil, pStencil_) && make(Cap, pCap_) &&
              make(Transparent, pTransparent_) && make(Particle, pParticle_);
    for (VkShaderModule m : {pbrV, pbrF, capV, capF, bgV, bgF, ptV, ptF}) vkDestroyShaderModule(device_, m, nullptr);
    return ok;
}

void VkBackend::destroyPipelines() {
    for (VkPipeline* p : {&pBg_, &pOpaque_, &pStencil_, &pCap_, &pTransparent_, &pParticle_}) {
        if (*p) vkDestroyPipeline(device_, *p, nullptr);
        *p = VK_NULL_HANDLE;
    }
}

bool VkBackend::createCommon() {
    VkDescriptorSetLayoutBinding b{};
    b.binding = 0;
    b.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    b.descriptorCount = 1;
    b.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo sli{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    sli.bindingCount = 1;
    sli.pBindings = &b;
    VKC(vkCreateDescriptorSetLayout(device_, &sli, nullptr, &setLayout_));
    VkPushConstantRange pr{VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, kPushBytes};
    VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pli.setLayoutCount = 1;
    pli.pSetLayouts = &setLayout_;
    pli.pushConstantRangeCount = 1;
    pli.pPushConstantRanges = &pr;
    VKC(vkCreatePipelineLayout(device_, &pli, nullptr, &pipeLayout_));

    VkCommandPoolCreateInfo pi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pi.queueFamilyIndex = queueFamily_;
    VKC(vkCreateCommandPool(device_, &pi, nullptr, &cmdPool_));
    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, kFrames};
    VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dpi.maxSets = kFrames;
    dpi.poolSizeCount = 1;
    dpi.pPoolSizes = &ps;
    VKC(vkCreateDescriptorPool(device_, &dpi, nullptr, &descPool_));
    for (Frame& fr : frames_) {
        VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        ai.commandPool = cmdPool_;
        ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        ai.commandBufferCount = 1;
        VKC(vkAllocateCommandBuffers(device_, &ai, &fr.cmd));
        VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        VKC(vkCreateFence(device_, &fi, nullptr, &fr.fence));
        VkSemaphoreCreateInfo si{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        VKC(vkCreateSemaphore(device_, &si, nullptr, &fr.imageAvailable));
        if (!createBuffer(kDynBytes, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, fr.dyn)) return false;
        VkDescriptorSetAllocateInfo dai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        dai.descriptorPool = descPool_;
        dai.descriptorSetCount = 1;
        dai.pSetLayouts = &setLayout_;
        VKC(vkAllocateDescriptorSets(device_, &dai, &fr.set));
        VkDescriptorBufferInfo bi{fr.dyn.buf, kUboOffset, sizeof(FrameUbo)};
        VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        w.dstSet = fr.set;
        w.dstBinding = 0;
        w.descriptorCount = 1;
        w.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        w.pBufferInfo = &bi;
        vkUpdateDescriptorSets(device_, 1, &w, 0, nullptr);
    }
    return true;
}

bool VkBackend::createSwapchain() {
    VkSurfaceCapabilitiesKHR caps;
    VKC(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(phys_, surface_, &caps));
    if (caps.currentExtent.width != 0xFFFFFFFFu) {
        extent_ = caps.currentExtent;
    } else {
        extent_ = {static_cast<uint32_t>(std::max(1, reqW_)), static_cast<uint32_t>(std::max(1, reqH_))};
    }
    if (extent_.width == 0 || extent_.height == 0) return false;
    uint32_t fn = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(phys_, surface_, &fn, nullptr);
    std::vector<VkSurfaceFormatKHR> fmts(fn);
    vkGetPhysicalDeviceSurfaceFormatsKHR(phys_, surface_, &fn, fmts.data());
    VkSurfaceFormatKHR chosen = fmts.empty() ? VkSurfaceFormatKHR{VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR} : fmts[0];
    manualGamma_ = true;
    for (auto& f : fmts)
        if (f.format == VK_FORMAT_R8G8B8A8_SRGB || f.format == VK_FORMAT_B8G8R8A8_SRGB) { chosen = f; manualGamma_ = false; break; }
    colorFormat_ = chosen.format;
    uint32_t count = std::max(caps.minImageCount, 3u);
    if (caps.maxImageCount) count = std::min(count, caps.maxImageCount);
    VkSwapchainCreateInfoKHR ci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    ci.surface = surface_;
    ci.minImageCount = count;
    ci.imageFormat = chosen.format;
    ci.imageColorSpace = chosen.colorSpace;
    ci.imageExtent = extent_;
    ci.imageArrayLayers = 1;
    ci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    ci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    // プリローテーションはコンポジタに任せる (currentExtent は回転後の寸法)
    ci.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
                                                                                          : caps.currentTransform;
    VkCompositeAlphaFlagBitsKHR alphas[] = {VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
                                            VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR};
    for (auto a : alphas)
        if (caps.supportedCompositeAlpha & a) { ci.compositeAlpha = a; break; }
    ci.presentMode = VK_PRESENT_MODE_FIFO_KHR;  // 垂直同期 (必ず対応)
    ci.clipped = VK_TRUE;
    ci.oldSwapchain = swapchain_;
    VkSwapchainKHR sc = VK_NULL_HANDLE;
    VKC(vkCreateSwapchainKHR(device_, &ci, nullptr, &sc));
    if (swapchain_) vkDestroySwapchainKHR(device_, swapchain_, nullptr);
    swapchain_ = sc;
    uint32_t n = 0;
    vkGetSwapchainImagesKHR(device_, swapchain_, &n, nullptr);
    swapImages_.resize(n);
    vkGetSwapchainImagesKHR(device_, swapchain_, &n, swapImages_.data());
    swapViews_.resize(n);
    for (uint32_t i = 0; i < n; ++i) {
        VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        vi.image = swapImages_[i];
        vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vi.format = colorFormat_;
        vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VKC(vkCreateImageView(device_, &vi, nullptr, &swapViews_[i]));
    }
    renderDone_.resize(n);
    for (auto& s : renderDone_) {
        VkSemaphoreCreateInfo si{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        VKC(vkCreateSemaphore(device_, &si, nullptr, &s));
    }
    if (!renderPass_ && !createRenderPass(colorFormat_, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)) return false;
    if (!pOpaque_ && !createPipelines()) return false;
    if (!createTargets(colorFormat_, swapViews_)) return false;
    swapDirty_ = false;
    return true;
}

void VkBackend::destroySwapchain() {
    destroyTargets();
    for (VkImageView v : swapViews_) vkDestroyImageView(device_, v, nullptr);
    swapViews_.clear();
    for (VkSemaphore s : renderDone_) vkDestroySemaphore(device_, s, nullptr);
    renderDone_.clear();
}

bool VkBackend::initSurface(ANativeWindow* window) {
#ifdef __ANDROID__
    release();
    if (!createInstance(true, false)) return false;
    VkAndroidSurfaceCreateInfoKHR si{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    si.window = window;
    VKC(vkCreateAndroidSurfaceKHR(instance_, &si, nullptr, &surface_));
    reqW_ = ANativeWindow_getWidth(window);
    reqH_ = ANativeWindow_getHeight(window);
    if (!pickDevice() || !createDevice(true) || !createCommon() || !createSwapchain()) {
        release();
        return false;
    }
    return true;
#else
    (void)window;
    return false;
#endif
}

bool VkBackend::initHeadless(int width, int height, bool validation) {
    release();
    headless_ = true;
    if (!createInstance(false, validation) || !pickDevice() || !createDevice(false) || !createCommon()) return false;
    extent_ = {static_cast<uint32_t>(width), static_cast<uint32_t>(height)};
    colorFormat_ = VK_FORMAT_R8G8B8A8_SRGB;
    manualGamma_ = false;
    if (!createImage(extent_.width, extent_.height, colorFormat_, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                     VK_SAMPLE_COUNT_1_BIT, VK_IMAGE_ASPECT_COLOR_BIT, offscreen_))
        return false;
    if (!createRenderPass(colorFormat_, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) || !createPipelines()) return false;
    return createTargets(colorFormat_, {offscreen_.view});
}

void VkBackend::release() {
    if (device_) {
        vkDeviceWaitIdle(device_);
        destroySwapchain();
        if (swapchain_) vkDestroySwapchainKHR(device_, swapchain_, nullptr);
        swapchain_ = VK_NULL_HANDLE;
        destroyImage(offscreen_);
        destroyPipelines();
        if (renderPass_) vkDestroyRenderPass(device_, renderPass_, nullptr);
        if (pipeLayout_) vkDestroyPipelineLayout(device_, pipeLayout_, nullptr);
        if (setLayout_) vkDestroyDescriptorSetLayout(device_, setLayout_, nullptr);
        if (descPool_) vkDestroyDescriptorPool(device_, descPool_, nullptr);
        for (Frame& fr : frames_) {
            if (fr.fence) vkDestroyFence(device_, fr.fence, nullptr);
            if (fr.imageAvailable) vkDestroySemaphore(device_, fr.imageAvailable, nullptr);
            destroyBuffer(fr.dyn);
            fr = Frame();
        }
        if (cmdPool_) vkDestroyCommandPool(device_, cmdPool_, nullptr);
        destroyBuffer(vbo_);
        destroyBuffer(ibo_);
        vkDestroyDevice(device_, nullptr);
    }
    renderPass_ = VK_NULL_HANDLE;
    pipeLayout_ = VK_NULL_HANDLE;
    setLayout_ = VK_NULL_HANDLE;
    descPool_ = VK_NULL_HANDLE;
    cmdPool_ = VK_NULL_HANDLE;
    device_ = VK_NULL_HANDLE;
    if (instance_) {
        if (surface_) vkDestroySurfaceKHR(instance_, surface_, nullptr);
        if (messenger_) {
            auto destroy = reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(vkGetInstanceProcAddr(instance_, "vkDestroyDebugUtilsMessengerEXT"));
            if (destroy) destroy(instance_, messenger_, nullptr);
        }
        vkDestroyInstance(instance_, nullptr);
    }
    surface_ = VK_NULL_HANDLE;
    messenger_ = VK_NULL_HANDLE;
    instance_ = VK_NULL_HANDLE;
    phys_ = VK_NULL_HANDLE;
    meshes_.clear();
    uploadedVersion_ = -1;
    headless_ = false;
}

void VkBackend::resize(int w, int h) {
    if (w == reqW_ && h == reqH_) return;
    reqW_ = w;
    reqH_ = h;
    swapDirty_ = true;
}

bool VkBackend::uploadScene(const Scene& scene) {
    vkDeviceWaitIdle(device_);
    destroyBuffer(vbo_);
    destroyBuffer(ibo_);
    meshes_.clear();
    size_t vbytes = 0, icount = 0;
    for (const MeshData& m : scene.meshes) { vbytes += m.vertices.size() * sizeof(float); icount += m.indices.size(); }
    if (!createBuffer(std::max<size_t>(vbytes, 64), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, vbo_)) return false;
    if (!createBuffer(std::max<size_t>(icount * 4, 64), VK_BUFFER_USAGE_INDEX_BUFFER_BIT, ibo_)) return false;
    auto* v = static_cast<uint8_t*>(vbo_.map);
    auto* ix = static_cast<uint32_t*>(ibo_.map);
    uint32_t vbase = 0, ibase = 0;
    for (const MeshData& m : scene.meshes) {
        std::memcpy(v, m.vertices.data(), m.vertices.size() * sizeof(float));
        v += m.vertices.size() * sizeof(float);
        std::memcpy(ix + ibase, m.indices.data(), m.indices.size() * 4);
        meshes_.push_back({ibase, static_cast<uint32_t>(m.indices.size()), static_cast<int32_t>(vbase)});
        vbase += m.vertexCount();
        ibase += static_cast<uint32_t>(m.indices.size());
    }
    return true;
}

void VkBackend::record(Frame& fr, uint32_t imageIndex, const FrameData& f) {
    VkCommandBuffer cb = fr.cmd;
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cb, &bi);
    VkClearValue clears[3]{};
    clears[0].color = {{0.004f, 0.004f, 0.005f, 1.0f}};
    clears[1].depthStencil = {1.0f, 0};
    clears[2].color = clears[0].color;
    VkRenderPassBeginInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    rp.renderPass = renderPass_;
    rp.framebuffer = framebuffers_[imageIndex];
    rp.renderArea = {{0, 0}, extent_};
    rp.clearValueCount = samples_ != VK_SAMPLE_COUNT_1_BIT ? 3 : 2;
    rp.pClearValues = clears;
    vkCmdBeginRenderPass(cb, &rp, VK_SUBPASS_CONTENTS_INLINE);
    VkViewport vp{0, 0, static_cast<float>(extent_.width), static_cast<float>(extent_.height), 0, 1};
    VkRect2D sc{{0, 0}, extent_};
    vkCmdSetViewport(cb, 0, 1, &vp);
    vkCmdSetScissor(cb, 0, 1, &sc);
    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLayout_, 0, 1, &fr.set, 0, nullptr);

    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pBg_);
    vkCmdDraw(cb, 3, 1, 0, 0);

    VkDeviceSize zero = 0;
    auto drawList = [&](const std::vector<DrawItem>& list, VkPipeline pipe) {
        if (list.empty()) return;
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
        vkCmdBindVertexBuffers(cb, 0, 1, &vbo_.buf, &zero);
        vkCmdBindIndexBuffer(cb, ibo_.buf, 0, VK_INDEX_TYPE_UINT32);
        for (const DrawItem& d : list) {
            if (d.mesh < 0 || d.mesh >= static_cast<int>(meshes_.size())) continue;
            vkCmdPushConstants(cb, pipeLayout_, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, kPushBytes, &d);
            const MeshRange& m = meshes_[d.mesh];
            vkCmdDrawIndexed(cb, m.indexCount, 1, m.firstIndex, m.vertexOffset, 0);
        }
    };
    drawList(f.opaque, pOpaque_);

    if (f.section && !f.stencil.empty()) {
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pStencil_);
        vkCmdBindVertexBuffers(cb, 0, 1, &vbo_.buf, &zero);
        vkCmdBindIndexBuffer(cb, ibo_.buf, 0, VK_INDEX_TYPE_UINT32);
        vkCmdSetStencilCompareMask(cb, VK_STENCIL_FACE_FRONT_AND_BACK, 0xFF);
        vkCmdSetStencilReference(cb, VK_STENCIL_FACE_FRONT_AND_BACK, 0);
        for (size_t i = 0; i < f.stencil.size(); ++i) {
            const DrawItem& d = f.stencil[i];
            if (d.mesh < 0 || d.mesh >= static_cast<int>(meshes_.size())) continue;
            vkCmdSetStencilWriteMask(cb, VK_STENCIL_FACE_FRONT_AND_BACK, 1u << f.stencilGroup[i]);
            vkCmdPushConstants(cb, pipeLayout_, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, kPushBytes, &d);
            const MeshRange& m = meshes_[d.mesh];
            vkCmdDrawIndexed(cb, m.indexCount, 1, m.firstIndex, m.vertexOffset, 0);
        }
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pCap_);
        VkDeviceSize capOff = kCapOffset;
        vkCmdBindVertexBuffers(cb, 0, 1, &fr.dyn.buf, &capOff);
        vkCmdSetStencilWriteMask(cb, VK_STENCIL_FACE_FRONT_AND_BACK, 0);
        for (uint32_t g = 0; g < 8; ++g) {
            DrawItem c{};
            c.base[0] = f.capColor[g].x; c.base[1] = f.capColor[g].y; c.base[2] = f.capColor[g].z; c.base[3] = 1;
            vkCmdSetStencilCompareMask(cb, VK_STENCIL_FACE_FRONT_AND_BACK, 1u << g);
            vkCmdSetStencilReference(cb, VK_STENCIL_FACE_FRONT_AND_BACK, 1u << g);
            vkCmdPushConstants(cb, pipeLayout_, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, kPushBytes, &c);
            vkCmdDraw(cb, 4, 1, 0, 0);
        }
    }
    drawList(f.transparent, pTransparent_);
    if (!f.particles.empty()) {
        size_t maxV = (kDynBytes - kPartOffset) / sizeof(ParticleVertex);
        uint32_t n = static_cast<uint32_t>(std::min(f.particles.size(), maxV));
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pParticle_);
        VkDeviceSize off = kPartOffset;
        vkCmdBindVertexBuffers(cb, 0, 1, &fr.dyn.buf, &off);
        vkCmdDraw(cb, n, 1, 0, 0);
    }
    vkCmdEndRenderPass(cb);
    vkEndCommandBuffer(cb);
}

bool VkBackend::render(const FrameData& f, const Scene& scene, int sceneVersion) {
    if (!device_) return false;
    if (sceneVersion != uploadedVersion_) {
        if (!uploadScene(scene)) return false;
        uploadedVersion_ = sceneVersion;
    }
    if (!headless_ && swapDirty_) {
        vkDeviceWaitIdle(device_);
        destroySwapchain();
        if (!createSwapchain()) return false;
    }
    Frame& fr = frames_[frameIndex_];
    vkWaitForFences(device_, 1, &fr.fence, VK_TRUE, UINT64_MAX);

    uint32_t imageIndex = 0;
    if (!headless_) {
        VkResult r = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, fr.imageAvailable, VK_NULL_HANDLE, &imageIndex);
        if (r == VK_ERROR_OUT_OF_DATE_KHR) { swapDirty_ = true; return true; }
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) return false;
    }
    vkResetFences(device_, 1, &fr.fence);

    // フレーム UBO: GL 規約の行列を Vulkan クリップ空間 (Y 反転, z 0..1) に変換
    FrameUbo u{};
    Mat4 fix = Mat4::identity();
    fix.at(1, 1) = -1.0f;
    fix.at(2, 2) = 0.5f;
    fix.at(2, 3) = 0.5f;
    Mat4 vp = fix * f.viewProj;
    std::memcpy(u.viewProj, vp.m, sizeof(u.viewProj));
    u.camPos[0] = f.camPos.x; u.camPos[1] = f.camPos.y; u.camPos[2] = f.camPos.z;
    std::memcpy(u.clip, f.clip, sizeof(u.clip));
    std::memcpy(u.flash, f.flash, sizeof(u.flash));
    u.misc[0] = f.time;
    u.misc[1] = manualGamma_ ? 1.0f : 0.0f;
    auto* dyn = static_cast<uint8_t*>(fr.dyn.map);
    std::memcpy(dyn + kUboOffset, &u, sizeof(u));
    std::memcpy(dyn + kCapOffset, f.capQuad, sizeof(f.capQuad));
    size_t maxV = (kDynBytes - kPartOffset) / sizeof(ParticleVertex);
    if (!f.particles.empty())
        std::memcpy(dyn + kPartOffset, f.particles.data(), std::min(f.particles.size(), maxV) * sizeof(ParticleVertex));

    vkResetCommandBuffer(fr.cmd, 0);
    record(fr, imageIndex, f);

    VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &fr.cmd;
    if (!headless_) {
        si.waitSemaphoreCount = 1;
        si.pWaitSemaphores = &fr.imageAvailable;
        si.pWaitDstStageMask = &waitStage;
        si.signalSemaphoreCount = 1;
        si.pSignalSemaphores = &renderDone_[imageIndex];
    }
    VKC(vkQueueSubmit(queue_, 1, &si, fr.fence));
    if (!headless_) {
        VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        pi.waitSemaphoreCount = 1;
        pi.pWaitSemaphores = &renderDone_[imageIndex];
        pi.swapchainCount = 1;
        pi.pSwapchains = &swapchain_;
        pi.pImageIndices = &imageIndex;
        VkResult r = vkQueuePresentKHR(queue_, &pi);
        if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_SUBOPTIMAL_KHR) swapDirty_ = true;
        else if (r != VK_SUCCESS) return false;
    }
    frameIndex_ = (frameIndex_ + 1) % kFrames;
    return true;
}

bool VkBackend::readback(std::vector<uint8_t>& rgba, int& w, int& h) {
    if (!headless_ || !device_) return false;
    vkDeviceWaitIdle(device_);
    Buffer staging;
    VkDeviceSize bytes = static_cast<VkDeviceSize>(extent_.width) * extent_.height * 4;
    if (!createBuffer(bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT, staging)) return false;
    VkCommandBuffer cb = frames_[0].cmd;
    vkResetCommandBuffer(cb, 0);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cb, &bi);
    VkBufferImageCopy region{};
    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.imageExtent = {extent_.width, extent_.height, 1};
    vkCmdCopyImageToBuffer(cb, offscreen_.img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, staging.buf, 1, &region);
    vkEndCommandBuffer(cb);
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cb;
    vkResetFences(device_, 1, &frames_[0].fence);
    VKC(vkQueueSubmit(queue_, 1, &si, frames_[0].fence));
    vkWaitForFences(device_, 1, &frames_[0].fence, VK_TRUE, UINT64_MAX);
    rgba.resize(bytes);
    std::memcpy(rgba.data(), staging.map, bytes);
    destroyBuffer(staging);
    w = static_cast<int>(extent_.width);
    h = static_cast<int>(extent_.height);
    return true;
}

}  // namespace es
