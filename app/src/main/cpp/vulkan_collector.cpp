// Vulkan 采集引擎：dlopen 动态加载 libvulkan（或指定驱动库），全函数指针解析，
// 采集物理设备属性/特性/扩展/限制/内存/队列族，输出 JSON。
// 不链接系统 libvulkan.so，这样可以采集"指定驱动"暴露的能力（M2 自定义驱动检测）。

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>
#include <sys/wait.h>
#include <poll.h>
#include <vulkan/vulkan.h>
#include <string>
#include <vector>
#include <cstring>
#include <sstream>
#include <iomanip>
#include <functional>
#include <cmath>
#include <thread>
#include <atomic>
// AdrenoTools（BSD-2-Clause © 2021 Billy Laws）：Winlator/GameHub/Eden 同款
// 驱动加载路径（linker namespace 绕过 + GPU 映射导入），见 adrenotools/driver.h。
#include <adrenotools/driver.h>

#define LOG_TAG "EmuBoxVulkan"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ── VkFormat → 名称（format 矩阵 JSON 输出用）────────────────────────
static const char* stringifyFormat(VkFormat f) {
    switch (f) {
        case VK_FORMAT_R8G8B8A8_UNORM: return "R8G8B8A8_UNORM";
        case VK_FORMAT_B8G8R8A8_UNORM: return "B8G8R8A8_UNORM";
        case VK_FORMAT_R8G8B8A8_SRGB: return "R8G8B8A8_SRGB";
        case VK_FORMAT_B8G8R8A8_SRGB: return "B8G8R8A8_SRGB";
        case VK_FORMAT_R16G16B16A16_SFLOAT: return "R16G16B16A16_SFLOAT";
        case VK_FORMAT_R32G32B32A32_SFLOAT: return "R32G32B32A32_SFLOAT";
        case VK_FORMAT_R16_SFLOAT: return "R16_SFLOAT";
        case VK_FORMAT_R32_SFLOAT: return "R32_SFLOAT";
        case VK_FORMAT_R8_UNORM: return "R8_UNORM";
        case VK_FORMAT_R8G8_UNORM: return "R8G8_UNORM";
        case VK_FORMAT_A2R10G10B10_UNORM_PACK32: return "A2R10G10B10_UNORM_PACK32";
        case VK_FORMAT_B10G11R11_UFLOAT_PACK32: return "B10G11R11_UFLOAT_PACK32";
        case VK_FORMAT_D16_UNORM: return "D16_UNORM";
        case VK_FORMAT_D24_UNORM_S8_UINT: return "D24_UNORM_S8_UINT";
        case VK_FORMAT_D32_SFLOAT: return "D32_SFLOAT";
        case VK_FORMAT_D32_SFLOAT_S8_UINT: return "D32_SFLOAT_S8_UINT";
        case VK_FORMAT_BC1_RGBA_UNORM_BLOCK: return "BC1_RGBA_UNORM_BLOCK";
        case VK_FORMAT_BC3_UNORM_BLOCK: return "BC3_UNORM_BLOCK";
        case VK_FORMAT_BC5_UNORM_BLOCK: return "BC5_UNORM_BLOCK";
        case VK_FORMAT_BC7_UNORM_BLOCK: return "BC7_UNORM_BLOCK";
        case VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK: return "ETC2_R8G8B8_UNORM_BLOCK";
        case VK_FORMAT_ASTC_4x4_UNORM_BLOCK: return "ASTC_4x4_UNORM_BLOCK";
        default: return "UNKNOWN";
    }
}

// ── 函数指针类型（vulkan.h 里 PFN_vk* 本身已是函数指针，直接用别名）──────
// vkGetInstanceProcAddr 的函数指针类型（明确指定 2 参数）
using GetInstanceProcAddrFn = PFN_vkVoidFunction (*)(VkInstance, const char*);

// 以下均为 vulkan.h 原始 PFN_vk* 类型的别名，避免重复套指针层
using PFN_CreateInstance = PFN_vkCreateInstance;
using PFN_DestroyInstance = PFN_vkDestroyInstance;
using PFN_EnumeratePhysicalDevices = PFN_vkEnumeratePhysicalDevices;
using PFN_GetPhysicalDeviceProperties = PFN_vkGetPhysicalDeviceProperties;
using PFN_GetPhysicalDeviceFeatures = PFN_vkGetPhysicalDeviceFeatures;
using PFN_EnumerateDeviceExtensionProperties = PFN_vkEnumerateDeviceExtensionProperties;
using PFN_GetPhysicalDeviceMemoryProperties = PFN_vkGetPhysicalDeviceMemoryProperties;
using PFN_GetPhysicalDeviceQueueFamilyProperties = PFN_vkGetPhysicalDeviceQueueFamilyProperties;
using PFN_GetPhysicalDeviceProperties2 = PFN_vkGetPhysicalDeviceProperties2;
using PFN_GetPhysicalDeviceFeatures2 = PFN_vkGetPhysicalDeviceFeatures2;
using PFN_GetPhysicalDeviceFormatProperties = PFN_vkGetPhysicalDeviceFormatProperties;

struct VulkanDispatch {
    void* libHandle = nullptr;
    PFN_CreateInstance vkCreateInstance = nullptr;
    PFN_DestroyInstance vkDestroyInstance = nullptr;
    PFN_EnumeratePhysicalDevices vkEnumeratePhysicalDevices = nullptr;
    PFN_GetPhysicalDeviceProperties vkGetPhysicalDeviceProperties = nullptr;
    PFN_GetPhysicalDeviceFeatures vkGetPhysicalDeviceFeatures = nullptr;
    PFN_EnumerateDeviceExtensionProperties vkEnumerateDeviceExtensionProperties = nullptr;
    PFN_GetPhysicalDeviceMemoryProperties vkGetPhysicalDeviceMemoryProperties = nullptr;
    PFN_GetPhysicalDeviceQueueFamilyProperties vkGetPhysicalDeviceQueueFamilyProperties = nullptr;
    PFN_GetPhysicalDeviceProperties2 vkGetPhysicalDeviceProperties2 = nullptr;
    PFN_GetPhysicalDeviceFeatures2 vkGetPhysicalDeviceFeatures2 = nullptr;
    PFN_GetPhysicalDeviceFormatProperties vkGetPhysicalDeviceFormatProperties = nullptr;
};

// ── JSON 工具（手写拼接，不引入第三方库）──────────────────────────────────
static std::string jsonEscape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += c;
                }
        }
    }
    return out;
}

static std::string versionToString(uint32_t v) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%u.%u.%u",
             VK_API_VERSION_MAJOR(v),
             VK_API_VERSION_MINOR(v),
             VK_API_VERSION_PATCH(v));
    return std::string(buf);
}

static std::string deviceTypeToString(VkPhysicalDeviceType t) {
    switch (t) {
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "integrated_gpu";
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: return "discrete_gpu";
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: return "virtual_gpu";
        case VK_PHYSICAL_DEVICE_TYPE_CPU: return "cpu";
        default: return "other";
    }
}

static std::string queueFlagsToString(VkQueueFlags flags) {
    std::vector<std::string> parts;
    if (flags & VK_QUEUE_GRAPHICS_BIT) parts.push_back("graphics");
    if (flags & VK_QUEUE_COMPUTE_BIT) parts.push_back("compute");
    if (flags & VK_QUEUE_TRANSFER_BIT) parts.push_back("transfer");
    if (flags & VK_QUEUE_SPARSE_BINDING_BIT) parts.push_back("sparse_binding");
    if (flags & VK_QUEUE_PROTECTED_BIT) parts.push_back("protected");
    std::string out;
    for (size_t i = 0; i < parts.size(); i++) {
        if (i) out += "|";
        out += parts[i];
    }
    return out;
}

static std::string memoryPropertyFlagsToString(VkMemoryPropertyFlags flags) {
    std::vector<std::string> parts;
    if (flags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) parts.push_back("device_local");
    if (flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) parts.push_back("host_visible");
    if (flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) parts.push_back("host_coherent");
    if (flags & VK_MEMORY_PROPERTY_HOST_CACHED_BIT) parts.push_back("host_cached");
    if (flags & VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT) parts.push_back("lazily_allocated");
    if (flags & VK_MEMORY_PROPERTY_PROTECTED_BIT) parts.push_back("protected");
    std::string out;
    for (size_t i = 0; i < parts.size(); i++) {
        if (i) out += "|";
        out += parts[i];
    }
    return out;
}

static std::string memoryHeapFlagsToString(VkMemoryHeapFlags flags) {
    std::vector<std::string> parts;
    if (flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) parts.push_back("device_local");
    if (flags & VK_MEMORY_HEAP_MULTI_INSTANCE_BIT) parts.push_back("multi_instance");
    std::string out;
    for (size_t i = 0; i < parts.size(); i++) {
        if (i) out += "|";
        out += parts[i];
    }
    return out;
}

// ── 采集单个物理设备 ────────────────────────────────────────────────────────
static std::string collectDevice(const VulkanDispatch& vk, VkPhysicalDevice device) {
    std::ostringstream j;

    // 基础属性
    VkPhysicalDeviceProperties props{};
    vk.vkGetPhysicalDeviceProperties(device, &props);

    j << "{";
    j << "\"deviceName\":\"" << jsonEscape(props.deviceName) << "\",";
    j << "\"deviceType\":\"" << deviceTypeToString(props.deviceType) << "\",";
    j << "\"vendorId\":" << props.vendorID << ",";
    j << "\"deviceId\":" << props.deviceID << ",";
    j << "\"apiVersion\":\"" << versionToString(props.apiVersion) << "\",";
    j << "\"driverVersion\":\"" << versionToString(props.driverVersion) << "\",";

    // driverName / driverInfo（通过 VK_KHR_get_physical_device_properties2）
    std::string driverName = "";
    std::string driverInfo = "";
    if (vk.vkGetPhysicalDeviceProperties2) {
        VkPhysicalDeviceDriverProperties driverProps{};
        driverProps.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;

        VkPhysicalDeviceProperties2 props2{};
        props2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
        props2.pNext = &driverProps;

        vk.vkGetPhysicalDeviceProperties2(device, &props2);
        driverName = driverProps.driverName;
        driverInfo = driverProps.driverInfo;
    }
    j << "\"driverName\":\"" << jsonEscape(driverName) << "\",";
    j << "\"driverInfo\":\"" << jsonEscape(driverInfo) << "\",";

    // 特性（只列关键特性，避免 JSON 过大）
    VkPhysicalDeviceFeatures features{};
    vk.vkGetPhysicalDeviceFeatures(device, &features);
    j << "\"features\":{";
    j << "\"robustBufferAccess\":" << (features.robustBufferAccess ? "true" : "false") << ",";
    j << "\"fullDrawIndexUint32\":" << (features.fullDrawIndexUint32 ? "true" : "false") << ",";
    j << "\"imageCubeArray\":" << (features.imageCubeArray ? "true" : "false") << ",";
    j << "\"independentBlend\":" << (features.independentBlend ? "true" : "false") << ",";
    j << "\"geometryShader\":" << (features.geometryShader ? "true" : "false") << ",";
    j << "\"tessellationShader\":" << (features.tessellationShader ? "true" : "false") << ",";
    j << "\"sampleRateShading\":" << (features.sampleRateShading ? "true" : "false") << ",";
    j << "\"dualSrcBlend\":" << (features.dualSrcBlend ? "true" : "false") << ",";
    j << "\"logicOp\":" << (features.logicOp ? "true" : "false") << ",";
    j << "\"multiDrawIndirect\":" << (features.multiDrawIndirect ? "true" : "false") << ",";
    j << "\"drawIndirectFirstInstance\":" << (features.drawIndirectFirstInstance ? "true" : "false") << ",";
    j << "\"depthClamp\":" << (features.depthClamp ? "true" : "false") << ",";
    j << "\"depthBiasClamp\":" << (features.depthBiasClamp ? "true" : "false") << ",";
    j << "\"fillModeNonSolid\":" << (features.fillModeNonSolid ? "true" : "false") << ",";
    j << "\"depthBounds\":" << (features.depthBounds ? "true" : "false") << ",";
    j << "\"wideLines\":" << (features.wideLines ? "true" : "false") << ",";
    j << "\"largePoints\":" << (features.largePoints ? "true" : "false") << ",";
    j << "\"alphaToOne\":" << (features.alphaToOne ? "true" : "false") << ",";
    j << "\"multiViewport\":" << (features.multiViewport ? "true" : "false") << ",";
    j << "\"samplerAnisotropy\":" << (features.samplerAnisotropy ? "true" : "false") << ",";
    j << "\"textureCompressionETC2\":" << (features.textureCompressionETC2 ? "true" : "false") << ",";
    j << "\"textureCompressionASTC_LDR\":" << (features.textureCompressionASTC_LDR ? "true" : "false") << ",";
    j << "\"textureCompressionBC\":" << (features.textureCompressionBC ? "true" : "false") << ",";
    j << "\"occlusionQueryPrecise\":" << (features.occlusionQueryPrecise ? "true" : "false") << ",";
    j << "\"pipelineStatisticsQuery\":" << (features.pipelineStatisticsQuery ? "true" : "false") << ",";
    j << "\"vertexPipelineStoresAndAtomics\":" << (features.vertexPipelineStoresAndAtomics ? "true" : "false") << ",";
    j << "\"fragmentStoresAndAtomics\":" << (features.fragmentStoresAndAtomics ? "true" : "false") << ",";
    j << "\"shaderTessellationAndGeometryPointSize\":" << (features.shaderTessellationAndGeometryPointSize ? "true" : "false") << ",";
    j << "\"shaderImageGatherExtended\":" << (features.shaderImageGatherExtended ? "true" : "false") << ",";
    j << "\"shaderStorageImageExtendedFormats\":" << (features.shaderStorageImageExtendedFormats ? "true" : "false") << ",";
    j << "\"shaderStorageImageMultisample\":" << (features.shaderStorageImageMultisample ? "true" : "false") << ",";
    j << "\"shaderStorageImageReadWithoutFormat\":" << (features.shaderStorageImageReadWithoutFormat ? "true" : "false") << ",";
    j << "\"shaderStorageImageWriteWithoutFormat\":" << (features.shaderStorageImageWriteWithoutFormat ? "true" : "false") << ",";
    j << "\"shaderUniformBufferArrayDynamicIndexing\":" << (features.shaderUniformBufferArrayDynamicIndexing ? "true" : "false") << ",";
    j << "\"shaderSampledImageArrayDynamicIndexing\":" << (features.shaderSampledImageArrayDynamicIndexing ? "true" : "false") << ",";
    j << "\"shaderStorageBufferArrayDynamicIndexing\":" << (features.shaderStorageBufferArrayDynamicIndexing ? "true" : "false") << ",";
    j << "\"shaderStorageImageArrayDynamicIndexing\":" << (features.shaderStorageImageArrayDynamicIndexing ? "true" : "false") << ",";
    j << "\"shaderClipDistance\":" << (features.shaderClipDistance ? "true" : "false") << ",";
    j << "\"shaderCullDistance\":" << (features.shaderCullDistance ? "true" : "false") << ",";
    j << "\"shaderFloat64\":" << (features.shaderFloat64 ? "true" : "false") << ",";
    j << "\"shaderInt64\":" << (features.shaderInt64 ? "true" : "false") << ",";
    j << "\"shaderInt16\":" << (features.shaderInt16 ? "true" : "false") << ",";
    j << "\"shaderResourceResidency\":" << (features.shaderResourceResidency ? "true" : "false") << ",";
    j << "\"shaderResourceMinLod\":" << (features.shaderResourceMinLod ? "true" : "false") << ",";
    j << "\"sparseBinding\":" << (features.sparseBinding ? "true" : "false") << ",";
    j << "\"sparseResidencyBuffer\":" << (features.sparseResidencyBuffer ? "true" : "false") << ",";
    j << "\"sparseResidencyImage2D\":" << (features.sparseResidencyImage2D ? "true" : "false") << ",";
    j << "\"sparseResidencyImage3D\":" << (features.sparseResidencyImage3D ? "true" : "false") << ",";
    j << "\"sparseResidency2Samples\":" << (features.sparseResidency2Samples ? "true" : "false") << ",";
    j << "\"sparseResidency4Samples\":" << (features.sparseResidency4Samples ? "true" : "false") << ",";
    j << "\"sparseResidency8Samples\":" << (features.sparseResidency8Samples ? "true" : "false") << ",";
    j << "\"sparseResidency16Samples\":" << (features.sparseResidency16Samples ? "true" : "false") << ",";
    j << "\"sparseResidencyAliased\":" << (features.sparseResidencyAliased ? "true" : "false") << ",";
    j << "\"variableMultisampleRate\":" << (features.variableMultisampleRate ? "true" : "false") << ",";
    j << "\"inheritedQueries\":" << (features.inheritedQueries ? "true" : "false");
    j << "}";

    // Vulkan 1.2/1.3 高价值特性（动态渲染/描述符索引/时间线信号量/同步2 等）。
    // 仅主采集路径解析了 vkGetPhysicalDeviceFeatures2（系统+驱动两条），
    // 解析失败（旧 ROM/驱动未导出）时输出空对象，UI 层显示"不可用"。
    j << ",\"features2\":{";
    bool f2ok = vk.vkGetPhysicalDeviceFeatures2 != nullptr;
    if (f2ok) {
        VkPhysicalDeviceVulkan13Features f13{};
        f13.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;
        VkPhysicalDeviceVulkan12Features f12{};
        f12.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
        VkPhysicalDeviceFeatures2 f2{};
        f2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        f2.pNext = &f12;
        f12.pNext = &f13;
        vk.vkGetPhysicalDeviceFeatures2(device, &f2);
        j << "\"dynamicRendering\":" << (f13.dynamicRendering ? "true" : "false") << ","
          << "\"synchronization2\":" << (f13.synchronization2 ? "true" : "false") << ","
          << "\"maintenance4\":" << (f13.maintenance4 ? "true" : "false") << ","
          << "\"pipelineCreationCacheControl\":" << (f13.pipelineCreationCacheControl ? "true" : "false") << ","
          << "\"privateData\":" << (f13.privateData ? "true" : "false") << ","
          << "\"shaderDemoteToHelperInvocation\":" << (f13.shaderDemoteToHelperInvocation ? "true" : "false") << ","
          << "\"inlineUniformBlock\":" << (f13.inlineUniformBlock ? "true" : "false") << ","
          << "\"shaderTerminateInvocation\":" << (f13.shaderTerminateInvocation ? "true" : "false") << ","
          << "\"timelineSemaphore\":" << (f12.timelineSemaphore ? "true" : "false") << ","
          << "\"bufferDeviceAddress\":" << (f12.bufferDeviceAddress ? "true" : "false") << ","
          << "\"descriptorIndexing\":" << (f12.descriptorIndexing ? "true" : "false") << ","
          << "\"shaderFloat16\":" << (f12.shaderFloat16 ? "true" : "false") << ","
          << "\"shaderInt8\":" << (f12.shaderInt8 ? "true" : "false") << ","
          << "\"storageBuffer8BitAccess\":" << (f12.storageBuffer8BitAccess ? "true" : "false") << ","
          << "\"shaderBufferInt64Atomics\":" << (f12.shaderBufferInt64Atomics ? "true" : "false") << ","
          << "\"drawIndirectCount\":" << (f12.drawIndirectCount ? "true" : "false") << ","
          << "\"scalarBlockLayout\":" << (f12.scalarBlockLayout ? "true" : "false") << ","
          << "\"uniformBufferStandardLayout\":" << (f12.uniformBufferStandardLayout ? "true" : "false") << ","
          << "\"separateDepthStencilLayouts\":" << (f12.separateDepthStencilLayouts ? "true" : "false") << ","
          << "\"hostQueryReset\":" << (f12.hostQueryReset ? "true" : "false") << ","
          << "\"vulkanMemoryModel\":" << (f12.vulkanMemoryModel ? "true" : "false") << ","
          << "\"shaderOutputLayer\":" << (f12.shaderOutputLayer ? "true" : "false") << ","
          << "\"shaderOutputViewportIndex\":" << (f12.shaderOutputViewportIndex ? "true" : "false");
    }
    j << "},";

    // 扩展列表
    uint32_t extCount = 0;
    vk.vkEnumerateDeviceExtensionProperties(device, nullptr, &extCount, nullptr);
    std::vector<VkExtensionProperties> extensions(extCount);
    if (extCount > 0) {
        vk.vkEnumerateDeviceExtensionProperties(device, nullptr, &extCount, extensions.data());
    }
    j << "\"extensions\":[";
    for (uint32_t i = 0; i < extCount; i++) {
        if (i) j << ",";
        j << "{\"name\":\"" << jsonEscape(extensions[i].extensionName)
          << "\",\"specVersion\":" << extensions[i].specVersion << "}";
    }
    j << "],";

    // 内存
    VkPhysicalDeviceMemoryProperties memProps{};
    vk.vkGetPhysicalDeviceMemoryProperties(device, &memProps);
    j << "\"memoryTypes\":[";
    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        if (i) j << ",";
        j << "{\"propertyFlags\":\"" << memoryPropertyFlagsToString(memProps.memoryTypes[i].propertyFlags)
          << "\",\"heapIndex\":" << memProps.memoryTypes[i].heapIndex << "}";
    }
    j << "],";
    j << "\"memoryHeaps\":[";
    for (uint32_t i = 0; i < memProps.memoryHeapCount; i++) {
        if (i) j << ",";
        j << "{\"size\":" << memProps.memoryHeaps[i].size
          << ",\"flags\":\"" << memoryHeapFlagsToString(memProps.memoryHeaps[i].flags) << "\"}";
    }
    j << "],";

    // 队列族
    uint32_t queueCount = 0;
    vk.vkGetPhysicalDeviceQueueFamilyProperties(device, &queueCount, nullptr);
    std::vector<VkQueueFamilyProperties> queues(queueCount);
    if (queueCount > 0) {
        vk.vkGetPhysicalDeviceQueueFamilyProperties(device, &queueCount, queues.data());
    }
    j << "\"queueFamilies\":[";
    for (uint32_t i = 0; i < queueCount; i++) {
        if (i) j << ",";
        j << "{\"queueFlags\":\"" << queueFlagsToString(queues[i].queueFlags)
          << "\",\"queueCount\":" << queues[i].queueCount
          << ",\"timestampValidBits\":" << queues[i].timestampValidBits
          << ",\"minImageTransferGranularity\":["
          << queues[i].minImageTransferGranularity.width << ","
          << queues[i].minImageTransferGranularity.height << ","
          << queues[i].minImageTransferGranularity.depth << "]}";
    }
    j << "]";

    // limits（关键限制）
    const VkPhysicalDeviceLimits& lim = props.limits;
    j << ",\"limits\":{";
    j << "\"maxImageDimension1D\":" << lim.maxImageDimension1D << ",";
    j << "\"maxImageDimension2D\":" << lim.maxImageDimension2D << ",";
    j << "\"maxImageDimension3D\":" << lim.maxImageDimension3D << ",";
    j << "\"maxImageDimensionCube\":" << lim.maxImageDimensionCube << ",";
    j << "\"maxImageArrayLayers\":" << lim.maxImageArrayLayers << ",";
    j << "\"maxTexelBufferElements\":" << lim.maxTexelBufferElements << ",";
    j << "\"maxUniformBufferRange\":" << lim.maxUniformBufferRange << ",";
    j << "\"maxStorageBufferRange\":" << lim.maxStorageBufferRange << ",";
    j << "\"maxPushConstantsSize\":" << lim.maxPushConstantsSize << ",";
    j << "\"maxMemoryAllocationCount\":" << lim.maxMemoryAllocationCount << ",";
    j << "\"maxSamplerAllocationCount\":" << lim.maxSamplerAllocationCount << ",";
    j << "\"bufferImageGranularity\":" << lim.bufferImageGranularity << ",";
    j << "\"sparseAddressSpaceSize\":" << lim.sparseAddressSpaceSize << ",";
    j << "\"maxBoundDescriptorSets\":" << lim.maxBoundDescriptorSets << ",";
    j << "\"maxPerStageDescriptorSamplers\":" << lim.maxPerStageDescriptorSamplers << ",";
    j << "\"maxPerStageDescriptorUniformBuffers\":" << lim.maxPerStageDescriptorUniformBuffers << ",";
    j << "\"maxPerStageDescriptorStorageBuffers\":" << lim.maxPerStageDescriptorStorageBuffers << ",";
    j << "\"maxPerStageDescriptorSampledImages\":" << lim.maxPerStageDescriptorSampledImages << ",";
    j << "\"maxPerStageDescriptorStorageImages\":" << lim.maxPerStageDescriptorStorageImages << ",";
    j << "\"maxPerStageDescriptorInputAttachments\":" << lim.maxPerStageDescriptorInputAttachments << ",";
    j << "\"maxPerStageResources\":" << lim.maxPerStageResources << ",";
    j << "\"maxDescriptorSetSamplers\":" << lim.maxDescriptorSetSamplers << ",";
    j << "\"maxDescriptorSetUniformBuffers\":" << lim.maxDescriptorSetUniformBuffers << ",";
    j << "\"maxDescriptorSetUniformBuffersDynamic\":" << lim.maxDescriptorSetUniformBuffersDynamic << ",";
    j << "\"maxDescriptorSetStorageBuffers\":" << lim.maxDescriptorSetStorageBuffers << ",";
    j << "\"maxDescriptorSetStorageBuffersDynamic\":" << lim.maxDescriptorSetStorageBuffersDynamic << ",";
    j << "\"maxDescriptorSetSampledImages\":" << lim.maxDescriptorSetSampledImages << ",";
    j << "\"maxDescriptorSetStorageImages\":" << lim.maxDescriptorSetStorageImages << ",";
    j << "\"maxDescriptorSetInputAttachments\":" << lim.maxDescriptorSetInputAttachments << ",";
    j << "\"maxVertexInputAttributes\":" << lim.maxVertexInputAttributes << ",";
    j << "\"maxVertexInputBindings\":" << lim.maxVertexInputBindings << ",";
    j << "\"maxVertexInputAttributeOffset\":" << lim.maxVertexInputAttributeOffset << ",";
    j << "\"maxVertexInputBindingStride\":" << lim.maxVertexInputBindingStride << ",";
    j << "\"maxVertexOutputComponents\":" << lim.maxVertexOutputComponents << ",";
    j << "\"maxTessellationGenerationLevel\":" << lim.maxTessellationGenerationLevel << ",";
    j << "\"maxTessellationPatchSize\":" << lim.maxTessellationPatchSize << ",";
    j << "\"maxTessellationControlPerVertexInputComponents\":" << lim.maxTessellationControlPerVertexInputComponents << ",";
    j << "\"maxTessellationControlPerVertexOutputComponents\":" << lim.maxTessellationControlPerVertexOutputComponents << ",";
    j << "\"maxTessellationControlPerPatchOutputComponents\":" << lim.maxTessellationControlPerPatchOutputComponents << ",";
    j << "\"maxTessellationControlTotalOutputComponents\":" << lim.maxTessellationControlTotalOutputComponents << ",";
    j << "\"maxTessellationEvaluationInputComponents\":" << lim.maxTessellationEvaluationInputComponents << ",";
    j << "\"maxTessellationEvaluationOutputComponents\":" << lim.maxTessellationEvaluationOutputComponents << ",";
    j << "\"maxGeometryShaderInvocations\":" << lim.maxGeometryShaderInvocations << ",";
    j << "\"maxGeometryInputComponents\":" << lim.maxGeometryInputComponents << ",";
    j << "\"maxGeometryOutputComponents\":" << lim.maxGeometryOutputComponents << ",";
    j << "\"maxGeometryOutputVertices\":" << lim.maxGeometryOutputVertices << ",";
    j << "\"maxGeometryTotalOutputComponents\":" << lim.maxGeometryTotalOutputComponents << ",";
    j << "\"maxFragmentInputComponents\":" << lim.maxFragmentInputComponents << ",";
    j << "\"maxFragmentOutputAttachments\":" << lim.maxFragmentOutputAttachments << ",";
    j << "\"maxFragmentDualSrcAttachments\":" << lim.maxFragmentDualSrcAttachments << ",";
    j << "\"maxFragmentCombinedOutputResources\":" << lim.maxFragmentCombinedOutputResources << ",";
    j << "\"maxComputeSharedMemorySize\":" << lim.maxComputeSharedMemorySize << ",";
    j << "\"maxComputeWorkGroupCount\":[" << lim.maxComputeWorkGroupCount[0] << "," << lim.maxComputeWorkGroupCount[1] << "," << lim.maxComputeWorkGroupCount[2] << "],";
    j << "\"maxComputeWorkGroupInvocations\":" << lim.maxComputeWorkGroupInvocations << ",";
    j << "\"maxComputeWorkGroupSize\":[" << lim.maxComputeWorkGroupSize[0] << "," << lim.maxComputeWorkGroupSize[1] << "," << lim.maxComputeWorkGroupSize[2] << "],";
    j << "\"subPixelPrecisionBits\":" << lim.subPixelPrecisionBits << ",";
    j << "\"subTexelPrecisionBits\":" << lim.subTexelPrecisionBits << ",";
    j << "\"mipmapPrecisionBits\":" << lim.mipmapPrecisionBits << ",";
    j << "\"maxDrawIndexedIndexValue\":" << lim.maxDrawIndexedIndexValue << ",";
    j << "\"maxDrawIndirectCount\":" << lim.maxDrawIndirectCount << ",";
    j << "\"maxSamplerLodBias\":" << lim.maxSamplerLodBias << ",";
    j << "\"maxSamplerAnisotropy\":" << lim.maxSamplerAnisotropy << ",";
    j << "\"maxViewports\":" << lim.maxViewports << ",";
    j << "\"maxViewportDimensions\":[" << lim.maxViewportDimensions[0] << "," << lim.maxViewportDimensions[1] << "],";
    j << "\"viewportBoundsRange\":[" << lim.viewportBoundsRange[0] << "," << lim.viewportBoundsRange[1] << "],";
    j << "\"viewportSubPixelBits\":" << lim.viewportSubPixelBits << ",";
    j << "\"minMemoryMapAlignment\":" << lim.minMemoryMapAlignment << ",";
    j << "\"minTexelBufferOffsetAlignment\":" << lim.minTexelBufferOffsetAlignment << ",";
    j << "\"minUniformBufferOffsetAlignment\":" << lim.minUniformBufferOffsetAlignment << ",";
    j << "\"minStorageBufferOffsetAlignment\":" << lim.minStorageBufferOffsetAlignment << ",";
    j << "\"minTexelOffset\":" << lim.minTexelOffset << ",";
    j << "\"minInterpolationOffset\":" << lim.minInterpolationOffset << ",";
    j << "\"maxInterpolationOffset\":" << lim.maxInterpolationOffset << ",";
    j << "\"subPixelInterpolationOffsetBits\":" << lim.subPixelInterpolationOffsetBits << ",";
    j << "\"maxFramebufferWidth\":" << lim.maxFramebufferWidth << ",";
    j << "\"maxFramebufferHeight\":" << lim.maxFramebufferHeight << ",";
    j << "\"maxFramebufferLayers\":" << lim.maxFramebufferLayers << ",";
    j << "\"framebufferColorSampleCounts\":" << lim.framebufferColorSampleCounts << ",";
    j << "\"framebufferDepthSampleCounts\":" << lim.framebufferDepthSampleCounts << ",";
    j << "\"framebufferNoAttachmentsSampleCounts\":" << lim.framebufferNoAttachmentsSampleCounts << ",";
    j << "\"maxColorAttachments\":" << lim.maxColorAttachments << ",";
    j << "\"sampledImageColorSampleCounts\":" << lim.sampledImageColorSampleCounts << ",";
    j << "\"sampledImageIntegerSampleCounts\":" << lim.sampledImageIntegerSampleCounts << ",";
    j << "\"sampledImageDepthSampleCounts\":" << lim.sampledImageDepthSampleCounts << ",";
    j << "\"sampledImageStencilSampleCounts\":" << lim.sampledImageStencilSampleCounts << ",";
    j << "\"storageImageSampleCounts\":" << lim.storageImageSampleCounts << ",";
    j << "\"maxSampleMaskWords\":" << lim.maxSampleMaskWords << ",";
    j << "\"timestampComputeAndGraphics\":" << (lim.timestampComputeAndGraphics ? "true" : "false") << ",";
    j << "\"timestampPeriod\":" << lim.timestampPeriod << ",";
    j << "\"maxClipDistances\":" << lim.maxClipDistances << ",";
    j << "\"maxCullDistances\":" << lim.maxCullDistances << ",";
    j << "\"maxCombinedClipAndCullDistances\":" << lim.maxCombinedClipAndCullDistances << ",";
    j << "\"discreteQueuePriorities\":" << lim.discreteQueuePriorities << ",";
    j << "\"pointSizeRange\":[" << lim.pointSizeRange[0] << "," << lim.pointSizeRange[1] << "],";
    j << "\"lineWidthRange\":[" << lim.lineWidthRange[0] << "," << lim.lineWidthRange[1] << "],";
    j << "\"pointSizeGranularity\":" << lim.pointSizeGranularity << ",";
    j << "\"lineWidthGranularity\":" << lim.lineWidthGranularity << ",";
    j << "\"strictLines\":" << (lim.strictLines ? "true" : "false") << ",";
    j << "\"standardSampleLocations\":" << (lim.standardSampleLocations ? "true" : "false") << ",";
    j << "\"optimalBufferCopyOffsetAlignment\":" << lim.optimalBufferCopyOffsetAlignment << ",";
    j << "\"optimalBufferCopyRowPitchAlignment\":" << lim.optimalBufferCopyRowPitchAlignment << ",";
    j << "\"nonCoherentAtomSize\":" << lim.nonCoherentAtomSize;
    j << "}";

    // Format 支持矩阵：DXVK/VKD3D 转译层判断纹理/交换链格式的关键依据
    j << ",\"formats\":[";
    const VkFormat keyFormats[] = {
        VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM,
        VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_B8G8R8A8_SRGB,
        VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R32G32B32A32_SFLOAT,
        VK_FORMAT_R16_SFLOAT, VK_FORMAT_R32_SFLOAT,
        VK_FORMAT_R8_UNORM, VK_FORMAT_R8G8_UNORM,
        VK_FORMAT_A2R10G10B10_UNORM_PACK32, VK_FORMAT_B10G11R11_UFLOAT_PACK32,
        VK_FORMAT_D16_UNORM, VK_FORMAT_D24_UNORM_S8_UINT,
        VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT,
        VK_FORMAT_BC1_RGBA_UNORM_BLOCK, VK_FORMAT_BC3_UNORM_BLOCK,
        VK_FORMAT_BC5_UNORM_BLOCK, VK_FORMAT_BC7_UNORM_BLOCK,
        VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK, VK_FORMAT_ASTC_4x4_UNORM_BLOCK
    };
    bool fmtOk = vk.vkGetPhysicalDeviceFormatProperties != nullptr;
    uint32_t printedFmts = 0;
    for (VkFormat f : keyFormats) {
        VkFormatProperties fp{};
        if (fmtOk) {
            vk.vkGetPhysicalDeviceFormatProperties(device, f, &fp);
        }
        if (printedFmts++) j << ",";
        j << "{\"format\":\"" << stringifyFormat(f) << "\","
          << "\"linear\":" << fp.linearTilingFeatures << ","
          << "\"optimal\":" << fp.optimalTilingFeatures << ","
          << "\"buffer\":" << fp.bufferFeatures << "}";
    }
    j << "]";

    j << "}";
    return j.str();
}

// ── 通用采集主体（解析 instance 级函数后枚举设备）──────────────────────────
static std::string collectDevices(VulkanDispatch& vk, VkInstance instance) {
    if (!vk.vkEnumeratePhysicalDevices) {
        return "{\"success\":false,\"errorCode\":\"ENUM_DEVICES_MISSING\",\"errorMessage\":\"vkEnumeratePhysicalDevices not resolved\",\"deviceCount\":0,\"devices\":[]}";
    }
    uint32_t deviceCount = 0;
    vk.vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);

    std::ostringstream out;
    out << "{\"success\":true,\"errorCode\":null,\"errorMessage\":null,\"deviceCount\":"
        << deviceCount << ",\"devices\":[";

    if (deviceCount > 0) {
        std::vector<VkPhysicalDevice> devices(deviceCount);
        vk.vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data());

        for (uint32_t i = 0; i < deviceCount; i++) {
            if (i) out << ",";
            out << collectDevice(vk, devices[i]);
        }
    }

    out << "]}";
    return out.str();
}

static VkInstance createVulkanInstance(PFN_CreateInstance createInstanceFn) {
    const char* enabledExtensions[] = { VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME };
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "EmuHub-CN";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "EmuBoxVulkanCollector";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;
    createInfo.enabledExtensionCount = 1;
    createInfo.ppEnabledExtensionNames = enabledExtensions;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult result = createInstanceFn(&createInfo, nullptr, &instance);
    if (result != VK_SUCCESS) {
        char errMsg[64];
        snprintf(errMsg, sizeof(errMsg), "vkCreateInstance failed: %d", result);
        LOGE("%s", errMsg);
        return VK_NULL_HANDLE;
    }
    return instance;
}

// ── 系统 Vulkan 采集（直接链接 libvulkan.so，用 loader 全局函数）───────────
// Android 的 libvulkan.so 一定导出 vkCreateInstance/vkGetInstanceProcAddr。
// 之前用 dlopen+dlsym 间接解析，部分 ROM 上 vkGetInstanceProcAddr 对
// VK_NULL_HANDLE+"vkCreateInstance" 返回 null，导致采集失败，故改为直连。
static std::string collectWithSystemVulkan() {
    VulkanDispatch vk;

    VkInstance instance = createVulkanInstance(vkCreateInstance);
    if (instance == VK_NULL_HANDLE) {
        return "{\"success\":false,\"errorCode\":\"CREATE_INSTANCE_FAILED\",\"errorMessage\":\"vkCreateInstance failed\",\"deviceCount\":0,\"devices\":[]}";
    }

    // 通过全局 vkGetInstanceProcAddr 解析 instance 级函数
    vk.vkEnumeratePhysicalDevices = reinterpret_cast<PFN_EnumeratePhysicalDevices>(
        vkGetInstanceProcAddr(instance, "vkEnumeratePhysicalDevices"));
    vk.vkGetPhysicalDeviceProperties = reinterpret_cast<PFN_GetPhysicalDeviceProperties>(
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties"));
    vk.vkGetPhysicalDeviceFeatures = reinterpret_cast<PFN_GetPhysicalDeviceFeatures>(
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures"));
    vk.vkEnumerateDeviceExtensionProperties = reinterpret_cast<PFN_EnumerateDeviceExtensionProperties>(
        vkGetInstanceProcAddr(instance, "vkEnumerateDeviceExtensionProperties"));
    vk.vkGetPhysicalDeviceMemoryProperties = reinterpret_cast<PFN_GetPhysicalDeviceMemoryProperties>(
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceMemoryProperties"));
    vk.vkGetPhysicalDeviceQueueFamilyProperties = reinterpret_cast<PFN_GetPhysicalDeviceQueueFamilyProperties>(
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceQueueFamilyProperties"));
    vk.vkGetPhysicalDeviceProperties2 = reinterpret_cast<PFN_GetPhysicalDeviceProperties2>(
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties2"));
    vk.vkGetPhysicalDeviceFeatures2 = reinterpret_cast<PFN_GetPhysicalDeviceFeatures2>(
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures2"));
    vk.vkGetPhysicalDeviceFormatProperties = reinterpret_cast<PFN_GetPhysicalDeviceFormatProperties>(
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceFormatProperties"));

    std::string json = collectDevices(vk, instance);
    vkDestroyInstance(instance, nullptr);
    return json;
}

// ── 自定义驱动采集（dlopen 驱动 .so，turnip/panvk 导出 vkGetInstanceProcAddr）
// 只在 fork 子进程中调用（见 JNI 入口），驱动崩溃不会带崩 App。
static std::string collectWithDriver(const char* driverPath) {
    VulkanDispatch vk;
    vk.libHandle = dlopen(driverPath, RTLD_NOW | RTLD_LOCAL);
    if (!vk.libHandle) {
        std::string err = dlerror() ? dlerror() : "unknown";
        LOGE("dlopen(%s) failed: %s", driverPath, err.c_str());
        return "{\"success\":false,\"errorCode\":\"DL_OPEN_FAILED\",\"errorMessage\":\""
               + jsonEscape(err) + "\",\"deviceCount\":0,\"devices\":[]}";
    }

    auto getInstanceProcAddr = reinterpret_cast<GetInstanceProcAddrFn>(
        dlsym(vk.libHandle, "vkGetInstanceProcAddr"));
    if (!getInstanceProcAddr) {
        dlclose(vk.libHandle);
        return "{\"success\":false,\"errorCode\":\"NO_VK_ENTRY\",\"errorMessage\":\"vkGetInstanceProcAddr not found in driver\",\"deviceCount\":0,\"devices\":[]}";
    }

    vk.vkCreateInstance = reinterpret_cast<PFN_CreateInstance>(
        getInstanceProcAddr(VK_NULL_HANDLE, "vkCreateInstance"));
    vk.vkDestroyInstance = reinterpret_cast<PFN_DestroyInstance>(
        getInstanceProcAddr(VK_NULL_HANDLE, "vkDestroyInstance"));
    if (!vk.vkCreateInstance || !vk.vkDestroyInstance) {
        dlclose(vk.libHandle);
        return "{\"success\":false,\"errorCode\":\"INSTANCE_FUNCS_MISSING\",\"errorMessage\":\"driver lacks vkCreateInstance/vkDestroyInstance\",\"deviceCount\":0,\"devices\":[]}";
    }

    VkInstance instance = createVulkanInstance(vk.vkCreateInstance);
    if (instance == VK_NULL_HANDLE) {
        dlclose(vk.libHandle);
        return "{\"success\":false,\"errorCode\":\"CREATE_INSTANCE_FAILED\",\"errorMessage\":\"driver vkCreateInstance failed\",\"deviceCount\":0,\"devices\":[]}";
    }

    vk.vkEnumeratePhysicalDevices = reinterpret_cast<PFN_EnumeratePhysicalDevices>(
        getInstanceProcAddr(instance, "vkEnumeratePhysicalDevices"));
    vk.vkGetPhysicalDeviceProperties = reinterpret_cast<PFN_GetPhysicalDeviceProperties>(
        getInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties"));
    vk.vkGetPhysicalDeviceFeatures = reinterpret_cast<PFN_GetPhysicalDeviceFeatures>(
        getInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures"));
    vk.vkEnumerateDeviceExtensionProperties = reinterpret_cast<PFN_EnumerateDeviceExtensionProperties>(
        getInstanceProcAddr(instance, "vkEnumerateDeviceExtensionProperties"));
    vk.vkGetPhysicalDeviceMemoryProperties = reinterpret_cast<PFN_GetPhysicalDeviceMemoryProperties>(
        getInstanceProcAddr(instance, "vkGetPhysicalDeviceMemoryProperties"));
    vk.vkGetPhysicalDeviceQueueFamilyProperties = reinterpret_cast<PFN_GetPhysicalDeviceQueueFamilyProperties>(
        getInstanceProcAddr(instance, "vkGetPhysicalDeviceQueueFamilyProperties"));
    vk.vkGetPhysicalDeviceProperties2 = reinterpret_cast<PFN_GetPhysicalDeviceProperties2>(
        getInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties2"));
    vk.vkGetPhysicalDeviceFeatures2 = reinterpret_cast<PFN_GetPhysicalDeviceFeatures2>(
        getInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures2"));
    vk.vkGetPhysicalDeviceFormatProperties = reinterpret_cast<PFN_GetPhysicalDeviceFormatProperties>(
        getInstanceProcAddr(instance, "vkGetPhysicalDeviceFormatProperties"));

    std::string json = collectDevices(vk, instance);
    vk.vkDestroyInstance(instance, nullptr);
    dlclose(vk.libHandle);
    return json;
}

// ── fork 子进程采集驱动：崩溃只死子进程，父进程安全返回错误 JSON ─────────
static std::string collectDriverIsolated(const char* driverPath) {
    int pipefd[2];
    if (pipe(pipefd) != 0) {
        return "{\"success\":false,\"errorCode\":\"PIPE_FAILED\",\"errorMessage\":\"pipe() failed\",\"deviceCount\":0,\"devices\":[]}";
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(pipefd[0]);
        close(pipefd[1]);
        return "{\"success\":false,\"errorCode\":\"FORK_FAILED\",\"errorMessage\":\"fork() failed\",\"deviceCount\":0,\"devices\":[]}";
    }

    if (pid == 0) {
        // 子进程：执行采集，结果写入管道后退出。崩溃（SIGSEGV 等）只终止子进程。
        close(pipefd[0]);
        std::string json;
        try {
            json = collectWithDriver(driverPath);
        } catch (const std::exception& e) {
            json = std::string("{\"success\":false,\"errorCode\":\"NATIVE_EXCEPTION\",\"errorMessage\":\"")
                   + jsonEscape(e.what()) + "\",\"deviceCount\":0,\"devices\":[]}";
        } catch (...) {
            json = "{\"success\":false,\"errorCode\":\"UNKNOWN_NATIVE_ERROR\",\"errorMessage\":\"unknown native exception\",\"deviceCount\":0,\"devices\":[]}";
        }
        const char* data = json.c_str();
        size_t len = json.size();
        size_t written = 0;
        while (written < len) {
            ssize_t n = write(pipefd[1], data + written, len - written);
            if (n <= 0) break;
            written += static_cast<size_t>(n);
        }
        close(pipefd[1]);
        _exit(0);
    }

    // 父进程：带超时读管道
    close(pipefd[1]);
    std::string result;
    char buf[8192];
    struct pollfd pfd;
    pfd.fd = pipefd[0];
    pfd.events = POLLIN;
    const int timeoutMs = 15000;

    while (true) {
        int r = poll(&pfd, 1, timeoutMs);
        if (r <= 0) break;  // 超时或错误
        ssize_t n = read(pipefd[0], buf, sizeof(buf));
        if (n <= 0) break;  // EOF（子进程已退出/崩溃）或错误
        result.append(buf, static_cast<size_t>(n));
        // 子进程可能已写完，检查是否可回收
        int status = 0;
        pid_t w = waitpid(pid, &status, WNOHANG);
        if (w == pid) break;
    }

    // 回收子进程（若仍存活则杀掉，防止僵尸）
    int status = 0;
    pid_t w = waitpid(pid, &status, WNOHANG);
    if (w == 0) {
        kill(pid, SIGKILL);
        waitpid(pid, &status, 0);
    }
    close(pipefd[0]);

    if (result.empty()) {
        return "{\"success\":false,\"errorCode\":\"DRIVER_CRASHED\",\"errorMessage\":\"驱动加载后进程崩溃或超时——App 进程提交 GPU 负载受环境限制（SELinux/容器），不代表驱动不兼容；采集到的设备/扩展/属性已可判断可用性，性能请在模拟器内验证\",\"deviceCount\":0,\"devices\":[]}";
    }
    // 校验 JSON 完整性：必须以 { 开头、} 结尾
    if (result.front() != '{' || result.back() != '}') {
        return "{\"success\":false,\"errorCode\":\"PARTIAL_JSON\",\"errorMessage\":\"驱动进程返回不完整数据\",\"deviceCount\":0,\"devices\":[]}";
    }
    return result;
}

// ── JNI 入口 ────────────────────────────────────────────────────────────────
// Kotlin: com.emubox.app.NativeVulkanBridge.collectVulkanInfo(String driverPath)
// driverPath 为 null/空时采集系统 Vulkan（直连 loader，主进程安全）；
// 传入驱动 .so 路径时 fork 子进程采集（防 native 崩溃带崩 App）。
extern "C" JNIEXPORT jstring JNICALL
Java_com_emubox_app_NativeVulkanBridge_collectVulkanInfo(JNIEnv* env, jobject /* thiz */, jstring driverPath) {
    const char* path = nullptr;
    if (driverPath) {
        path = env->GetStringUTFChars(driverPath, nullptr);
    }

    std::string json;
    if (path == nullptr || path[0] == '\0') {
        try {
            json = collectWithSystemVulkan();
        } catch (const std::exception& e) {
            json = std::string("{\"success\":false,\"errorCode\":\"NATIVE_EXCEPTION\",\"errorMessage\":\"")
                   + jsonEscape(e.what()) + "\",\"deviceCount\":0,\"devices\":[]}";
        } catch (...) {
            json = "{\"success\":false,\"errorCode\":\"UNKNOWN_NATIVE_ERROR\",\"errorMessage\":\"unknown native exception\",\"deviceCount\":0,\"devices\":[]}";
        }
    } else {
        json = collectDriverIsolated(path);
    }

    if (driverPath && path) {
        env->ReleaseStringUTFChars(driverPath, path);
    }

    return env->NewStringUTF(json.c_str());
}

// ═══════════════════════════════════════════════════════════════════════════
// GPU 基准测试引擎：fill bandwidth + copy bandwidth，timestamp query 精确计时
// 修改原因：用户要求实测跑分，对比不同驱动在当前设备上的性能差异。
// 影响范围：新增 JNI 入口 benchmarkVulkan，不改动现有采集逻辑。
// 回滚方法：删除本段代码及 Kotlin 侧 benchmark 调用即可。
// ═══════════════════════════════════════════════════════════════════════════

// ── 计算基准 shader（SPIR-V 1.0，本地构造并逐指令校验）────────────────────
// GLSL 源：layout(local_size_x=256) in; layout(set=0,binding=0) buffer Data { float d[]; };
//          void main(){ uint i = gl_GlobalInvocationID.x; d[i] = d[i]*2.0f + 1.0f; }
// 每个线程 2 flops（FMul+FAdd）；32MB buffer = 8M float = 1678 万 flops/次 dispatch。
static const uint32_t kComputeShaderSpv[] = {
    0x07230203, 0x00010000, 0x00000000, 0x0000001b, 0x00000000, 0x00020011,
    0x00000001, 0x0003000e, 0x00000000, 0x00000001, 0x0005000f, 0x00000005,
    0x0000000b, 0x6e69616d, 0x0000000c, 0x00060010, 0x0000000b, 0x00000011,
    0x00000100, 0x00000001, 0x00000001, 0x0004002c, 0x0000000c, 0x0000000b,
    0x0000001c, 0x0004002c, 0x00000007, 0x00000006, 0x00000004, 0x0003002c,
    0x00000008, 0x00000002, 0x0004002c, 0x0000000a, 0x00000020, 0x00000000,
    0x0004002c, 0x0000000a, 0x0000001f, 0x00000000, 0x00020013, 0x00000001,
    0x00030021, 0x00000002, 0x00000001, 0x00030015, 0x00000003, 0x00000020,
    0x00040014, 0x00000004, 0x00000020, 0x00000000, 0x0004002a, 0x00000004,
    0x00000005, 0x00000100, 0x0004002a, 0x00000004, 0x00000006, 0x00000001,
    0x0004002a, 0x00000004, 0x00000011, 0x00000000, 0x0004002a, 0x00000003,
    0x00000014, 0x40000000, 0x0004002a, 0x00000003, 0x00000016, 0x3f800000,
    0x00040017, 0x00000017, 0x00000004, 0x00000003, 0x00030024, 0x00000007,
    0x00000003, 0x00030022, 0x00000008, 0x00000007, 0x00040020, 0x00000009,
    0x00000002, 0x00000008, 0x00040020, 0x0000000d, 0x00000002, 0x00000003,
    0x00040020, 0x00000018, 0x00000001, 0x00000017, 0x0004003b, 0x00000009,
    0x0000000a, 0x00000002, 0x0004003b, 0x00000018, 0x0000000c, 0x00000001,
    0x00050026, 0x00000001, 0x0000000e, 0x00000000, 0x00000002, 0x00020027,
    0x0000000f, 0x0004002f, 0x00000018, 0x00000019, 0x0000000c, 0x00050039,
    0x00000004, 0x0000001a, 0x00000019, 0x00000000, 0x00050033, 0x0000000d,
    0x00000010, 0x0000000a, 0x0000001a, 0x0004002f, 0x00000003, 0x00000012,
    0x00000010, 0x0005004d, 0x00000003, 0x00000013, 0x00000012, 0x00000014,
    0x0005004a, 0x00000003, 0x00000015, 0x00000013, 0x00000016, 0x00030030,
    0x00000010, 0x00000015, 0x00010029, 0x00010028,
};
static const size_t kComputeShaderSpvBytes = sizeof(kComputeShaderSpv);

struct BenchmarkDispatch {
    PFN_vkCreateDevice vkCreateDevice = nullptr;
    PFN_vkDestroyDevice vkDestroyDevice = nullptr;
    PFN_vkGetDeviceQueue vkGetDeviceQueue = nullptr;
    PFN_vkCreateCommandPool vkCreateCommandPool = nullptr;
    PFN_vkDestroyCommandPool vkDestroyCommandPool = nullptr;
    PFN_vkAllocateCommandBuffers vkAllocateCommandBuffers = nullptr;
    PFN_vkFreeCommandBuffers vkFreeCommandBuffers = nullptr;
    PFN_vkBeginCommandBuffer vkBeginCommandBuffer = nullptr;
    PFN_vkEndCommandBuffer vkEndCommandBuffer = nullptr;
    PFN_vkResetCommandBuffer vkResetCommandBuffer = nullptr;
    PFN_vkCmdFillBuffer vkCmdFillBuffer = nullptr;
    PFN_vkCmdCopyBuffer vkCmdCopyBuffer = nullptr;
    PFN_vkQueueSubmit vkQueueSubmit = nullptr;
    PFN_vkQueueWaitIdle vkQueueWaitIdle = nullptr;
    PFN_vkCreateBuffer vkCreateBuffer = nullptr;
    PFN_vkDestroyBuffer vkDestroyBuffer = nullptr;
    PFN_vkGetBufferMemoryRequirements vkGetBufferMemoryRequirements = nullptr;
    PFN_vkAllocateMemory vkAllocateMemory = nullptr;
    PFN_vkFreeMemory vkFreeMemory = nullptr;
    PFN_vkBindBufferMemory vkBindBufferMemory = nullptr;
    PFN_vkCreateQueryPool vkCreateQueryPool = nullptr;
    PFN_vkDestroyQueryPool vkDestroyQueryPool = nullptr;
    PFN_vkCmdResetQueryPool vkCmdResetQueryPool = nullptr;
    PFN_vkCmdWriteTimestamp vkCmdWriteTimestamp = nullptr;
    PFN_vkGetQueryPoolResults vkGetQueryPoolResults = nullptr;
    PFN_vkGetDeviceProcAddr vkGetDeviceProcAddr = nullptr;
    // ── compute 基准专用 ──
    PFN_vkCreateShaderModule vkCreateShaderModule = nullptr;
    PFN_vkDestroyShaderModule vkDestroyShaderModule = nullptr;
    PFN_vkCreateDescriptorSetLayout vkCreateDescriptorSetLayout = nullptr;
    PFN_vkDestroyDescriptorSetLayout vkDestroyDescriptorSetLayout = nullptr;
    PFN_vkCreateDescriptorPool vkCreateDescriptorPool = nullptr;
    PFN_vkDestroyDescriptorPool vkDestroyDescriptorPool = nullptr;
    PFN_vkAllocateDescriptorSets vkAllocateDescriptorSets = nullptr;
    PFN_vkUpdateDescriptorSets vkUpdateDescriptorSets = nullptr;
    PFN_vkCreatePipelineLayout vkCreatePipelineLayout = nullptr;
    PFN_vkDestroyPipelineLayout vkDestroyPipelineLayout = nullptr;
    PFN_vkCreateComputePipelines vkCreateComputePipelines = nullptr;
    PFN_vkDestroyPipeline vkDestroyPipeline = nullptr;
    PFN_vkCmdBindPipeline vkCmdBindPipeline = nullptr;
    PFN_vkCmdBindDescriptorSets vkCmdBindDescriptorSets = nullptr;
    PFN_vkCmdDispatch vkCmdDispatch = nullptr;
};

// 阶段 1：加载 instance 级入口（创建逻辑设备前必须有 vkCreateDevice/vkGetDeviceProcAddr）
// resolver：系统跑分传 nullptr（用全局 vkGetInstanceProcAddr）；驱动跑分传驱动导出的
// vkGetInstanceProcAddr，否则驱动创建的 instance 无法解析 device 级入口。
static bool loadInstanceDispatch(VkInstance instance, BenchmarkDispatch& bk,
                                 PFN_vkGetInstanceProcAddr resolver) {
    auto getInst = resolver ? resolver : reinterpret_cast<PFN_vkGetInstanceProcAddr>(
        reinterpret_cast<void*>(vkGetInstanceProcAddr));
    if (!getInst) return false;

    bk.vkCreateDevice = reinterpret_cast<PFN_vkCreateDevice>(
        getInst(instance, "vkCreateDevice"));
    bk.vkGetDeviceProcAddr = reinterpret_cast<PFN_vkGetDeviceProcAddr>(
        getInst(instance, "vkGetDeviceProcAddr"));
    return bk.vkCreateDevice != nullptr && bk.vkGetDeviceProcAddr != nullptr;
}

// 阶段 2：device 创建后加载 device 级函数
// device 级函数必须通过 vkGetDeviceProcAddr 获取，
// vkGetInstanceProcAddr 对 device 级函数不保证返回（Android loader 会返回 NULL）。
static bool loadDeviceDispatch(VkDevice device, BenchmarkDispatch& bk) {
    auto getDev = bk.vkGetDeviceProcAddr;
    if (!getDev) return false;

#define LOAD_DEV(name) bk.name = reinterpret_cast<PFN_##name>(getDev(device, #name))
    LOAD_DEV(vkDestroyDevice);
    LOAD_DEV(vkGetDeviceQueue);
    LOAD_DEV(vkCreateCommandPool);
    LOAD_DEV(vkDestroyCommandPool);
    LOAD_DEV(vkAllocateCommandBuffers);
    LOAD_DEV(vkFreeCommandBuffers);
    LOAD_DEV(vkBeginCommandBuffer);
    LOAD_DEV(vkEndCommandBuffer);
    LOAD_DEV(vkResetCommandBuffer);
    LOAD_DEV(vkCmdFillBuffer);
    LOAD_DEV(vkCmdCopyBuffer);
    LOAD_DEV(vkQueueSubmit);
    LOAD_DEV(vkQueueWaitIdle);
    LOAD_DEV(vkCreateBuffer);
    LOAD_DEV(vkDestroyBuffer);
    LOAD_DEV(vkGetBufferMemoryRequirements);
    LOAD_DEV(vkAllocateMemory);
    LOAD_DEV(vkFreeMemory);
    LOAD_DEV(vkBindBufferMemory);
    LOAD_DEV(vkCreateQueryPool);
    LOAD_DEV(vkDestroyQueryPool);
    LOAD_DEV(vkCmdResetQueryPool);
    LOAD_DEV(vkCmdWriteTimestamp);
    LOAD_DEV(vkGetQueryPoolResults);
    LOAD_DEV(vkCreateShaderModule);
    LOAD_DEV(vkDestroyShaderModule);
    LOAD_DEV(vkCreateDescriptorSetLayout);
    LOAD_DEV(vkDestroyDescriptorSetLayout);
    LOAD_DEV(vkCreateDescriptorPool);
    LOAD_DEV(vkDestroyDescriptorPool);
    LOAD_DEV(vkAllocateDescriptorSets);
    LOAD_DEV(vkUpdateDescriptorSets);
    LOAD_DEV(vkCreatePipelineLayout);
    LOAD_DEV(vkDestroyPipelineLayout);
    LOAD_DEV(vkCreateComputePipelines);
    LOAD_DEV(vkDestroyPipeline);
    LOAD_DEV(vkCmdBindPipeline);
    LOAD_DEV(vkCmdBindDescriptorSets);
    LOAD_DEV(vkCmdDispatch);
#undef LOAD_DEV

    return bk.vkCreateDevice && bk.vkDestroyDevice && bk.vkGetDeviceQueue &&
           bk.vkCreateCommandPool && bk.vkAllocateCommandBuffers &&
           bk.vkBeginCommandBuffer && bk.vkEndCommandBuffer &&
           bk.vkCmdFillBuffer && bk.vkCmdCopyBuffer &&
           bk.vkQueueSubmit && bk.vkQueueWaitIdle &&
           bk.vkCreateBuffer && bk.vkAllocateMemory && bk.vkBindBufferMemory &&
           bk.vkCreateQueryPool && bk.vkCmdWriteTimestamp && bk.vkGetQueryPoolResults;
}

static int findGraphicsQueueFamily(const VulkanDispatch& vk, VkPhysicalDevice device) {
    uint32_t count = 0;
    vk.vkGetPhysicalDeviceQueueFamilyProperties(device, &count, nullptr);
    if (count == 0) return -1;
    std::vector<VkQueueFamilyProperties> props(count);
    vk.vkGetPhysicalDeviceQueueFamilyProperties(device, &count, props.data());
    for (uint32_t i = 0; i < count; i++) {
        if (props[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) return static_cast<int>(i);
    }
    return -1;
}

static int findDeviceLocalMemoryType(const VulkanDispatch& vk, VkPhysicalDevice device, uint32_t typeBits) {
    VkPhysicalDeviceMemoryProperties memProps{};
    vk.vkGetPhysicalDeviceMemoryProperties(device, &memProps);
    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        if ((typeBits & (1u << i)) &&
            (memProps.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
            return static_cast<int>(i);
        }
    }
    // 兜底：任意匹配的 type
    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        if (typeBits & (1u << i)) return static_cast<int>(i);
    }
    return -1;
}

static std::string runBenchmark(const VulkanDispatch& vk, VkInstance instance, VkPhysicalDevice device,
                                PFN_vkGetInstanceProcAddr resolver = nullptr, bool enableCompute = false) {
    BenchmarkDispatch bk{};
    VkDevice logicalDevice = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool cmdPool = VK_NULL_HANDLE;
    VkCommandBuffer cmdBuf = VK_NULL_HANDLE;
    VkBuffer bufA = VK_NULL_HANDLE, bufB = VK_NULL_HANDLE;
    VkDeviceMemory memA = VK_NULL_HANDLE, memB = VK_NULL_HANDLE;
    VkQueryPool queryPool = VK_NULL_HANDLE;
    // compute 基准资源
    VkShaderModule shaderModule = VK_NULL_HANDLE;
    VkDescriptorSetLayout dsLayout = VK_NULL_HANDLE;
    VkDescriptorPool dsPool = VK_NULL_HANDLE;
    VkDescriptorSet ds = VK_NULL_HANDLE;
    VkPipelineLayout pipeLayout = VK_NULL_HANDLE;
    VkPipeline computePipeline = VK_NULL_HANDLE;

    auto cleanup = [&]() {
        // 函数指针可能未加载成功（loadBenchmarkDispatch 失败时），必须判空防崩溃
        if (bk.vkFreeCommandBuffers && cmdBuf && cmdPool) bk.vkFreeCommandBuffers(logicalDevice, cmdPool, 1, &cmdBuf);
        if (bk.vkDestroyCommandPool && cmdPool) bk.vkDestroyCommandPool(logicalDevice, cmdPool, nullptr);
        if (bk.vkDestroyBuffer && bufA) bk.vkDestroyBuffer(logicalDevice, bufA, nullptr);
        if (bk.vkDestroyBuffer && bufB) bk.vkDestroyBuffer(logicalDevice, bufB, nullptr);
        if (bk.vkFreeMemory && memA) bk.vkFreeMemory(logicalDevice, memA, nullptr);
        if (bk.vkFreeMemory && memB) bk.vkFreeMemory(logicalDevice, memB, nullptr);
        if (bk.vkDestroyQueryPool && queryPool) bk.vkDestroyQueryPool(logicalDevice, queryPool, nullptr);
        if (bk.vkDestroyPipeline && computePipeline) bk.vkDestroyPipeline(logicalDevice, computePipeline, nullptr);
        if (bk.vkDestroyPipelineLayout && pipeLayout) bk.vkDestroyPipelineLayout(logicalDevice, pipeLayout, nullptr);
        if (bk.vkDestroyDescriptorPool && dsPool) bk.vkDestroyDescriptorPool(logicalDevice, dsPool, nullptr);
        if (bk.vkDestroyDescriptorSetLayout && dsLayout) bk.vkDestroyDescriptorSetLayout(logicalDevice, dsLayout, nullptr);
        if (bk.vkDestroyShaderModule && shaderModule) bk.vkDestroyShaderModule(logicalDevice, shaderModule, nullptr);
        if (bk.vkDestroyDevice && logicalDevice) bk.vkDestroyDevice(logicalDevice, nullptr);
    };

    auto fail = [&](const char* code, const char* msg) -> std::string {
        cleanup();
        std::ostringstream j;
        j << "{\"success\":false,\"errorCode\":\"" << code << "\",\"errorMessage\":\""
          << jsonEscape(msg) << "\",\"fillBandwidthGBs\":0,\"copyBandwidthGBs\":0,"
          << "\"computeGFLOPS\":0,\"computeError\":\"\",\"totalScore\":0}";
        return j.str();
    };

    // 1. 找 graphics queue family
    int qf = findGraphicsQueueFamily(vk, device);
    if (qf < 0) return fail("NO_GRAPHICS_QUEUE", "设备无 graphics queue");

    // 2. 检查 timestamp 支持
    VkPhysicalDeviceProperties props{};
    vk.vkGetPhysicalDeviceProperties(device, &props);
    if (!props.limits.timestampComputeAndGraphics) {
        return fail("TIMESTAMP_UNSUPPORTED", "设备不支持 timestamp query");
    }
    float timestampPeriod = props.limits.timestampPeriod; // ns

    // 3. 创建逻辑设备（先加载 instance 级入口，再创建，再加载 device 级函数）
    if (!loadInstanceDispatch(instance, bk, resolver)) {
        return fail("LOAD_INSTANCE_DISPATCH_FAILED", "加载 instance 级函数失败");
    }

    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueCI{};
    queueCI.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueCI.queueFamilyIndex = static_cast<uint32_t>(qf);
    queueCI.queueCount = 1;
    queueCI.pQueuePriorities = &queuePriority;

    VkDeviceCreateInfo devCI{};
    devCI.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    devCI.queueCreateInfoCount = 1;
    devCI.pQueueCreateInfos = &queueCI;

    if (bk.vkCreateDevice(device, &devCI, nullptr, &logicalDevice) != VK_SUCCESS) {
        return fail("CREATE_DEVICE_FAILED", "创建逻辑设备失败");
    }

    if (!loadDeviceDispatch(logicalDevice, bk)) {
        return fail("LOAD_DEVICE_DISPATCH_FAILED", "加载 device 级函数失败");
    }

    bk.vkGetDeviceQueue(logicalDevice, static_cast<uint32_t>(qf), 0, &queue);

    // 4. command pool + buffer
    VkCommandPoolCreateInfo poolCI{};
    poolCI.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolCI.queueFamilyIndex = static_cast<uint32_t>(qf);
    poolCI.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    if (bk.vkCreateCommandPool(logicalDevice, &poolCI, nullptr, &cmdPool) != VK_SUCCESS) {
        return fail("CREATE_CMDPOOL_FAILED", "创建 command pool 失败");
    }

    VkCommandBufferAllocateInfo allocCI{};
    allocCI.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocCI.commandPool = cmdPool;
    allocCI.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocCI.commandBufferCount = 1;
    if (bk.vkAllocateCommandBuffers(logicalDevice, &allocCI, &cmdBuf) != VK_SUCCESS) {
        return fail("ALLOC_CMDBUF_FAILED", "分配 command buffer 失败");
    }

    // 5. timestamp query pool（2 个 query：start + end）
    VkQueryPoolCreateInfo queryCI{};
    queryCI.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    queryCI.queryType = VK_QUERY_TYPE_TIMESTAMP;
    queryCI.queryCount = 2;
    if (bk.vkCreateQueryPool(logicalDevice, &queryCI, nullptr, &queryPool) != VK_SUCCESS) {
        return fail("CREATE_QUERYPOOL_FAILED", "创建 query pool 失败");
    }

    // 6. 分配 2 个 32MB device-local buffer
    const VkDeviceSize BUF_SIZE = 32ull * 1024 * 1024; // 32 MB
    auto createBuffer = [&](VkBuffer& buf, VkDeviceMemory& mem) -> bool {
        VkBufferCreateInfo bufCI{};
        bufCI.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufCI.size = BUF_SIZE;
        bufCI.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT |
                      VK_BUFFER_USAGE_STORAGE_BUFFER_BIT; // compute 基准需要 storage buffer
        bufCI.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (bk.vkCreateBuffer(logicalDevice, &bufCI, nullptr, &buf) != VK_SUCCESS) return false;

        VkMemoryRequirements memReq{};
        bk.vkGetBufferMemoryRequirements(logicalDevice, buf, &memReq);
        int memType = findDeviceLocalMemoryType(vk, device, memReq.memoryTypeBits);
        if (memType < 0) return false;

        VkMemoryAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocInfo.allocationSize = memReq.size;
        allocInfo.memoryTypeIndex = static_cast<uint32_t>(memType);
        if (bk.vkAllocateMemory(logicalDevice, &allocInfo, nullptr, &mem) != VK_SUCCESS) return false;
        if (bk.vkBindBufferMemory(logicalDevice, buf, mem, 0) != VK_SUCCESS) return false;
        return true;
    };

    if (!createBuffer(bufA, memA)) return fail("CREATE_BUFFER_A_FAILED", "创建 buffer A 失败");
    if (!createBuffer(bufB, memB)) return fail("CREATE_BUFFER_B_FAILED", "创建 buffer B 失败");

    // 7. 辅助：录制 + 提交 + 取时间戳（失败时设置 stageError 并返回负值）
    std::string stageError;
    auto runTimed = [&](int iterations, std::function<void()> recordCmds) -> double {
        bk.vkResetCommandBuffer(cmdBuf, 0);
        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        bk.vkBeginCommandBuffer(cmdBuf, &beginInfo);

        bk.vkCmdResetQueryPool(cmdBuf, queryPool, 0, 2);
        bk.vkCmdWriteTimestamp(cmdBuf, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, 0);

        recordCmds();

        bk.vkCmdWriteTimestamp(cmdBuf, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, 1);
        bk.vkEndCommandBuffer(cmdBuf);

        VkSubmitInfo submitInfo{};
        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &cmdBuf;
        VkResult submitRes = bk.vkQueueSubmit(queue, 1, &submitInfo, VK_NULL_HANDLE);
        if (submitRes != VK_SUCCESS) {
            stageError = "QUEUE_SUBMIT_FAILED_" + std::to_string(submitRes);
            return -1.0;
        }
        bk.vkQueueWaitIdle(queue);

        uint64_t timestamps[2] = {0, 0};
        VkResult qRes = bk.vkGetQueryPoolResults(logicalDevice, queryPool, 0, 2,
                                  sizeof(timestamps), timestamps, sizeof(uint64_t),
                                  VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
        if (qRes != VK_SUCCESS || timestamps[1] <= timestamps[0]) {
            // 时间戳查询失败或差值为 0：继续算带宽会产生 inf/NaN，导致输出非法 JSON
            stageError = "TIMESTAMP_QUERY_FAILED_" + std::to_string(qRes);
            return -1.0;
        }
        double deltaNs = static_cast<double>(timestamps[1] - timestamps[0]) * timestampPeriod;
        return deltaNs;
    };

    // 8. 测试1：fill bandwidth（填充 32MB × 50 次）
    const int FILL_ITERS = 30;
    double fillNs = runTimed(FILL_ITERS, [&]() {
        for (int i = 0; i < FILL_ITERS; i++) {
            bk.vkCmdFillBuffer(cmdBuf, bufA, 0, VK_WHOLE_SIZE, 0x41414141);
        }
    });
    if (fillNs <= 0.0) {
        cleanup();
        return fail(stageError.empty() ? "FILL_STAGE_FAILED" : stageError.c_str(), "fill stage failed");
    }
    double fillBytes = static_cast<double>(BUF_SIZE) * FILL_ITERS;
    double fillBandwidthGBs = (fillBytes / (fillNs / 1e9)) / (1024.0 * 1024.0 * 1024.0);

    // 9. 测试2：copy bandwidth（32MB × 50 次，A→B）
    const int COPY_ITERS = 30;
    double copyNs = runTimed(COPY_ITERS, [&]() {
        VkBufferCopy copyRegion{};
        copyRegion.size = BUF_SIZE;
        for (int i = 0; i < COPY_ITERS; i++) {
            bk.vkCmdCopyBuffer(cmdBuf, bufA, bufB, 1, &copyRegion);
        }
    });
    if (copyNs <= 0.0) {
        cleanup();
        return fail(stageError.empty() ? "COPY_STAGE_FAILED" : stageError.c_str(), "copy stage failed");
    }
    double copyBytes = static_cast<double>(BUF_SIZE) * COPY_ITERS;
    double copyBandwidthGBs = (copyBytes / (copyNs / 1e9)) / (1024.0 * 1024.0 * 1024.0);

    // ── 测试3：compute 吞吐（8M float × 2 flops × N dispatch，GFLOPS）────────
    // 运行时开关：系统跑分在主进程执行，部分 Android GPU 驱动（尤其 Adreno）
    // 在 vkCreateComputePipelines/dispatch 阶段会触发驱动级崩溃，直接带崩 App，
    // 因此系统跑分默认关闭 compute（只测 fill/copy 带宽）。驱动实测跑分跑在
    // fork 子进程隔离内（驱动自管 GPU 访问），compute 崩溃只死子进程，可开启。
    // compute 失败仅降级该指标，不使整个跑分失败。
    std::string computeError;
    double computeGFLOPS = 0.0;
    if (enableCompute) {
    {
        // shader module（字节数组本地构造并校验，见 kComputeShaderSpv）
        VkShaderModuleCreateInfo smCI{};
        smCI.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        smCI.codeSize = kComputeShaderSpvBytes;
        smCI.pCode = kComputeShaderSpv;
        if (bk.vkCreateShaderModule &&
            bk.vkCreateShaderModule(logicalDevice, &smCI, nullptr, &shaderModule) == VK_SUCCESS) {
            // descriptor set layout：set=0 binding=0 storage buffer（仅 float 数组）
            VkDescriptorSetLayoutBinding binding{};
            binding.binding = 0;
            binding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            binding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            binding.descriptorCount = 1;
            VkDescriptorSetLayoutCreateInfo dsLayoutCI{};
            dsLayoutCI.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
            dsLayoutCI.bindingCount = 1;
            dsLayoutCI.pBindings = &binding;
            if (bk.vkCreateDescriptorSetLayout &&
                bk.vkCreateDescriptorSetLayout(logicalDevice, &dsLayoutCI, nullptr, &dsLayout) == VK_SUCCESS) {
                // pipeline layout
                VkPipelineLayoutCreateInfo plCI{};
                plCI.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
                plCI.setLayoutCount = 1;
                plCI.pSetLayouts = &dsLayout;
                if (bk.vkCreatePipelineLayout &&
                    bk.vkCreatePipelineLayout(logicalDevice, &plCI, nullptr, &pipeLayout) == VK_SUCCESS) {
                    VkComputePipelineCreateInfo cpCI{};
                    cpCI.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
                    cpCI.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
                    cpCI.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
                    cpCI.stage.module = shaderModule;
                    cpCI.stage.pName = "main";
                    cpCI.layout = pipeLayout;
                    if (bk.vkCreateComputePipelines &&
                        bk.vkCreateComputePipelines(logicalDevice, VK_NULL_HANDLE, 1, &cpCI, nullptr,
                                                    &computePipeline) == VK_SUCCESS) {
                        // descriptor pool + set，绑定 bufA
                        VkDescriptorPoolSize poolSize{};
                        poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
                        poolSize.descriptorCount = 1;
                        VkDescriptorPoolCreateInfo dpCI{};
                        dpCI.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
                        dpCI.maxSets = 1;
                        dpCI.poolSizeCount = 1;
                        dpCI.pPoolSizes = &poolSize;
                        if (bk.vkCreateDescriptorPool &&
                            bk.vkCreateDescriptorPool(logicalDevice, &dpCI, nullptr, &dsPool) == VK_SUCCESS) {
                            VkDescriptorSetAllocateInfo dsAlloc{};
                            dsAlloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
                            dsAlloc.descriptorPool = dsPool;
                            dsAlloc.descriptorSetCount = 1;
                            dsAlloc.pSetLayouts = &dsLayout;
                            if (bk.vkAllocateDescriptorSets &&
                                bk.vkAllocateDescriptorSets(logicalDevice, &dsAlloc, &ds) == VK_SUCCESS) {
                                VkDescriptorBufferInfo bufInfo{};
                                bufInfo.buffer = bufA;
                                bufInfo.offset = 0;
                                bufInfo.range = BUF_SIZE;
                                VkWriteDescriptorSet write{};
                                write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                                write.dstSet = ds;
                                write.dstBinding = 0;
                                write.descriptorCount = 1;
                                write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
                                write.pBufferInfo = &bufInfo;
                                bk.vkUpdateDescriptorSets(logicalDevice, 1, &write, 0, nullptr);

                                // 计时：单 command buffer 内连续 N 次 dispatch（避免提交开销）
                                const uint32_t COMPUTE_DISPATCHES = 4000; // 8M×2 flops×4千 ≈ 671 亿 flops
                                double computeNs = runTimed(COMPUTE_DISPATCHES, [&]() {
                                    bk.vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, computePipeline);
                                    bk.vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                                                               pipeLayout, 0, 1, &ds, 0, nullptr);
                                    // 8M floats / 256 threads = 32768 groups
                                    for (uint32_t i = 0; i < COMPUTE_DISPATCHES; i++) {
                                        bk.vkCmdDispatch(cmdBuf, 32768, 1, 1);
                                    }
                                });
                                if (computeNs > 0.0) {
                                    double flops = static_cast<double>(BUF_SIZE / sizeof(float)) * 2.0 *
                                                   static_cast<double>(COMPUTE_DISPATCHES);
                                    computeGFLOPS = flops / (computeNs / 1e9) / 1e9;
                                    if (!std::isfinite(computeGFLOPS)) computeGFLOPS = 0.0;
                                } else {
                                    computeError = stageError.empty() ? "COMPUTE_TIMING_FAILED" : stageError;
                                }
                            } else {
                                computeError = "ALLOC_DESCRIPTOR_SET_FAILED";
                            }
                        } else {
                            computeError = "CREATE_DESCRIPTOR_POOL_FAILED";
                        }
                    } else {
                        computeError = "CREATE_COMPUTE_PIPELINE_FAILED";
                    }
                } else {
                    computeError = "CREATE_PIPELINE_LAYOUT_FAILED";
                }
            } else {
                computeError = "CREATE_DS_LAYOUT_FAILED";
            }
        } else {
            computeError = "CREATE_SHADER_MODULE_FAILED";
        }
    }
    } // enableCompute

    // 数值合法性兜底（防止 inf/NaN 进入 JSON）
    if (!std::isfinite(fillBandwidthGBs) || !std::isfinite(copyBandwidthGBs)) {
        cleanup();
        return "{\"success\":false,\"errorCode\":\"BANDWIDTH_NONFINITE\",\"errorMessage\":\"computed bandwidth is not finite\"}";
    }

    // ── CPU 基准（纯计算，无 GPU 依赖，主进程安全）────────────
    // 整数：64 位混合运算（mul/add/xor/shift），每线程 4M 次迭代；
    // 浮点：double 乘加混合，每线程 4M 次迭代；
    // 多线程分片（最多 8 线程），聚合计时换算 MIPS / MFLOPS。
    struct CpuResult { double intMIPS = 0.0; double floatMFLOPS = 0.0; int threads = 1; };
    auto runCpuBenchmark = []() -> CpuResult {
        const unsigned hw = std::thread::hardware_concurrency();
        const int threads = std::max(1u, std::min(hw, 8u));
        const long long ITERS = 4 * 1000 * 1000; // 每线程迭代数
        std::atomic<long long> intOps{0}, floatOps{0};
        std::atomic<bool> startFlag{false};
        std::atomic<int> ready{0};
        auto worker = [&](int tid) {
            // 先报告就绪，再等统一开始信号
            // （顺序必须如此：主线程等所有 worker 就绪后才发 startFlag，
            //   若先等信号再报告就绪会互相等待造成死锁）
            ready.fetch_add(1);
            while (!startFlag.load()) { std::this_thread::yield(); }
            // 每线程独立种子，避免共享变量竞争
            unsigned long long a = 0x9E3779B97F4A7C15ull + tid * 7919u;
            unsigned long long b = a * 31ull + 17ull;
            long long oi = 0;
            // 整数混合运算：6 条指令/迭代（mul, add, xor, shift, xor, add）
            for (long long i = 0; i < ITERS; i++) {
                a = a * 2862933555777941757ull + 3037000493ull;
                b = (b << 13) | (b >> 51);
                a ^= b;
                b += a;
                a = (a >> 7) ^ (a << 57);
                b ^= a;
                a += b;
                oi += 6;
            }
            intOps.fetch_add(oi, std::memory_order_relaxed);
            // 浮点：4 条 FP 运算/迭代（add, mul, fma 近似用 add+mul）
            volatile double x = 1.0000001 + tid * 1e-9, y = 0.9999999 + tid * 1e-9;
            double sum = 0.0;
            long long of = 0;
            for (long long i = 0; i < ITERS; i++) {
                x = x * y + 0.0000001;
                y = y * 0.99999999 + 0.0000001;
                sum += x * y;
                x += y;
                of += 4;
            }
            if (sum == -1.2345) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "cpu bench sum guard");
            floatOps.fetch_add(of, std::memory_order_relaxed);
        };
        std::vector<std::thread> pool;
        pool.reserve(threads);
        for (int t = 0; t < threads; t++) pool.emplace_back(worker, t);
        // 等所有 worker 就绪后统一开始（尽量减少错峰对总时长的影响）
        while (ready.load() < threads) { std::this_thread::yield(); }
        auto t0 = std::chrono::steady_clock::now();
        startFlag.store(true);
        for (auto& th : pool) th.join();
        auto t1 = std::chrono::steady_clock::now();
        double secs = std::chrono::duration<double>(t1 - t0).count();
        CpuResult r;
        r.threads = threads;
        if (secs > 0.0) {
            r.intMIPS = (double)intOps.load() / secs / 1e6;
            r.floatMFLOPS = (double)floatOps.load() / secs / 1e6;
        }
        return r;
    };
    CpuResult cpu = runCpuBenchmark();

    // 10. 总分：带宽综合（fill+copy 平均，20GB/s = 1000 分）
    //     v1.10.5 起 compute 停用，总分仅由带宽构成，避免 9xxx 分虚高。
    double avgBandwidth = (fillBandwidthGBs + copyBandwidthGBs) / 2.0;
    double scorePart1 = avgBandwidth / 20.0 * 1000.0;
    int totalScore = static_cast<int>(scorePart1);
    if (totalScore > 9999) totalScore = 9999;

    // 设备名
    char devName[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE];
    strncpy(devName, props.deviceName, VK_MAX_PHYSICAL_DEVICE_NAME_SIZE - 1);
    devName[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE - 1] = '\0';

    cleanup();

    std::ostringstream j;
    j << "{\"success\":true,"
      << "\"deviceName\":\"" << jsonEscape(devName) << "\","
      << "\"fillBandwidthGBs\":" << std::fixed << std::setprecision(2) << fillBandwidthGBs << ","
      << "\"copyBandwidthGBs\":" << copyBandwidthGBs << ","
      << "\"computeGFLOPS\":" << std::setprecision(2) << computeGFLOPS << ","
      << "\"computeError\":\"" << jsonEscape(computeError) << "\","
      << "\"cpuIntMIPS\":" << std::setprecision(0) << cpu.intMIPS << ","
      << "\"cpuFloatMFLOPS\":" << std::setprecision(0) << cpu.floatMFLOPS << ","
      << "\"cpuThreads\":" << cpu.threads << ","
      << "\"totalScore\":" << totalScore << ","
      << "\"bufferSizeMB\":32,"
      << "\"fillIterations\":" << FILL_ITERS << ","
      << "\"copyIterations\":" << COPY_ITERS << ","
      << "\"timestampPeriodNs\":" << timestampPeriod << "}";
    return j.str();
}

// 系统 Vulkan 基准（主进程直连 libvulkan.so）
static std::string benchmarkSystemVulkan() {
    VkInstance instance = createVulkanInstance(vkCreateInstance);
    if (instance == VK_NULL_HANDLE) {
        return "{\"success\":false,\"errorCode\":\"CREATE_INSTANCE_FAILED\",\"errorMessage\":\"vkCreateInstance failed\"}";
    }

    VulkanDispatch vk{};
    vk.vkEnumeratePhysicalDevices = reinterpret_cast<PFN_EnumeratePhysicalDevices>(
        vkGetInstanceProcAddr(instance, "vkEnumeratePhysicalDevices"));
    vk.vkGetPhysicalDeviceProperties = reinterpret_cast<PFN_GetPhysicalDeviceProperties>(
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties"));
    vk.vkGetPhysicalDeviceMemoryProperties = reinterpret_cast<PFN_GetPhysicalDeviceMemoryProperties>(
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceMemoryProperties"));
    vk.vkGetPhysicalDeviceQueueFamilyProperties = reinterpret_cast<PFN_GetPhysicalDeviceQueueFamilyProperties>(
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceQueueFamilyProperties"));

    uint32_t deviceCount = 0;
    vk.vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
    if (deviceCount == 0) {
        vkDestroyInstance(instance, nullptr);
        return "{\"success\":false,\"errorCode\":\"NO_DEVICE\",\"errorMessage\":\"无 Vulkan 物理设备\"}";
    }

    std::vector<VkPhysicalDevice> devices(deviceCount);
    vk.vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data());

    std::string result = runBenchmark(vk, instance, devices[0], nullptr, false);

    vkDestroyInstance(instance, nullptr);
    return result;
}

// ── 驱动级基准：真正加载驱动 .so 跑分（不是系统 Vulkan）────────────────────
// 与 collectWithDriver 同一加载范式：dlopen → dlsym(vkGetInstanceProcAddr) →
// 用驱动创建 instance/device → 跑带宽 + compute 基准。
// 必须在 fork 子进程内调用：驱动不兼容当前 GPU 时崩溃只死子进程。
static std::string runBenchmarkWithDriver(const char* driverPath) {
    VulkanDispatch vk;
    vk.libHandle = dlopen(driverPath, RTLD_NOW | RTLD_LOCAL);
    if (!vk.libHandle) {
        std::string err = dlerror() ? dlerror() : "unknown";
        return "{\"success\":false,\"errorCode\":\"DL_OPEN_FAILED\",\"errorMessage\":\""
               + jsonEscape(err) + "\",\"fillBandwidthGBs\":0,\"copyBandwidthGBs\":0,"
               + "\"computeGFLOPS\":0,\"computeError\":\"\",\"totalScore\":0}";
    }

    // ICD 规范入口：mesa turnip/panvk 导出的标准符号是 vk_icdGetInstanceProcAddr
    // （ICD loader 契约），vkGetInstanceProcAddr 只由 loader 导出、驱动不一定有。
    // 之前只查 vkGetInstanceProcAddr 导致所有 turnip 驱动误报"未导出"，这是误判。
    auto getInst = reinterpret_cast<GetInstanceProcAddrFn>(
        dlsym(vk.libHandle, "vk_icdGetInstanceProcAddr"));
    if (!getInst) {
        getInst = reinterpret_cast<GetInstanceProcAddrFn>(
            dlsym(vk.libHandle, "vkGetInstanceProcAddr"));
    }
    if (!getInst) {
        dlclose(vk.libHandle);
        return "{\"success\":false,\"errorCode\":\"NO_VK_ENTRY\",\"errorMessage\":\""
               "driver lacks vk_icdGetInstanceProcAddr/vkGetInstanceProcAddr\","
               "\"fillBandwidthGBs\":0,\"copyBandwidthGBs\":0,\"computeGFLOPS\":0,"
               "\"computeError\":\"\",\"totalScore\":0}";
    }

    vk.vkCreateInstance = reinterpret_cast<PFN_CreateInstance>(
        getInst(VK_NULL_HANDLE, "vkCreateInstance"));
    vk.vkDestroyInstance = reinterpret_cast<PFN_DestroyInstance>(
        getInst(VK_NULL_HANDLE, "vkDestroyInstance"));
    if (!vk.vkCreateInstance || !vk.vkDestroyInstance) {
        dlclose(vk.libHandle);
        return "{\"success\":false,\"errorCode\":\"INSTANCE_FUNCS_MISSING\",\"errorMessage\":\""
               "driver lacks vkCreateInstance/vkDestroyInstance\",\"fillBandwidthGBs\":0,"
               "\"copyBandwidthGBs\":0,\"computeGFLOPS\":0,\"computeError\":\"\",\"totalScore\":0}";
    }

    VkInstance instance = createVulkanInstance(vk.vkCreateInstance);
    if (instance == VK_NULL_HANDLE) {
        dlclose(vk.libHandle);
        return "{\"success\":false,\"errorCode\":\"CREATE_INSTANCE_FAILED\",\"errorMessage\":\""
               "driver vkCreateInstance failed\",\"fillBandwidthGBs\":0,\"copyBandwidthGBs\":0,"
               "\"computeGFLOPS\":0,\"computeError\":\"\",\"totalScore\":0}";
    }

    vk.vkEnumeratePhysicalDevices = reinterpret_cast<PFN_EnumeratePhysicalDevices>(
        getInst(instance, "vkEnumeratePhysicalDevices"));
    vk.vkGetPhysicalDeviceProperties = reinterpret_cast<PFN_GetPhysicalDeviceProperties>(
        getInst(instance, "vkGetPhysicalDeviceProperties"));
    vk.vkGetPhysicalDeviceMemoryProperties = reinterpret_cast<PFN_GetPhysicalDeviceMemoryProperties>(
        getInst(instance, "vkGetPhysicalDeviceMemoryProperties"));
    vk.vkGetPhysicalDeviceQueueFamilyProperties = reinterpret_cast<PFN_GetPhysicalDeviceQueueFamilyProperties>(
        getInst(instance, "vkGetPhysicalDeviceQueueFamilyProperties"));

    uint32_t deviceCount = 0;
    vk.vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
    if (deviceCount == 0) {
        vk.vkDestroyInstance(instance, nullptr);
        dlclose(vk.libHandle);
        return "{\"success\":false,\"errorCode\":\"NO_DEVICE\",\"errorMessage\":\""
               "driver reports no Vulkan physical device\",\"fillBandwidthGBs\":0,"
               "\"copyBandwidthGBs\":0,\"computeGFLOPS\":0,\"computeError\":\"\",\"totalScore\":0}";
    }

    std::vector<VkPhysicalDevice> devices(deviceCount);
    vk.vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data());

    // 跑基准：resolver 传驱动的 vkGetInstanceProcAddr，保证 device 级函数从驱动解析
    std::string result = runBenchmark(vk, instance, devices[0], getInst, true);
    vk.vkDestroyInstance(instance, nullptr);
    dlclose(vk.libHandle);
    return result;
}

// 通用隔离执行器：fork 子进程跑 fn()，崩溃/超时只死子进程，父进程拿结果。
// 系统跑分与驱动跑分统一走这里——GPU 驱动对非法 shader/不兼容实例的
// native 崩溃（段错误、abort）不会带崩 App 进程。
static std::string runBenchmarkIsolated(const std::function<std::string()>& fn) {
    int pipefd[2];
    if (pipe(pipefd) != 0) {
        return "{\"success\":false,\"errorCode\":\"PIPE_FAILED\",\"errorMessage\":\"pipe() failed\"}";
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(pipefd[0]);
        close(pipefd[1]);
        return "{\"success\":false,\"errorCode\":\"FORK_FAILED\",\"errorMessage\":\"fork() failed\"}";
    }

    if (pid == 0) {
        close(pipefd[0]);
        std::string json;
        try {
            json = fn();
        } catch (const std::exception& e) {
            json = std::string("{\"success\":false,\"errorCode\":\"NATIVE_EXCEPTION\",\"errorMessage\":\"")
                   + jsonEscape(e.what()) + "\"}";
        } catch (...) {
            json = "{\"success\":false,\"errorCode\":\"UNKNOWN_NATIVE_ERROR\",\"errorMessage\":\"unknown native exception\"}";
        }
        const char* data = json.c_str();
        size_t len = json.size();
        size_t written = 0;
        while (written < len) {
            ssize_t n = write(pipefd[1], data + written, len - written);
            if (n <= 0) break;
            written += static_cast<size_t>(n);
        }
        close(pipefd[1]);
        _exit(0);
    }

    close(pipefd[1]);
    std::string result;
    char buf[8192];
    struct pollfd pfd;
    pfd.fd = pipefd[0];
    pfd.events = POLLIN;
    const int timeoutMs = 25000; // compute 2 万 dispatch 比带宽基准更耗时

    while (true) {
        int r = poll(&pfd, 1, timeoutMs);
        if (r <= 0) break;
        ssize_t n = read(pipefd[0], buf, sizeof(buf));
        if (n <= 0) break;
        result.append(buf, static_cast<size_t>(n));
        int status = 0;
        pid_t w = waitpid(pid, &status, WNOHANG);
        if (w == pid) break;
    }

    int status = 0;
    pid_t w = waitpid(pid, &status, WNOHANG);
    if (w == 0) {
        kill(pid, SIGKILL);
        waitpid(pid, &status, 0);
    }
    close(pipefd[0]);

    if (result.empty()) {
        return "{\"success\":false,\"errorCode\":\"DRIVER_CRASHED\",\"errorMessage\":\"跑分进程崩溃或超时——App 进程提交 GPU 负载受环境限制（SELinux/容器），不代表驱动不兼容；驱动可用性已由实测采集判断，性能请在模拟器内验证\",\"deviceName\":\"\",\"fillBandwidthGBs\":0,\"copyBandwidthGBs\":0,\"computeGFLOPS\":0,\"computeError\":\"\",\"totalScore\":0}";
    }
    if (result.front() != '{' || result.back() != '}') {
        return "{\"success\":false,\"errorCode\":\"PARTIAL_JSON\",\"errorMessage\":\"基准进程返回不完整数据\",\"deviceName\":\"\",\"fillBandwidthGBs\":0,\"copyBandwidthGBs\":0,\"computeGFLOPS\":0,\"computeError\":\"\",\"totalScore\":0}";
    }
    return result;
}

// 自定义驱动基准（fork 子进程隔离）
static std::string benchmarkDriverIsolated(const char* driverPath) {
    return runBenchmarkIsolated([&]() {
        return runBenchmarkWithDriver(driverPath);
    });
}

// ── JNI 入口：GPU 基准测试 ──────────────────────────────────────────────────
// Kotlin: com.emubox.app.NativeVulkanBridge.benchmarkVulkan(String driverPath)
// driverPath 为 null/空时测系统 Vulkan；传入驱动路径时 fork 子进程隔离。
extern "C" JNIEXPORT jstring JNICALL
Java_com_emubox_app_NativeVulkanBridge_benchmarkVulkan(JNIEnv* env, jobject /* thiz */, jstring driverPath) {
    const char* path = nullptr;
    if (driverPath) {
        path = env->GetStringUTFChars(driverPath, nullptr);
    }

    std::string json;
    if (path == nullptr || path[0] == '\0') {
        // 系统跑分必须在主进程执行：Android 的 GPU 驱动（尤其 Adreno）明确不支持
        // fork 后使用（gralloc/BLAST 队列状态在 fork 后失效），fork 子进程调用
        // Vulkan API 必崩。此前"fork 隔离"思路反而导致系统跑分 100% 失败。
        // v1.10.5 已停用 compute 段，默认仅 fill/copy（驱动最稳定路径），主进程
        // 执行风险可控；buffer 分配失败等异常仍会以错误 JSON 返回，不闪退。
        json = benchmarkSystemVulkan();
    } else {
        // 自定义驱动实测：dlopen 驱动 .so 后通过 ICD 入口（vk_icdGetInstanceProcAddr）
        // 创建 instance/device 跑带宽基准，与系统 Vulkan 驱动完全解耦（驱动自管
        // GPU 访问，不依赖 zygote 预加载的 GPU 状态），因此 fork 子进程隔离可用，
        // 驱动崩溃只死子进程。创建失败返回具体原因（SELinux 权限/符号缺失等）。
        json = benchmarkDriverIsolated(path);
    }

    if (driverPath && path) {
        env->ReleaseStringUTFChars(driverPath, path);
    }

    return env->NewStringUTF(json.c_str());
}

// ═══════════════════════════════════════════════════════════════════════════
// AdrenoTools 实测：以 Winlator/GameHub/Eden 同款加载路径（linker namespace
// 绕过 + GPU 映射导入 ADRENOTOOLS_DRIVER_GPU_MAPPING_IMPORT，turnip GSL 分配
// 必需）加载自定义 turnip/panvk 驱动，枚举实例/设备扩展、设备属性并跑分。
// 裸 dlopen 在普通 App 进程会因符号解析/namespace 限制失败，本路径与模拟器
// 环境一致，兼容面最广。驱动崩溃仍只死 fork 子进程。
// 修改原因：用户要求集成 adrenotools 实测驱动性能/设备兼容/支持扩展数。
// 影响范围：新增两个 JNI 入口（collectDriverAdrenotools / benchmarkDriverAdrenotools），
//           不改动现有 dlopen 采集与系统跑分路径。
// 回滚方法：删除本段代码与 Kotlin 侧两个 external 声明即可。
// ═══════════════════════════════════════════════════════════════════════════

// ── 系统 Vulkan 预热（vktest/PPSSPP 同款前提）─────────────────────────────
// :drivertest 独立进程启动后从未初始化系统 Vulkan。AdrenoTools 的
// GPU_MAPPING_IMPORT 需要把自定义驱动挂到系统驱动的 GSL 映射上（非 root 下
// 不用开 /dev/kgsl）；缺宿主 GPU 上下文则 mapping import 无对象，降级仅
// CUSTOM 又要求 kgsl 权限，两路必败。故加载自定义驱动前先创建系统实例并
// 枚举物理设备，让系统驱动建立映射上下文。实例静态持有至进程退出。
static VkInstance g_systemVulkanInstance = VK_NULL_HANDLE;
static void warmupSystemVulkan() {
    if (g_systemVulkanInstance != VK_NULL_HANDLE) return;
    // native 库链接了系统 libvulkan（loader），全局 vkCreateInstance 可用
    VkInstance instance = createVulkanInstance(vkCreateInstance);
    if (instance == VK_NULL_HANDLE) return;
    // 枚举物理设备，触发系统驱动真实初始化（打开 GPU 节点）
    auto enumFn = reinterpret_cast<PFN_EnumeratePhysicalDevices>(
            vkGetInstanceProcAddr(instance, "vkEnumeratePhysicalDevices"));
    if (enumFn) {
        uint32_t count = 0;
        enumFn(instance, &count, nullptr);
        if (count > 0) {
            std::vector<VkPhysicalDevice> devs(count);
            enumFn(instance, &count, devs.data());
        }
    }
    g_systemVulkanInstance = instance;
}

// 加载驱动并解析 ICD 入口；成功返回空串并写出句柄，失败返回错误 JSON。
// useMappingImport=false 时强制仅 CUSTOM（不启用 GPU_MAPPING_IMPORT）：
// 跑分在 fork 子进程中提交实际 GPU 负载，GSL 映射句柄跨 fork 可能失效
// 导致崩溃（DRIVER_CRASHED），故跑分路径不申请映射，保留 fork 安全隔离。
static std::string loadDriverAdrenoTools(const char* driverDir, const char* driverName,
                                         const char* tmpDir, const char* hookLibDir,
                                         bool useMappingImport,
                                         void** libHandleOut, GetInstanceProcAddrFn* getInstOut) {
    // adrenotools 内部按 "dir + 文件名" 直接拼接 stat/dlopen，目录必须以 "/" 结尾。
    // 此前 Kotlin 侧传 File.parent（无结尾斜杠）导致拼接路径非法、加载必失败
    // （ADRENOTOOLS_OPEN_FAILED，从 m24 集成至今）。这里归一化兜底，任何调用
    // 方漏传斜杠都不再影响加载。
    std::string driverDirNorm = driverDir ? driverDir : "";
    if (!driverDirNorm.empty() && driverDirNorm.back() != '/') driverDirNorm += "/";
    std::string tmpDirNorm = tmpDir ? tmpDir : "";
    if (!tmpDirNorm.empty() && tmpDirNorm.back() != '/') tmpDirNorm += "/";
    const char* dd = driverDirNorm.c_str();
    const char* td = tmpDirNorm.empty() ? nullptr : tmpDirNorm.c_str();

    void* mappingHandle = nullptr;
    // 默认首次尝试完整功能：自定义驱动 + GPU 映射导入（turnip GSL 分配通常需要）。
    // 部分驱动/设备不支持映射导入时该组合会失败，降级为仅自定义驱动重试，
    // 与 Winlator 低端 fallback 策略一致，扩大实测成功率。映射句柄由 fork
    // 子进程退出即释放，无需手动 close。
    void* lib = nullptr;
    std::string firstErr;
    if (useMappingImport) {
        // 先预热系统 GPU 上下文（vktest/PPSSPP 同款前提），再挂载自定义驱动
        warmupSystemVulkan();
        lib = adrenotools_open_libvulkan(
                RTLD_NOW,
                ADRENOTOOLS_DRIVER_CUSTOM | ADRENOTOOLS_DRIVER_GPU_MAPPING_IMPORT,
                td, hookLibDir, dd, driverName, nullptr, &mappingHandle);
        if (!lib) {
            firstErr = dlerror() ? dlerror() : "unknown";
            void* retryMapping = nullptr;
            lib = adrenotools_open_libvulkan(
                    RTLD_NOW,
                    ADRENOTOOLS_DRIVER_CUSTOM,
                    td, hookLibDir, dd, driverName, nullptr, &retryMapping);
        }
    } else {
        // 跑分路径：强制仅 CUSTOM，不申请 GPU 映射，规避映射句柄跨 fork 失效。
        lib = adrenotools_open_libvulkan(
                RTLD_NOW,
                ADRENOTOOLS_DRIVER_CUSTOM,
                td, hookLibDir, dd, driverName, nullptr, nullptr);
    }
    if (!lib) {
        std::string err = dlerror() ? dlerror() : firstErr;
        // 附加前置诊断：驱动文件是否存在/hook 库是否在 nativeLibraryDir，
        // 帮助区分"路径/文件问题"与"系统级权限问题"。
        std::string driverPath = driverDirNorm + driverName;
        bool driverExists = !driverPath.empty() && access(driverPath.c_str(), F_OK) == 0;
        std::string diag = "driver_exists=" + std::string(driverExists ? "1" : "0");
        return "{\"success\":false,\"errorCode\":\"ADRENOTOOLS_OPEN_FAILED\",\"errorMessage\":\""
               + jsonEscape(err) + "\",\"diag\":\"" + diag + "\",\"deviceCount\":0,\"devices\":[]}";
    }
    // ICD 规范入口：mesa turnip/panvk 导出 vk_icdGetInstanceProcAddr
    auto getInst = reinterpret_cast<GetInstanceProcAddrFn>(
            dlsym(lib, "vk_icdGetInstanceProcAddr"));
    if (!getInst) {
        getInst = reinterpret_cast<GetInstanceProcAddrFn>(
                dlsym(lib, "vkGetInstanceProcAddr"));
    }
    if (!getInst) {
        dlclose(lib);
        return "{\"success\":false,\"errorCode\":\"NO_VK_ENTRY\",\"errorMessage\":\""
               "driver lacks vk_icdGetInstanceProcAddr/vkGetInstanceProcAddr\","
               "\"deviceCount\":0,\"devices\":[]}";
    }
    LOGI("AdrenoTools driver loaded: %s/%s via %s", driverDir, driverName,
         getInst == reinterpret_cast<GetInstanceProcAddrFn>(dlsym(lib, "vk_icdGetInstanceProcAddr"))
             ? "icd" : "classic");
    *libHandleOut = lib;
    *getInstOut = getInst;
    return "";
}

// AdrenoTools 路径采集：实例扩展 + 物理设备属性/特性/设备扩展/队列/内存
static std::string collectDriverAdrenotoolsJson(const char* driverDir, const char* driverName,
                                                const char* tmpDir, const char* hookLibDir) {
    void* lib = nullptr;
    GetInstanceProcAddrFn getInst = nullptr;
    std::string err = loadDriverAdrenoTools(driverDir, driverName, tmpDir, hookLibDir, true, &lib, &getInst);
    if (!err.empty()) return err;

    VulkanDispatch vk;
    vk.libHandle = lib;
    // 优先经 getInst（Mesa ICD 规范 vk_icdGetInstanceProcAddr / classic
    // vkGetInstanceProcAddr）取全局函数。实测发现部分驱动（如 KIMCHI/
    // The412Banner 的 turnip 包）仅导出 classic 入口，其 vkGetInstanceProcAddr
    // 对 VK_NULL_HANDLE+"vkCreateInstance" 返回空（与本项目系统采集遇到的
    // ROM 行为一致），但 .so 直接导出 vkCreateInstance 符号——此时 fallback
    // 用 dlsym 直取符号，跳过查询。
    vk.vkCreateInstance = getInst
            ? reinterpret_cast<PFN_CreateInstance>(getInst(VK_NULL_HANDLE, "vkCreateInstance"))
            : nullptr;
    if (!vk.vkCreateInstance) {
        vk.vkCreateInstance = reinterpret_cast<PFN_CreateInstance>(dlsym(lib, "vkCreateInstance"));
    }
    vk.vkDestroyInstance = getInst
            ? reinterpret_cast<PFN_DestroyInstance>(getInst(VK_NULL_HANDLE, "vkDestroyInstance"))
            : nullptr;
    if (!vk.vkDestroyInstance) {
        vk.vkDestroyInstance = reinterpret_cast<PFN_DestroyInstance>(dlsym(lib, "vkDestroyInstance"));
    }
    if (!vk.vkCreateInstance || !vk.vkDestroyInstance) {
        const char* symCreate = dlsym(lib, "vkCreateInstance") ? "1" : "0";
        const char* symIcd = dlsym(lib, "vk_icdGetInstanceProcAddr") ? "1" : "0";
        const char* symClassic = dlsym(lib, "vkGetInstanceProcAddr") ? "1" : "0";
        LOGI("AdrenoTools INSTANCE_FUNCS_MISSING: sym_create=%s icd=%s classic=%s",
             symCreate, symIcd, symClassic);
        dlclose(lib);
        return "{\"success\":false,\"errorCode\":\"INSTANCE_FUNCS_MISSING\",\"errorMessage\":\""
               "driver lacks vkCreateInstance/vkDestroyInstance [diag: sym_create=" + std::string(symCreate)
               + " icd=" + std::string(symIcd) + " classic=" + std::string(symClassic)
               + "]\",\"deviceCount\":0,\"devices\":[]}";
    }

    VkInstance instance = createVulkanInstance(vk.vkCreateInstance);
    if (instance == VK_NULL_HANDLE) {
        dlclose(lib);
        return "{\"success\":false,\"errorCode\":\"CREATE_INSTANCE_FAILED\",\"errorMessage\":\""
               "driver vkCreateInstance failed\",\"deviceCount\":0,\"devices\":[]}";
    }

    vk.vkEnumeratePhysicalDevices = reinterpret_cast<PFN_EnumeratePhysicalDevices>(
            getInst(instance, "vkEnumeratePhysicalDevices"));
    vk.vkGetPhysicalDeviceProperties = reinterpret_cast<PFN_GetPhysicalDeviceProperties>(
            getInst(instance, "vkGetPhysicalDeviceProperties"));
    vk.vkGetPhysicalDeviceFeatures = reinterpret_cast<PFN_GetPhysicalDeviceFeatures>(
            getInst(instance, "vkGetPhysicalDeviceFeatures"));
    vk.vkEnumerateDeviceExtensionProperties = reinterpret_cast<PFN_EnumerateDeviceExtensionProperties>(
            getInst(instance, "vkEnumerateDeviceExtensionProperties"));
    vk.vkGetPhysicalDeviceMemoryProperties = reinterpret_cast<PFN_GetPhysicalDeviceMemoryProperties>(
            getInst(instance, "vkGetPhysicalDeviceMemoryProperties"));
    vk.vkGetPhysicalDeviceQueueFamilyProperties = reinterpret_cast<PFN_GetPhysicalDeviceQueueFamilyProperties>(
            getInst(instance, "vkGetPhysicalDeviceQueueFamilyProperties"));
    vk.vkGetPhysicalDeviceProperties2 = reinterpret_cast<PFN_GetPhysicalDeviceProperties2>(
            getInst(instance, "vkGetPhysicalDeviceProperties2"));

    // 实例扩展（ICD/loader 层能力，vkEnumerateInstanceExtensionProperties 无需实例）
    int instExtCount = 0;
    std::string instExts = "[]";
    auto enumInstExt = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(
            getInst(VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties"));
    if (enumInstExt) {
        uint32_t n = 0;
        if (enumInstExt(nullptr, &n, nullptr) == VK_SUCCESS && n > 0) {
            std::vector<VkExtensionProperties> exts(n);
            if (enumInstExt(nullptr, &n, exts.data()) == VK_SUCCESS) {
                instExtCount = static_cast<int>(n);
                std::ostringstream s;
                s << "[";
                for (uint32_t i = 0; i < n; i++) {
                    if (i) s << ",";
                    s << "{\"name\":\"" << jsonEscape(exts[i].extensionName)
                      << "\",\"specVersion\":" << exts[i].specVersion << "}";
                }
                s << "]";
                instExts = s.str();
            }
        }
    }

    std::string dev = collectDevices(vk, instance);
    vk.vkDestroyInstance(instance, nullptr);
    dlclose(lib);

    // 把 loader/实例扩展字段插入设备 JSON（collectDevices 固定输出结构）
    std::string insert = "\"loader\":\"adrenotools\",\"instanceExtCount\":"
                         + std::to_string(instExtCount) + ",\"instanceExtensions\":" + instExts + ",";
    auto pos = dev.find("\"deviceCount\"");
    if (pos == std::string::npos) return dev;
    dev.insert(pos, insert);
    return dev;
}

// ═══════════════════════════════════════════════════════════════════════════
// vktest 同款加载链：ICD JSON + 系统 Vulkan Loader（驱动检测首选路径）
// vktest（K11MCH1 AdrenoTools 测试 App）在独立进程中经系统 loader 加载自定义
// 驱动：写 ICD JSON → setenv VK_ICD_FILENAMES → dlopen libvulkan.so（Khronos
// loader）→ loader 按 JSON 的 library_path 加载驱动并走标准 ICD 初始化。
// 相比 adrenotools_open_libvulkan（linker namespace 直载），loader 路径与
// mesa turnip/panvk 的符号解析/初始化行为完全同构，兼容面更广——这正是
// vktest 能跑通而裸 dlopen 在部分设备失败的原因。
// 修改原因：用户要求驱动检测达到 vktest 同水平（真正加载 + 本地渲染验证）。
// 影响范围：新增 loader 路径，adrenotools 路径保留为 fallback。
// 回滚方法：删除本段及 JNI 双路径包装即可。
// ═══════════════════════════════════════════════════════════════════════════
static std::string loadDriverViaLoader(const char* driverDir, const char* driverName,
                                       const char* tmpDir,
                                       void** libHandleOut, GetInstanceProcAddrFn* getInstOut) {
    std::string dir = driverDir ? driverDir : "";
    if (!dir.empty() && dir.back() != '/') dir += "/";
    std::string tmp = tmpDir ? tmpDir : "";
    if (!tmp.empty() && tmp.back() != '/') tmp += "/";
    std::string driverPath = dir + driverName;
    if (access(driverPath.c_str(), F_OK) != 0) {
        return "{\"success\":false,\"errorCode\":\"DRIVER_FILE_MISSING\",\"errorMessage\":\""
               "driver file not found: " + driverPath + "\",\"deviceCount\":0,\"devices\":[]}";
    }
    // 1) 写 ICD JSON：library_path 用绝对路径
    std::string icdPath = tmp + "icd_" + std::to_string(getpid()) + ".json";
    std::string icdBody = "{\"file_format_version\":\"1.0.0\",\"ICD\":{\"library_path\":\""
                          + driverPath + "\",\"api_version\":\"1.3.0\"}}";
    {
        FILE* f = fopen(icdPath.c_str(), "w");
        if (!f) {
            return "{\"success\":false,\"errorCode\":\"ICD_WRITE_FAILED\",\"errorMessage\":\""
                   "cannot write icd json: " + icdPath + "\",\"deviceCount\":0,\"devices\":[]}";
        }
        fputs(icdBody.c_str(), f);
        fclose(f);
    }
    // 2) setenv：loader 首次初始化读取 VK_ICD_FILENAMES
    if (setenv("VK_ICD_FILENAMES", icdPath.c_str(), 1) != 0) {
        return "{\"success\":false,\"errorCode\":\"SETENV_FAILED\",\"errorMessage\":\""
               "setenv VK_ICD_FILENAMES failed\",\"deviceCount\":0,\"devices\":[]}";
    }
    // 3) dlopen 系统 loader
    void* loader = dlopen("libvulkan.so", RTLD_NOW);
    if (!loader) {
        std::string err = dlerror() ? dlerror() : "unknown";
        return "{\"success\":false,\"errorCode\":\"LOADER_OPEN_FAILED\",\"errorMessage\":\""
               "dlopen libvulkan.so failed: " + jsonEscape(err) + "\",\"deviceCount\":0,\"devices\":[]}";
    }
    auto getInst = reinterpret_cast<GetInstanceProcAddrFn>(dlsym(loader, "vkGetInstanceProcAddr"));
    if (!getInst) {
        dlclose(loader);
        return "{\"success\":false,\"errorCode\":\"LOADER_NO_ENTRY\",\"errorMessage\":\""
               "loader lacks vkGetInstanceProcAddr\",\"deviceCount\":0,\"devices\":[]}";
    }
    *libHandleOut = loader;
    *getInstOut = getInst;
    LOGI("Driver loaded via loader+ICD JSON: %s", driverPath.c_str());
    return "";
}

// Loader 路径采集：与 collectDriverAdrenotoolsJson 同构，仅加载段走 ICD JSON+loader
static std::string collectDriverViaLoaderJson(const char* driverDir, const char* driverName,
                                              const char* tmpDir, const char* hookLibDir) {
    void* lib = nullptr;
    GetInstanceProcAddrFn getInst = nullptr;
    std::string err = loadDriverViaLoader(driverDir, driverName, tmpDir, &lib, &getInst);
    if (!err.empty()) return err;

    VulkanDispatch vk;
    vk.libHandle = lib;
    vk.vkCreateInstance = getInst
            ? reinterpret_cast<PFN_CreateInstance>(getInst(VK_NULL_HANDLE, "vkCreateInstance"))
            : nullptr;
    if (!vk.vkCreateInstance) {
        vk.vkCreateInstance = reinterpret_cast<PFN_CreateInstance>(dlsym(lib, "vkCreateInstance"));
    }
    vk.vkDestroyInstance = getInst
            ? reinterpret_cast<PFN_DestroyInstance>(getInst(VK_NULL_HANDLE, "vkDestroyInstance"))
            : nullptr;
    if (!vk.vkDestroyInstance) {
        vk.vkDestroyInstance = reinterpret_cast<PFN_DestroyInstance>(dlsym(lib, "vkDestroyInstance"));
    }
    if (!vk.vkCreateInstance || !vk.vkDestroyInstance) {
        dlclose(lib);
        unsetenv("VK_ICD_FILENAMES");
        return "{\"success\":false,\"errorCode\":\"INSTANCE_FUNCS_MISSING\",\"errorMessage\":\""
               "loader path: driver lacks vkCreateInstance/vkDestroyInstance\",\"deviceCount\":0,\"devices\":[]}";
    }

    VkInstance instance = createVulkanInstance(vk.vkCreateInstance);
    if (instance == VK_NULL_HANDLE) {
        dlclose(lib);
        unsetenv("VK_ICD_FILENAMES");
        return "{\"success\":false,\"errorCode\":\"CREATE_INSTANCE_FAILED\",\"errorMessage\":\""
               "loader path: driver vkCreateInstance failed\",\"deviceCount\":0,\"devices\":[]}";
    }

    vk.vkEnumeratePhysicalDevices = reinterpret_cast<PFN_EnumeratePhysicalDevices>(
            getInst(instance, "vkEnumeratePhysicalDevices"));
    vk.vkGetPhysicalDeviceProperties = reinterpret_cast<PFN_GetPhysicalDeviceProperties>(
            getInst(instance, "vkGetPhysicalDeviceProperties"));
    vk.vkGetPhysicalDeviceFeatures = reinterpret_cast<PFN_GetPhysicalDeviceFeatures>(
            getInst(instance, "vkGetPhysicalDeviceFeatures"));
    vk.vkEnumerateDeviceExtensionProperties = reinterpret_cast<PFN_EnumerateDeviceExtensionProperties>(
            getInst(instance, "vkEnumerateDeviceExtensionProperties"));
    vk.vkGetPhysicalDeviceMemoryProperties = reinterpret_cast<PFN_GetPhysicalDeviceMemoryProperties>(
            getInst(instance, "vkGetPhysicalDeviceMemoryProperties"));
    vk.vkGetPhysicalDeviceQueueFamilyProperties = reinterpret_cast<PFN_GetPhysicalDeviceQueueFamilyProperties>(
            getInst(instance, "vkGetPhysicalDeviceQueueFamilyProperties"));
    vk.vkGetPhysicalDeviceProperties2 = reinterpret_cast<PFN_GetPhysicalDeviceProperties2>(
            getInst(instance, "vkGetPhysicalDeviceProperties2"));

    int instExtCount = 0;
    std::string instExts = "[]";
    auto enumInstExt = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(
            getInst(VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties"));
    if (enumInstExt) {
        uint32_t n = 0;
        if (enumInstExt(nullptr, &n, nullptr) == VK_SUCCESS && n > 0) {
            std::vector<VkExtensionProperties> exts(n);
            if (enumInstExt(nullptr, &n, exts.data()) == VK_SUCCESS) {
                instExtCount = static_cast<int>(n);
                std::ostringstream s;
                s << "[";
                for (uint32_t i = 0; i < n; i++) {
                    if (i) s << ",";
                    s << "{\"name\":\"" << jsonEscape(exts[i].extensionName)
                      << "\",\"specVersion\":" << exts[i].specVersion << "}";
                }
                s << "]";
                instExts = s.str();
            }
        }
    }

    std::string dev = collectDevices(vk, instance);
    vk.vkDestroyInstance(instance, nullptr);
    dlclose(lib);
    unsetenv("VK_ICD_FILENAMES");
    std::string insert = "\"loader\":\"loader\",\"instanceExtCount\":"
                         + std::to_string(instExtCount) + ",\"instanceExtensions\":" + instExts + ",";
    auto pos = dev.find("\"deviceCount\"");
    if (pos == std::string::npos) return dev;
    dev.insert(pos, insert);
    return dev;
}

// Loader 路径跑分：复用 runBenchmark，验证驱动真正渲染（fill/copy 带宽 = GPU 实际负载）
static std::string benchmarkDriverViaLoaderJson(const char* driverDir, const char* driverName,
                                                const char* tmpDir, const char* hookLibDir) {
    void* lib = nullptr;
    GetInstanceProcAddrFn getInst = nullptr;
    std::string err = loadDriverViaLoader(driverDir, driverName, tmpDir, &lib, &getInst);
    if (!err.empty()) return err;

    VulkanDispatch vk;
    vk.libHandle = lib;
    vk.vkCreateInstance = getInst
            ? reinterpret_cast<PFN_CreateInstance>(getInst(VK_NULL_HANDLE, "vkCreateInstance"))
            : nullptr;
    if (!vk.vkCreateInstance) {
        vk.vkCreateInstance = reinterpret_cast<PFN_CreateInstance>(dlsym(lib, "vkCreateInstance"));
    }
    vk.vkDestroyInstance = getInst
            ? reinterpret_cast<PFN_DestroyInstance>(getInst(VK_NULL_HANDLE, "vkDestroyInstance"))
            : nullptr;
    if (!vk.vkDestroyInstance) {
        vk.vkDestroyInstance = reinterpret_cast<PFN_DestroyInstance>(dlsym(lib, "vkDestroyInstance"));
    }
    if (!vk.vkCreateInstance || !vk.vkDestroyInstance) {
        dlclose(lib);
        unsetenv("VK_ICD_FILENAMES");
        return "{\"success\":false,\"errorCode\":\"INSTANCE_FUNCS_MISSING\",\"errorMessage\":\""
               "loader path: driver lacks vkCreateInstance/vkDestroyInstance\",\"fillBandwidthGBs\":0,"
               "\"copyBandwidthGBs\":0,\"computeGFLOPS\":0,\"computeError\":\"\",\"totalScore\":0}";
    }

    VkInstance instance = createVulkanInstance(vk.vkCreateInstance);
    if (instance == VK_NULL_HANDLE) {
        dlclose(lib);
        unsetenv("VK_ICD_FILENAMES");
        return "{\"success\":false,\"errorCode\":\"CREATE_INSTANCE_FAILED\",\"errorMessage\":\""
               "loader path: driver vkCreateInstance failed\",\"fillBandwidthGBs\":0,\"copyBandwidthGBs\":0,"
               "\"computeGFLOPS\":0,\"computeError\":\"\",\"totalScore\":0}";
    }

    vk.vkEnumeratePhysicalDevices = reinterpret_cast<PFN_EnumeratePhysicalDevices>(
            getInst(instance, "vkEnumeratePhysicalDevices"));
    vk.vkGetPhysicalDeviceProperties = reinterpret_cast<PFN_GetPhysicalDeviceProperties>(
            getInst(instance, "vkGetPhysicalDeviceProperties"));
    vk.vkGetPhysicalDeviceFeatures = reinterpret_cast<PFN_GetPhysicalDeviceFeatures>(
            getInst(instance, "vkGetPhysicalDeviceFeatures"));
    vk.vkEnumerateDeviceExtensionProperties = reinterpret_cast<PFN_EnumerateDeviceExtensionProperties>(
            getInst(instance, "vkEnumerateDeviceExtensionProperties"));
    vk.vkGetPhysicalDeviceMemoryProperties = reinterpret_cast<PFN_GetPhysicalDeviceMemoryProperties>(
            getInst(instance, "vkGetPhysicalDeviceMemoryProperties"));
    vk.vkGetPhysicalDeviceQueueFamilyProperties = reinterpret_cast<PFN_GetPhysicalDeviceQueueFamilyProperties>(
            getInst(instance, "vkGetPhysicalDeviceQueueFamilyProperties"));

    uint32_t deviceCount = 0;
    vk.vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
    if (deviceCount == 0) {
        vk.vkDestroyInstance(instance, nullptr);
        dlclose(lib);
        unsetenv("VK_ICD_FILENAMES");
        return "{\"success\":false,\"errorCode\":\"NO_DEVICE\",\"errorMessage\":\""
               "loader path: no physical devices\",\"fillBandwidthGBs\":0,\"copyBandwidthGBs\":0,"
               "\"computeGFLOPS\":0,\"computeError\":\"\",\"totalScore\":0}";
    }
    std::vector<VkPhysicalDevice> devices(deviceCount);
    vk.vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data());
    // enableCompute=true：loader 路径即 vktest 同构，驱动已完整初始化，可提交 GPU 负载
    std::string res = runBenchmark(vk, instance, devices[0], getInst, true);
    vk.vkDestroyInstance(instance, nullptr);
    dlclose(lib);
    unsetenv("VK_ICD_FILENAMES");
    return res;
}

static const uint32_t tri_vert_spv[] = {
    0x03, 0x02, 0x23, 0x07, 0x00, 0x00, 0x01, 0x00, 0x0a, 0x00, 0x0d, 0x00,
    0x36, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x11, 0x00, 0x02, 0x00,
    0x01, 0x00, 0x00, 0x00, 0x0b, 0x00, 0x06, 0x00, 0x01, 0x00, 0x00, 0x00,
    0x47, 0x4c, 0x53, 0x4c, 0x2e, 0x73, 0x74, 0x64, 0x2e, 0x34, 0x35, 0x30,
    0x00, 0x00, 0x00, 0x00, 0x0e, 0x00, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x01, 0x00, 0x00, 0x00, 0x0f, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x04, 0x00, 0x00, 0x00, 0x6d, 0x61, 0x69, 0x6e, 0x00, 0x00, 0x00, 0x00,
    0x22, 0x00, 0x00, 0x00, 0x26, 0x00, 0x00, 0x00, 0x31, 0x00, 0x00, 0x00,
    0x03, 0x00, 0x03, 0x00, 0x02, 0x00, 0x00, 0x00, 0xc2, 0x01, 0x00, 0x00,
    0x04, 0x00, 0x0a, 0x00, 0x47, 0x4c, 0x5f, 0x47, 0x4f, 0x4f, 0x47, 0x4c,
    0x45, 0x5f, 0x63, 0x70, 0x70, 0x5f, 0x73, 0x74, 0x79, 0x6c, 0x65, 0x5f,
    0x6c, 0x69, 0x6e, 0x65, 0x5f, 0x64, 0x69, 0x72, 0x65, 0x63, 0x74, 0x69,
    0x76, 0x65, 0x00, 0x00, 0x04, 0x00, 0x08, 0x00, 0x47, 0x4c, 0x5f, 0x47,
    0x4f, 0x4f, 0x47, 0x4c, 0x45, 0x5f, 0x69, 0x6e, 0x63, 0x6c, 0x75, 0x64,
    0x65, 0x5f, 0x64, 0x69, 0x72, 0x65, 0x63, 0x74, 0x69, 0x76, 0x65, 0x00,
    0x05, 0x00, 0x04, 0x00, 0x04, 0x00, 0x00, 0x00, 0x6d, 0x61, 0x69, 0x6e,
    0x00, 0x00, 0x00, 0x00, 0x05, 0x00, 0x03, 0x00, 0x0c, 0x00, 0x00, 0x00,
    0x70, 0x6f, 0x73, 0x00, 0x05, 0x00, 0x03, 0x00, 0x17, 0x00, 0x00, 0x00,
    0x63, 0x6f, 0x6c, 0x00, 0x05, 0x00, 0x06, 0x00, 0x20, 0x00, 0x00, 0x00,
    0x67, 0x6c, 0x5f, 0x50, 0x65, 0x72, 0x56, 0x65, 0x72, 0x74, 0x65, 0x78,
    0x00, 0x00, 0x00, 0x00, 0x06, 0x00, 0x06, 0x00, 0x20, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x67, 0x6c, 0x5f, 0x50, 0x6f, 0x73, 0x69, 0x74,
    0x69, 0x6f, 0x6e, 0x00, 0x06, 0x00, 0x07, 0x00, 0x20, 0x00, 0x00, 0x00,
    0x01, 0x00, 0x00, 0x00, 0x67, 0x6c, 0x5f, 0x50, 0x6f, 0x69, 0x6e, 0x74,
    0x53, 0x69, 0x7a, 0x65, 0x00, 0x00, 0x00, 0x00, 0x06, 0x00, 0x07, 0x00,
    0x20, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x67, 0x6c, 0x5f, 0x43,
    0x6c, 0x69, 0x70, 0x44, 0x69, 0x73, 0x74, 0x61, 0x6e, 0x63, 0x65, 0x00,
    0x06, 0x00, 0x07, 0x00, 0x20, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00,
    0x67, 0x6c, 0x5f, 0x43, 0x75, 0x6c, 0x6c, 0x44, 0x69, 0x73, 0x74, 0x61,
    0x6e, 0x63, 0x65, 0x00, 0x05, 0x00, 0x03, 0x00, 0x22, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x05, 0x00, 0x06, 0x00, 0x26, 0x00, 0x00, 0x00,
    0x67, 0x6c, 0x5f, 0x56, 0x65, 0x72, 0x74, 0x65, 0x78, 0x49, 0x6e, 0x64,
    0x65, 0x78, 0x00, 0x00, 0x05, 0x00, 0x05, 0x00, 0x31, 0x00, 0x00, 0x00,
    0x66, 0x72, 0x61, 0x67, 0x43, 0x6f, 0x6c, 0x6f, 0x72, 0x00, 0x00, 0x00,
    0x48, 0x00, 0x05, 0x00, 0x20, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x0b, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x48, 0x00, 0x05, 0x00,
    0x20, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x0b, 0x00, 0x00, 0x00,
    0x01, 0x00, 0x00, 0x00, 0x48, 0x00, 0x05, 0x00, 0x20, 0x00, 0x00, 0x00,
    0x02, 0x00, 0x00, 0x00, 0x0b, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00,
    0x48, 0x00, 0x05, 0x00, 0x20, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00,
    0x0b, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x47, 0x00, 0x03, 0x00,
    0x20, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x47, 0x00, 0x04, 0x00,
    0x26, 0x00, 0x00, 0x00, 0x0b, 0x00, 0x00, 0x00, 0x2a, 0x00, 0x00, 0x00,
    0x47, 0x00, 0x04, 0x00, 0x31, 0x00, 0x00, 0x00, 0x1e, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x13, 0x00, 0x02, 0x00, 0x02, 0x00, 0x00, 0x00,
    0x21, 0x00, 0x03, 0x00, 0x03, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00,
    0x16, 0x00, 0x03, 0x00, 0x06, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00,
    0x17, 0x00, 0x04, 0x00, 0x07, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00,
    0x02, 0x00, 0x00, 0x00, 0x15, 0x00, 0x04, 0x00, 0x08, 0x00, 0x00, 0x00,
    0x20, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x2b, 0x00, 0x04, 0x00,
    0x08, 0x00, 0x00, 0x00, 0x09, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00,
    0x1c, 0x00, 0x04, 0x00, 0x0a, 0x00, 0x00, 0x00, 0x07, 0x00, 0x00, 0x00,
    0x09, 0x00, 0x00, 0x00, 0x20, 0x00, 0x04, 0x00, 0x0b, 0x00, 0x00, 0x00,
    0x07, 0x00, 0x00, 0x00, 0x0a, 0x00, 0x00, 0x00, 0x2b, 0x00, 0x04, 0x00,
    0x06, 0x00, 0x00, 0x00, 0x0d, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x2b, 0x00, 0x04, 0x00, 0x06, 0x00, 0x00, 0x00, 0x0e, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0xbf, 0x2c, 0x00, 0x05, 0x00, 0x07, 0x00, 0x00, 0x00,
    0x0f, 0x00, 0x00, 0x00, 0x0d, 0x00, 0x00, 0x00, 0x0e, 0x00, 0x00, 0x00,
    0x2b, 0x00, 0x04, 0x00, 0x06, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x3f, 0x2c, 0x00, 0x05, 0x00, 0x07, 0x00, 0x00, 0x00,
    0x11, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00,
    0x2c, 0x00, 0x05, 0x00, 0x07, 0x00, 0x00, 0x00, 0x12, 0x00, 0x00, 0x00,
    0x0e, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x06, 0x00,
    0x0a, 0x00, 0x00, 0x00, 0x13, 0x00, 0x00, 0x00, 0x0f, 0x00, 0x00, 0x00,
    0x11, 0x00, 0x00, 0x00, 0x12, 0x00, 0x00, 0x00, 0x17, 0x00, 0x04, 0x00,
    0x14, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00,
    0x1c, 0x00, 0x04, 0x00, 0x15, 0x00, 0x00, 0x00, 0x14, 0x00, 0x00, 0x00,
    0x09, 0x00, 0x00, 0x00, 0x20, 0x00, 0x04, 0x00, 0x16, 0x00, 0x00, 0x00,
    0x07, 0x00, 0x00, 0x00, 0x15, 0x00, 0x00, 0x00, 0x2b, 0x00, 0x04, 0x00,
    0x06, 0x00, 0x00, 0x00, 0x18, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80, 0x3f,
    0x2c, 0x00, 0x06, 0x00, 0x14, 0x00, 0x00, 0x00, 0x19, 0x00, 0x00, 0x00,
    0x18, 0x00, 0x00, 0x00, 0x0d, 0x00, 0x00, 0x00, 0x0d, 0x00, 0x00, 0x00,
    0x2c, 0x00, 0x06, 0x00, 0x14, 0x00, 0x00, 0x00, 0x1a, 0x00, 0x00, 0x00,
    0x0d, 0x00, 0x00, 0x00, 0x18, 0x00, 0x00, 0x00, 0x0d, 0x00, 0x00, 0x00,
    0x2c, 0x00, 0x06, 0x00, 0x14, 0x00, 0x00, 0x00, 0x1b, 0x00, 0x00, 0x00,
    0x0d, 0x00, 0x00, 0x00, 0x0d, 0x00, 0x00, 0x00, 0x18, 0x00, 0x00, 0x00,
    0x2c, 0x00, 0x06, 0x00, 0x15, 0x00, 0x00, 0x00, 0x1c, 0x00, 0x00, 0x00,
    0x19, 0x00, 0x00, 0x00, 0x1a, 0x00, 0x00, 0x00, 0x1b, 0x00, 0x00, 0x00,
    0x17, 0x00, 0x04, 0x00, 0x1d, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00,
    0x04, 0x00, 0x00, 0x00, 0x2b, 0x00, 0x04, 0x00, 0x08, 0x00, 0x00, 0x00,
    0x1e, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1c, 0x00, 0x04, 0x00,
    0x1f, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x1e, 0x00, 0x00, 0x00,
    0x1e, 0x00, 0x06, 0x00, 0x20, 0x00, 0x00, 0x00, 0x1d, 0x00, 0x00, 0x00,
    0x06, 0x00, 0x00, 0x00, 0x1f, 0x00, 0x00, 0x00, 0x1f, 0x00, 0x00, 0x00,
    0x20, 0x00, 0x04, 0x00, 0x21, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00,
    0x20, 0x00, 0x00, 0x00, 0x3b, 0x00, 0x04, 0x00, 0x21, 0x00, 0x00, 0x00,
    0x22, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x15, 0x00, 0x04, 0x00,
    0x23, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
    0x2b, 0x00, 0x04, 0x00, 0x23, 0x00, 0x00, 0x00, 0x24, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x20, 0x00, 0x04, 0x00, 0x25, 0x00, 0x00, 0x00,
    0x01, 0x00, 0x00, 0x00, 0x23, 0x00, 0x00, 0x00, 0x3b, 0x00, 0x04, 0x00,
    0x25, 0x00, 0x00, 0x00, 0x26, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
    0x20, 0x00, 0x04, 0x00, 0x28, 0x00, 0x00, 0x00, 0x07, 0x00, 0x00, 0x00,
    0x07, 0x00, 0x00, 0x00, 0x20, 0x00, 0x04, 0x00, 0x2e, 0x00, 0x00, 0x00,
    0x03, 0x00, 0x00, 0x00, 0x1d, 0x00, 0x00, 0x00, 0x20, 0x00, 0x04, 0x00,
    0x30, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x14, 0x00, 0x00, 0x00,
    0x3b, 0x00, 0x04, 0x00, 0x30, 0x00, 0x00, 0x00, 0x31, 0x00, 0x00, 0x00,
    0x03, 0x00, 0x00, 0x00, 0x20, 0x00, 0x04, 0x00, 0x33, 0x00, 0x00, 0x00,
    0x07, 0x00, 0x00, 0x00, 0x14, 0x00, 0x00, 0x00, 0x36, 0x00, 0x05, 0x00,
    0x02, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x03, 0x00, 0x00, 0x00, 0xf8, 0x00, 0x02, 0x00, 0x05, 0x00, 0x00, 0x00,
    0x3b, 0x00, 0x04, 0x00, 0x0b, 0x00, 0x00, 0x00, 0x0c, 0x00, 0x00, 0x00,
    0x07, 0x00, 0x00, 0x00, 0x3b, 0x00, 0x04, 0x00, 0x16, 0x00, 0x00, 0x00,
    0x17, 0x00, 0x00, 0x00, 0x07, 0x00, 0x00, 0x00, 0x3e, 0x00, 0x03, 0x00,
    0x0c, 0x00, 0x00, 0x00, 0x13, 0x00, 0x00, 0x00, 0x3e, 0x00, 0x03, 0x00,
    0x17, 0x00, 0x00, 0x00, 0x1c, 0x00, 0x00, 0x00, 0x3d, 0x00, 0x04, 0x00,
    0x23, 0x00, 0x00, 0x00, 0x27, 0x00, 0x00, 0x00, 0x26, 0x00, 0x00, 0x00,
    0x41, 0x00, 0x05, 0x00, 0x28, 0x00, 0x00, 0x00, 0x29, 0x00, 0x00, 0x00,
    0x0c, 0x00, 0x00, 0x00, 0x27, 0x00, 0x00, 0x00, 0x3d, 0x00, 0x04, 0x00,
    0x07, 0x00, 0x00, 0x00, 0x2a, 0x00, 0x00, 0x00, 0x29, 0x00, 0x00, 0x00,
    0x51, 0x00, 0x05, 0x00, 0x06, 0x00, 0x00, 0x00, 0x2b, 0x00, 0x00, 0x00,
    0x2a, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x51, 0x00, 0x05, 0x00,
    0x06, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, 0x2a, 0x00, 0x00, 0x00,
    0x01, 0x00, 0x00, 0x00, 0x50, 0x00, 0x07, 0x00, 0x1d, 0x00, 0x00, 0x00,
    0x2d, 0x00, 0x00, 0x00, 0x2b, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00,
    0x0d, 0x00, 0x00, 0x00, 0x18, 0x00, 0x00, 0x00, 0x41, 0x00, 0x05, 0x00,
    0x2e, 0x00, 0x00, 0x00, 0x2f, 0x00, 0x00, 0x00, 0x22, 0x00, 0x00, 0x00,
    0x24, 0x00, 0x00, 0x00, 0x3e, 0x00, 0x03, 0x00, 0x2f, 0x00, 0x00, 0x00,
    0x2d, 0x00, 0x00, 0x00, 0x3d, 0x00, 0x04, 0x00, 0x23, 0x00, 0x00, 0x00,
    0x32, 0x00, 0x00, 0x00, 0x26, 0x00, 0x00, 0x00, 0x41, 0x00, 0x05, 0x00,
    0x33, 0x00, 0x00, 0x00, 0x34, 0x00, 0x00, 0x00, 0x17, 0x00, 0x00, 0x00,
    0x32, 0x00, 0x00, 0x00, 0x3d, 0x00, 0x04, 0x00, 0x14, 0x00, 0x00, 0x00,
    0x35, 0x00, 0x00, 0x00, 0x34, 0x00, 0x00, 0x00, 0x3e, 0x00, 0x03, 0x00,
    0x31, 0x00, 0x00, 0x00, 0x35, 0x00, 0x00, 0x00, 0xfd, 0x00, 0x01, 0x00,
    0x38, 0x00, 0x01, 0x00,
};
static const uint32_t tri_frag_spv[] = {
    0x03, 0x02, 0x23, 0x07, 0x00, 0x00, 0x01, 0x00, 0x0a, 0x00, 0x0d, 0x00,
    0x13, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x11, 0x00, 0x02, 0x00,
    0x01, 0x00, 0x00, 0x00, 0x0b, 0x00, 0x06, 0x00, 0x01, 0x00, 0x00, 0x00,
    0x47, 0x4c, 0x53, 0x4c, 0x2e, 0x73, 0x74, 0x64, 0x2e, 0x34, 0x35, 0x30,
    0x00, 0x00, 0x00, 0x00, 0x0e, 0x00, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x01, 0x00, 0x00, 0x00, 0x0f, 0x00, 0x07, 0x00, 0x04, 0x00, 0x00, 0x00,
    0x04, 0x00, 0x00, 0x00, 0x6d, 0x61, 0x69, 0x6e, 0x00, 0x00, 0x00, 0x00,
    0x09, 0x00, 0x00, 0x00, 0x0c, 0x00, 0x00, 0x00, 0x10, 0x00, 0x03, 0x00,
    0x04, 0x00, 0x00, 0x00, 0x07, 0x00, 0x00, 0x00, 0x03, 0x00, 0x03, 0x00,
    0x02, 0x00, 0x00, 0x00, 0xc2, 0x01, 0x00, 0x00, 0x04, 0x00, 0x0a, 0x00,
    0x47, 0x4c, 0x5f, 0x47, 0x4f, 0x4f, 0x47, 0x4c, 0x45, 0x5f, 0x63, 0x70,
    0x70, 0x5f, 0x73, 0x74, 0x79, 0x6c, 0x65, 0x5f, 0x6c, 0x69, 0x6e, 0x65,
    0x5f, 0x64, 0x69, 0x72, 0x65, 0x63, 0x74, 0x69, 0x76, 0x65, 0x00, 0x00,
    0x04, 0x00, 0x08, 0x00, 0x47, 0x4c, 0x5f, 0x47, 0x4f, 0x4f, 0x47, 0x4c,
    0x45, 0x5f, 0x69, 0x6e, 0x63, 0x6c, 0x75, 0x64, 0x65, 0x5f, 0x64, 0x69,
    0x72, 0x65, 0x63, 0x74, 0x69, 0x76, 0x65, 0x00, 0x05, 0x00, 0x04, 0x00,
    0x04, 0x00, 0x00, 0x00, 0x6d, 0x61, 0x69, 0x6e, 0x00, 0x00, 0x00, 0x00,
    0x05, 0x00, 0x05, 0x00, 0x09, 0x00, 0x00, 0x00, 0x6f, 0x75, 0x74, 0x43,
    0x6f, 0x6c, 0x6f, 0x72, 0x00, 0x00, 0x00, 0x00, 0x05, 0x00, 0x05, 0x00,
    0x0c, 0x00, 0x00, 0x00, 0x66, 0x72, 0x61, 0x67, 0x43, 0x6f, 0x6c, 0x6f,
    0x72, 0x00, 0x00, 0x00, 0x47, 0x00, 0x04, 0x00, 0x09, 0x00, 0x00, 0x00,
    0x1e, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x47, 0x00, 0x04, 0x00,
    0x0c, 0x00, 0x00, 0x00, 0x1e, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x13, 0x00, 0x02, 0x00, 0x02, 0x00, 0x00, 0x00, 0x21, 0x00, 0x03, 0x00,
    0x03, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x16, 0x00, 0x03, 0x00,
    0x06, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00, 0x17, 0x00, 0x04, 0x00,
    0x07, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00,
    0x20, 0x00, 0x04, 0x00, 0x08, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00,
    0x07, 0x00, 0x00, 0x00, 0x3b, 0x00, 0x04, 0x00, 0x08, 0x00, 0x00, 0x00,
    0x09, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x17, 0x00, 0x04, 0x00,
    0x0a, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00,
    0x20, 0x00, 0x04, 0x00, 0x0b, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
    0x0a, 0x00, 0x00, 0x00, 0x3b, 0x00, 0x04, 0x00, 0x0b, 0x00, 0x00, 0x00,
    0x0c, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x2b, 0x00, 0x04, 0x00,
    0x06, 0x00, 0x00, 0x00, 0x0e, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80, 0x3f,
    0x36, 0x00, 0x05, 0x00, 0x02, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0xf8, 0x00, 0x02, 0x00,
    0x05, 0x00, 0x00, 0x00, 0x3d, 0x00, 0x04, 0x00, 0x0a, 0x00, 0x00, 0x00,
    0x0d, 0x00, 0x00, 0x00, 0x0c, 0x00, 0x00, 0x00, 0x51, 0x00, 0x05, 0x00,
    0x06, 0x00, 0x00, 0x00, 0x0f, 0x00, 0x00, 0x00, 0x0d, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x51, 0x00, 0x05, 0x00, 0x06, 0x00, 0x00, 0x00,
    0x10, 0x00, 0x00, 0x00, 0x0d, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
    0x51, 0x00, 0x05, 0x00, 0x06, 0x00, 0x00, 0x00, 0x11, 0x00, 0x00, 0x00,
    0x0d, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x50, 0x00, 0x07, 0x00,
    0x07, 0x00, 0x00, 0x00, 0x12, 0x00, 0x00, 0x00, 0x0f, 0x00, 0x00, 0x00,
    0x10, 0x00, 0x00, 0x00, 0x11, 0x00, 0x00, 0x00, 0x0e, 0x00, 0x00, 0x00,
    0x3e, 0x00, 0x03, 0x00, 0x09, 0x00, 0x00, 0x00, 0x12, 0x00, 0x00, 0x00,
    0xfd, 0x00, 0x01, 0x00, 0x38, 0x00, 0x01, 0x00,
};

// ── 渲染测试（vktest 同款）：自定义驱动 → 图形管线 → 离屏渲染三角形 60 帧 → FPS
// 离屏渲染不依赖窗口/EGL，turnip 驱动可直接执行图形管线；每帧同步 submit（保守但
// 能真实反映驱动渲染吞吐）。阶段错误分步返回，崩溃时由独立进程判定 DRIVER_CRASHED。
static std::string runRenderTest(VulkanDispatch& vk, VkInstance instance, VkPhysicalDevice phys,
                                 GetInstanceProcAddrFn getInst) {
    if (getInst == nullptr) {
        return "{\"success\":false,\"errorCode\":\"NO_GETINST\",\"errorMessage\":\"render: getInst null\","
               "\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }
    // 设备级函数
    using PFN_CreateDevice = VkResult (*)(VkPhysicalDevice, const VkDeviceCreateInfo*, const VkAllocationCallbacks*, VkDevice*);
    using PFN_GetDeviceQueue = void (*)(VkDevice, uint32_t, uint32_t, VkQueue*);
    using PFN_CreateShaderModule = VkResult (*)(VkDevice, const VkShaderModuleCreateInfo*, const VkAllocationCallbacks*, VkShaderModule*);
    using PFN_CreateRenderPass = VkResult (*)(VkDevice, const VkRenderPassCreateInfo*, const VkAllocationCallbacks*, VkRenderPass*);
    using PFN_CreateImageView = VkResult (*)(VkDevice, const VkImageViewCreateInfo*, const VkAllocationCallbacks*, VkImageView*);
    using PFN_CreateImage = VkResult (*)(VkDevice, const VkImageCreateInfo*, const VkAllocationCallbacks*, VkImage*);
    using PFN_CreateFramebuffer = VkResult (*)(VkDevice, const VkFramebufferCreateInfo*, const VkAllocationCallbacks*, VkFramebuffer*);
    using PFN_CreatePipelineLayout = VkResult (*)(VkDevice, const VkPipelineLayoutCreateInfo*, const VkAllocationCallbacks*, VkPipelineLayout*);
    using PFN_CreateGraphicsPipelines = VkResult (*)(VkDevice, VkPipelineCache, uint32_t, const VkGraphicsPipelineCreateInfo*, const VkAllocationCallbacks*, VkPipeline*);
    using PFN_CreateCommandPool = VkResult (*)(VkDevice, const VkCommandPoolCreateInfo*, const VkAllocationCallbacks*, VkCommandPool*);
    using PFN_AllocateCommandBuffers = VkResult (*)(VkDevice, const VkCommandBufferAllocateInfo*, VkCommandBuffer*);
    using PFN_AllocateMemory = VkResult (*)(VkDevice, const VkMemoryAllocateInfo*, const VkAllocationCallbacks*, VkDeviceMemory*);
    using PFN_BindImageMemory = VkResult (*)(VkDevice, VkImage, VkDeviceMemory, VkDeviceSize);
    using PFN_BeginCommandBuffer = VkResult (*)(VkCommandBuffer, const VkCommandBufferBeginInfo*);
    using PFN_CmdBeginRenderPass = void (*)(VkCommandBuffer, const VkRenderPassBeginInfo*, VkSubpassContents);
    using PFN_CmdDraw = void (*)(VkCommandBuffer, uint32_t, uint32_t, uint32_t, uint32_t);
    using PFN_CmdEndRenderPass = void (*)(VkCommandBuffer);
    using PFN_EndCommandBuffer = VkResult (*)(VkCommandBuffer);
    using PFN_QueueSubmit = VkResult (*)(VkQueue, uint32_t, const VkSubmitInfo*, VkFence);
    using PFN_DeviceWaitIdle = VkResult (*)(VkDevice);
    using PFN_GetMemoryRequirements = void (*)(VkDevice, VkImage, VkMemoryRequirements*);
    using PFN_DestroyShaderModule = void (*)(VkDevice, VkShaderModule, const VkAllocationCallbacks*);
    using PFN_DestroyRenderPass = void (*)(VkDevice, VkRenderPass, const VkAllocationCallbacks*);
    using PFN_DestroyImageView = void (*)(VkDevice, VkImageView, const VkAllocationCallbacks*);
    using PFN_DestroyImage = void (*)(VkDevice, VkImage, const VkAllocationCallbacks*);
    using PFN_DestroyFramebuffer = void (*)(VkDevice, VkFramebuffer, const VkAllocationCallbacks*);
    using PFN_DestroyPipelineLayout = void (*)(VkDevice, VkPipelineLayout, const VkAllocationCallbacks*);
    using PFN_DestroyPipeline = void (*)(VkDevice, VkPipeline, const VkAllocationCallbacks*);
    using PFN_DestroyCommandPool = void (*)(VkDevice, VkCommandPool, const VkAllocationCallbacks*);
    using PFN_DestroyDevice = void (*)(VkDevice, const VkAllocationCallbacks*);
    using PFN_GetDeviceProcAddr = PFN_vkVoidFunction (*)(VkDevice, const char*);

    auto devF = [&](const char* n) -> PFN_vkVoidFunction { return getInst(instance, n); };
    auto devFd = [&](const char* n) -> PFN_vkVoidFunction { return getInst(instance, n); };

    PFN_CreateDevice vkCreateDevice = reinterpret_cast<PFN_CreateDevice>(devF("vkCreateDevice"));
    if (!vkCreateDevice) return "{\"success\":false,\"errorCode\":\"NO_CREATE_DEVICE\",\"errorMessage\":\"render: no vkCreateDevice\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    PFN_GetDeviceQueue vkGetDeviceQueue = reinterpret_cast<PFN_GetDeviceQueue>(devF("vkGetDeviceQueue"));
    PFN_CreateShaderModule vkCreateShaderModule = reinterpret_cast<PFN_CreateShaderModule>(devF("vkCreateShaderModule"));
    PFN_CreateRenderPass vkCreateRenderPass = reinterpret_cast<PFN_CreateRenderPass>(devF("vkCreateRenderPass"));
    PFN_CreateImageView vkCreateImageView = reinterpret_cast<PFN_CreateImageView>(devF("vkCreateImageView"));
    PFN_CreateImage vkCreateImage = reinterpret_cast<PFN_CreateImage>(devF("vkCreateImage"));
    PFN_CreateFramebuffer vkCreateFramebuffer = reinterpret_cast<PFN_CreateFramebuffer>(devF("vkCreateFramebuffer"));
    PFN_CreatePipelineLayout vkCreatePipelineLayout = reinterpret_cast<PFN_CreatePipelineLayout>(devF("vkCreatePipelineLayout"));
    PFN_CreateGraphicsPipelines vkCreateGraphicsPipelines = reinterpret_cast<PFN_CreateGraphicsPipelines>(devF("vkCreateGraphicsPipelines"));
    PFN_CreateCommandPool vkCreateCommandPool = reinterpret_cast<PFN_CreateCommandPool>(devF("vkCreateCommandPool"));
    PFN_AllocateCommandBuffers vkAllocateCommandBuffers = reinterpret_cast<PFN_AllocateCommandBuffers>(devF("vkAllocateCommandBuffers"));
    PFN_AllocateMemory vkAllocateMemory = reinterpret_cast<PFN_AllocateMemory>(devF("vkAllocateMemory"));
    PFN_BindImageMemory vkBindImageMemory = reinterpret_cast<PFN_BindImageMemory>(devF("vkBindImageMemory"));
    PFN_GetMemoryRequirements vkGetMemoryRequirements = reinterpret_cast<PFN_GetMemoryRequirements>(devF("vkGetMemoryRequirements"));
    PFN_BeginCommandBuffer vkBeginCommandBuffer = reinterpret_cast<PFN_BeginCommandBuffer>(devF("vkBeginCommandBuffer"));
    PFN_CmdBeginRenderPass vkCmdBeginRenderPass = reinterpret_cast<PFN_CmdBeginRenderPass>(devF("vkCmdBeginRenderPass"));
    PFN_CmdDraw vkCmdDraw = reinterpret_cast<PFN_CmdDraw>(devF("vkCmdDraw"));
    PFN_CmdEndRenderPass vkCmdEndRenderPass = reinterpret_cast<PFN_CmdEndRenderPass>(devF("vkCmdEndRenderPass"));
    PFN_EndCommandBuffer vkEndCommandBuffer = reinterpret_cast<PFN_EndCommandBuffer>(devF("vkEndCommandBuffer"));
    PFN_QueueSubmit vkQueueSubmit = reinterpret_cast<PFN_QueueSubmit>(devF("vkQueueSubmit"));
    PFN_DeviceWaitIdle vkDeviceWaitIdle = reinterpret_cast<PFN_DeviceWaitIdle>(devF("vkDeviceWaitIdle"));
    PFN_DestroyShaderModule vkDestroyShaderModule = reinterpret_cast<PFN_DestroyShaderModule>(devF("vkDestroyShaderModule"));
    PFN_DestroyRenderPass vkDestroyRenderPass = reinterpret_cast<PFN_DestroyRenderPass>(devF("vkDestroyRenderPass"));
    PFN_DestroyImageView vkDestroyImageView = reinterpret_cast<PFN_DestroyImageView>(devF("vkDestroyImageView"));
    PFN_DestroyImage vkDestroyImage = reinterpret_cast<PFN_DestroyImage>(devF("vkDestroyImage"));
    PFN_DestroyFramebuffer vkDestroyFramebuffer = reinterpret_cast<PFN_DestroyFramebuffer>(devF("vkDestroyFramebuffer"));
    PFN_DestroyPipelineLayout vkDestroyPipelineLayout = reinterpret_cast<PFN_DestroyPipelineLayout>(devF("vkDestroyPipelineLayout"));
    PFN_DestroyPipeline vkDestroyPipeline = reinterpret_cast<PFN_DestroyPipeline>(devF("vkDestroyPipeline"));
    PFN_DestroyCommandPool vkDestroyCommandPool = reinterpret_cast<PFN_DestroyCommandPool>(devF("vkDestroyCommandPool"));
    PFN_DestroyDevice vkDestroyDevice = reinterpret_cast<PFN_DestroyDevice>(devF("vkDestroyDevice"));
    if (!vkGetDeviceQueue || !vkCreateShaderModule || !vkCreateRenderPass || !vkCreateImageView ||
        !vkCreateImage || !vkCreateFramebuffer || !vkCreatePipelineLayout || !vkCreateGraphicsPipelines ||
        !vkCreateCommandPool || !vkAllocateCommandBuffers || !vkAllocateMemory || !vkBindImageMemory ||
        !vkGetMemoryRequirements || !vkBeginCommandBuffer || !vkCmdBeginRenderPass || !vkCmdDraw ||
        !vkCmdEndRenderPass || !vkEndCommandBuffer || !vkQueueSubmit || !vkDeviceWaitIdle) {
        return "{\"success\":false,\"errorCode\":\"RENDER_FUNCS_MISSING\",\"errorMessage\":\"render: driver lacks device funcs\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }

    // 找 graphics queue family
    uint32_t qfCount = 0;
    vk.vkGetPhysicalDeviceQueueFamilyProperties(phys, &qfCount, nullptr);
    std::vector<VkQueueFamilyProperties> qfs(qfCount);
    vk.vkGetPhysicalDeviceQueueFamilyProperties(phys, &qfCount, qfs.data());
    int32_t gfxQf = -1;
    for (uint32_t i = 0; i < qfCount; i++) {
        if (qfs[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) { gfxQf = static_cast<int32_t>(i); break; }
    }
    if (gfxQf < 0) {
        return "{\"success\":false,\"errorCode\":\"NO_GRAPHICS_QUEUE\",\"errorMessage\":\"render: no graphics queue\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }

    // 创建 device
    const float qp = 1.0f;
    VkDeviceQueueCreateInfo dq = {};
    dq.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    dq.queueFamilyIndex = static_cast<uint32_t>(gfxQf);
    dq.queueCount = 1;
    dq.pQueuePriorities = &qp;
    VkDeviceCreateInfo dci = {};
    dci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &dq;
    VkDevice device = VK_NULL_HANDLE;
    if (vkCreateDevice(phys, &dci, nullptr, &device) != VK_SUCCESS || device == VK_NULL_HANDLE) {
        return "{\"success\":false,\"errorCode\":\"DEVICE_CREATE_FAILED\",\"errorMessage\":\"render: vkCreateDevice failed\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }
    VkQueue queue = VK_NULL_HANDLE;
    vkGetDeviceQueue(device, static_cast<uint32_t>(gfxQf), 0, &queue);

    // shader modules
    VkShaderModule vs = VK_NULL_HANDLE, fs = VK_NULL_HANDLE;
    VkShaderModuleCreateInfo vsci = {};
    vsci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    vsci.codeSize = sizeof(tri_vert_spv);
    vsci.pCode = tri_vert_spv;
    if (vkCreateShaderModule(device, &vsci, nullptr, &vs) != VK_SUCCESS) {
        vkDestroyDevice(device, nullptr);
        return "{\"success\":false,\"errorCode\":\"VS_MODULE_FAILED\",\"errorMessage\":\"render: vertex shader module failed\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }
    VkShaderModuleCreateInfo fsci = {};
    fsci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    fsci.codeSize = sizeof(tri_frag_spv);
    fsci.pCode = tri_frag_spv;
    if (vkCreateShaderModule(device, &fsci, nullptr, &fs) != VK_SUCCESS) {
        vkDestroyShaderModule(device, vs, nullptr);
        vkDestroyDevice(device, nullptr);
        return "{\"success\":false,\"errorCode\":\"FS_MODULE_FAILED\",\"errorMessage\":\"render: fragment shader module failed\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }

    // renderpass
    VkAttachmentDescription att = {};
    att.format = VK_FORMAT_R8G8B8A8_UNORM;
    att.samples = VK_SAMPLE_COUNT_1_BIT;
    att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    att.finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    VkAttachmentReference colRef = {};
    colRef.attachment = 0;
    colRef.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    VkSubpassDescription sub = {};
    sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount = 1;
    sub.pColorAttachments = &colRef;
    VkRenderPassCreateInfo rpci = {};
    rpci.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    rpci.attachmentCount = 1;
    rpci.pAttachments = &att;
    rpci.subpassCount = 1;
    rpci.pSubpasses = &sub;
    VkRenderPass rp = VK_NULL_HANDLE;
    if (vkCreateRenderPass(device, &rpci, nullptr, &rp) != VK_SUCCESS) {
        vkDestroyShaderModule(device, vs, nullptr);
        vkDestroyShaderModule(device, fs, nullptr);
        vkDestroyDevice(device, nullptr);
        return "{\"success\":false,\"errorCode\":\"RENDERPASS_FAILED\",\"errorMessage\":\"render: renderpass failed\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }

    const uint32_t W = 256, H = 256;
    // 离屏 image
    VkImageCreateInfo ici = {};
    ici.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = VK_FORMAT_R8G8B8A8_UNORM;
    ici.extent = { W, H, 1 };
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkImage img = VK_NULL_HANDLE;
    if (vkCreateImage(device, &ici, nullptr, &img) != VK_SUCCESS) {
        vkDestroyRenderPass(device, rp, nullptr);
        vkDestroyShaderModule(device, vs, nullptr);
        vkDestroyShaderModule(device, fs, nullptr);
        vkDestroyDevice(device, nullptr);
        return "{\"success\":false,\"errorCode\":\"IMAGE_CREATE_FAILED\",\"errorMessage\":\"render: offscreen image failed\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }
    VkMemoryRequirements memReq = {};
    vkGetMemoryRequirements(device, img, &memReq);
    VkMemoryAllocateInfo mai = {};
    mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mai.allocationSize = memReq.size;
    // 内存类型：找 host 可见或设备本地（简单取第一个支持位）
    VkPhysicalDeviceMemoryProperties memProps = {};
    vk.vkGetPhysicalDeviceMemoryProperties(phys, &memProps);
    uint32_t memType = 0xFFFFFFFF;
    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        if (memReq.memoryTypeBits & (1u << i)) { memType = i; break; }
    }
    if (memType == 0xFFFFFFFF) {
        vkDestroyImage(device, img, nullptr);
        vkDestroyRenderPass(device, rp, nullptr);
        vkDestroyShaderModule(device, vs, nullptr);
        vkDestroyShaderModule(device, fs, nullptr);
        vkDestroyDevice(device, nullptr);
        return "{\"success\":false,\"errorCode\":\"NO_MEM_TYPE\",\"errorMessage\":\"render: no memory type\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }
    mai.memoryTypeIndex = memType;
    VkDeviceMemory imgMem = VK_NULL_HANDLE;
    if (vkAllocateMemory(device, &mai, nullptr, &imgMem) != VK_SUCCESS ||
        vkBindImageMemory(device, img, imgMem, 0) != VK_SUCCESS) {
        vkDestroyImage(device, img, nullptr);
        vkDestroyRenderPass(device, rp, nullptr);
        vkDestroyShaderModule(device, vs, nullptr);
        vkDestroyShaderModule(device, fs, nullptr);
        vkDestroyDevice(device, nullptr);
        return "{\"success\":false,\"errorCode\":\"MEM_BIND_FAILED\",\"errorMessage\":\"render: image memory failed\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }
    VkImageViewCreateInfo ivci = {};
    ivci.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    ivci.image = img;
    ivci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    ivci.format = VK_FORMAT_R8G8B8A8_UNORM;
    ivci.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
    VkImageView iv = VK_NULL_HANDLE;
    if (vkCreateImageView(device, &ivci, nullptr, &iv) != VK_SUCCESS) {
        vkDestroyImage(device, img, nullptr);
        vkDestroyRenderPass(device, rp, nullptr);
        vkDestroyShaderModule(device, vs, nullptr);
        vkDestroyShaderModule(device, fs, nullptr);
        vkDestroyDevice(device, nullptr);
        return "{\"success\":false,\"errorCode\":\"IMAGEVIEW_FAILED\",\"errorMessage\":\"render: image view failed\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }
    VkFramebufferCreateInfo fbci = {};
    fbci.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
    fbci.renderPass = rp;
    fbci.attachmentCount = 1;
    fbci.pAttachments = &iv;
    fbci.width = W;
    fbci.height = H;
    fbci.layers = 1;
    VkFramebuffer fb = VK_NULL_HANDLE;
    if (vkCreateFramebuffer(device, &fbci, nullptr, &fb) != VK_SUCCESS) {
        vkDestroyImageView(device, iv, nullptr);
        vkDestroyImage(device, img, nullptr);
        vkDestroyRenderPass(device, rp, nullptr);
        vkDestroyShaderModule(device, vs, nullptr);
        vkDestroyShaderModule(device, fs, nullptr);
        vkDestroyDevice(device, nullptr);
        return "{\"success\":false,\"errorCode\":\"FRAMEBUFFER_FAILED\",\"errorMessage\":\"render: framebuffer failed\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }

    // pipeline
    VkPipelineShaderStageCreateInfo stages[2] = {};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vs;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fs;
    stages[1].pName = "main";
    VkPipelineLayoutCreateInfo plci = {};
    plci.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    VkPipelineLayout pl = VK_NULL_HANDLE;
    if (vkCreatePipelineLayout(device, &plci, nullptr, &pl) != VK_SUCCESS) {
        vkDestroyFramebuffer(device, fb, nullptr);
        vkDestroyImageView(device, iv, nullptr);
        vkDestroyImage(device, img, nullptr);
        vkDestroyRenderPass(device, rp, nullptr);
        vkDestroyShaderModule(device, vs, nullptr);
        vkDestroyShaderModule(device, fs, nullptr);
        vkDestroyDevice(device, nullptr);
        return "{\"success\":false,\"errorCode\":\"PIPELINE_LAYOUT_FAILED\",\"errorMessage\":\"render: pipeline layout failed\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }
    VkPipelineVertexInputStateCreateInfo vi = {};
    vi.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    VkPipelineInputAssemblyStateCreateInfo ia = {};
    ia.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo vp = {};
    vp.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    VkViewport viewport = { 0.0f, 0.0f, static_cast<float>(W), static_cast<float>(H), 0.0f, 1.0f };
    VkRect2D scissor = { {0, 0}, {W, H} };
    vp.viewportCount = 1;
    vp.pViewports = &viewport;
    vp.scissorCount = 1;
    vp.pScissors = &scissor;
    VkPipelineRasterizationStateCreateInfo rs = {};
    rs.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    rs.polygonMode = VK_POLYGON_MODE_FILL;
    rs.cullMode = VK_CULL_MODE_NONE;
    rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    rs.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo ms = {};
    ms.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState ba = {};
    ba.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                        VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo cb = {};
    cb.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    cb.attachmentCount = 1;
    cb.pAttachments = &ba;
    VkGraphicsPipelineCreateInfo gpci = {};
    gpci.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    gpci.stageCount = 2;
    gpci.pStages = stages;
    gpci.pVertexInputState = &vi;
    gpci.pInputAssemblyState = &ia;
    gpci.pViewportState = &vp;
    gpci.pRasterizationState = &rs;
    gpci.pMultisampleState = &ms;
    gpci.pColorBlendState = &cb;
    gpci.layout = pl;
    gpci.renderPass = rp;
    gpci.subpass = 0;
    VkPipeline pipe = VK_NULL_HANDLE;
    if (vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &gpci, nullptr, &pipe) != VK_SUCCESS || pipe == VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(device, pl, nullptr);
        vkDestroyFramebuffer(device, fb, nullptr);
        vkDestroyImageView(device, iv, nullptr);
        vkDestroyImage(device, img, nullptr);
        vkDestroyRenderPass(device, rp, nullptr);
        vkDestroyShaderModule(device, vs, nullptr);
        vkDestroyShaderModule(device, fs, nullptr);
        vkDestroyDevice(device, nullptr);
        return "{\"success\":false,\"errorCode\":\"PIPELINE_FAILED\",\"errorMessage\":\"render: graphics pipeline failed\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }

    // command buffer
    VkCommandPoolCreateInfo cpci = {};
    cpci.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    cpci.queueFamilyIndex = static_cast<uint32_t>(gfxQf);
    VkCommandPool pool = VK_NULL_HANDLE;
    if (vkCreateCommandPool(device, &cpci, nullptr, &pool) != VK_SUCCESS) {
        vkDestroyPipeline(device, pipe, nullptr);
        vkDestroyPipelineLayout(device, pl, nullptr);
        vkDestroyFramebuffer(device, fb, nullptr);
        vkDestroyImageView(device, iv, nullptr);
        vkDestroyImage(device, img, nullptr);
        vkDestroyRenderPass(device, rp, nullptr);
        vkDestroyShaderModule(device, vs, nullptr);
        vkDestroyShaderModule(device, fs, nullptr);
        vkDestroyDevice(device, nullptr);
        return "{\"success\":false,\"errorCode\":\"CMDPOOL_FAILED\",\"errorMessage\":\"render: command pool failed\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }
    VkCommandBufferAllocateInfo cba = {};
    cba.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cba.commandPool = pool;
    cba.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cba.commandBufferCount = 1;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    if (vkAllocateCommandBuffers(device, &cba, &cmd) != VK_SUCCESS) {
        vkDestroyCommandPool(device, pool, nullptr);
        vkDestroyPipeline(device, pipe, nullptr);
        vkDestroyPipelineLayout(device, pl, nullptr);
        vkDestroyFramebuffer(device, fb, nullptr);
        vkDestroyImageView(device, iv, nullptr);
        vkDestroyImage(device, img, nullptr);
        vkDestroyRenderPass(device, rp, nullptr);
        vkDestroyShaderModule(device, vs, nullptr);
        vkDestroyShaderModule(device, fs, nullptr);
        vkDestroyDevice(device, nullptr);
        return "{\"success\":false,\"errorCode\":\"CMDBUF_FAILED\",\"errorMessage\":\"render: command buffer failed\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }

    // 帧循环：60 帧离屏渲染，每帧同步 submit（保守口径，真实反映渲染吞吐）
    const int FRAMES = 60;
    VkClearValue clear = {};
    clear.color = { {0.2f, 0.2f, 0.3f, 1.0f} };
    auto t0 = std::chrono::steady_clock::now();
    for (int f = 0; f < FRAMES; f++) {
        VkCommandBufferBeginInfo cbbi = {};
        cbbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        VkRenderPassBeginInfo rpbi = {};
        rpbi.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        rpbi.renderPass = rp;
        rpbi.framebuffer = fb;
        rpbi.renderArea = { {0, 0}, {W, H} };
        rpbi.clearValueCount = 1;
        rpbi.pClearValues = &clear;
        if (vkBeginCommandBuffer(cmd, &cbbi) != VK_SUCCESS) {
            vkDestroyCommandPool(device, pool, nullptr);
            vkDestroyPipeline(device, pipe, nullptr);
            vkDestroyPipelineLayout(device, pl, nullptr);
            vkDestroyFramebuffer(device, fb, nullptr);
            vkDestroyImageView(device, iv, nullptr);
            vkDestroyImage(device, img, nullptr);
            vkDestroyRenderPass(device, rp, nullptr);
            vkDestroyShaderModule(device, vs, nullptr);
            vkDestroyShaderModule(device, fs, nullptr);
            vkDestroyDevice(device, nullptr);
            return "{\"success\":false,\"errorCode\":\"BEGIN_CMDBUF_FAILED\",\"errorMessage\":\"render: begin cmd failed\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
        }
        vkCmdBeginRenderPass(cmd, &rpbi, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdDraw(cmd, 3, 1, 0, 0);
        vkCmdEndRenderPass(cmd);
        if (vkEndCommandBuffer(cmd) != VK_SUCCESS) {
            vkDestroyCommandPool(device, pool, nullptr);
            vkDestroyPipeline(device, pipe, nullptr);
            vkDestroyPipelineLayout(device, pl, nullptr);
            vkDestroyFramebuffer(device, fb, nullptr);
            vkDestroyImageView(device, iv, nullptr);
            vkDestroyImage(device, img, nullptr);
            vkDestroyRenderPass(device, rp, nullptr);
            vkDestroyShaderModule(device, vs, nullptr);
            vkDestroyShaderModule(device, fs, nullptr);
            vkDestroyDevice(device, nullptr);
            return "{\"success\":false,\"errorCode\":\"END_CMDBUF_FAILED\",\"errorMessage\":\"render: end cmd failed\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
        }
        VkSubmitInfo si = {};
        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd;
        if (vkQueueSubmit(queue, 1, &si, VK_NULL_HANDLE) != VK_SUCCESS) {
            vkDestroyCommandPool(device, pool, nullptr);
            vkDestroyPipeline(device, pipe, nullptr);
            vkDestroyPipelineLayout(device, pl, nullptr);
            vkDestroyFramebuffer(device, fb, nullptr);
            vkDestroyImageView(device, iv, nullptr);
            vkDestroyImage(device, img, nullptr);
            vkDestroyRenderPass(device, rp, nullptr);
            vkDestroyShaderModule(device, vs, nullptr);
            vkDestroyShaderModule(device, fs, nullptr);
            vkDestroyDevice(device, nullptr);
            return "{\"success\":false,\"errorCode\":\"SUBMIT_FAILED\",\"errorMessage\":\"render: queue submit failed\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
        }
        vkDeviceWaitIdle(device);
    }
    auto t1 = std::chrono::steady_clock::now();
    double secs = std::chrono::duration<double>(t1 - t0).count();
    if (secs <= 0) secs = 1e-9;

    // 清理
    vkDestroyCommandPool(device, pool, nullptr);
    vkDestroyPipeline(device, pipe, nullptr);
    vkDestroyPipelineLayout(device, pl, nullptr);
    vkDestroyFramebuffer(device, fb, nullptr);
    vkDestroyImageView(device, iv, nullptr);
    vkDestroyImage(device, img, nullptr);
    vkDestroyRenderPass(device, rp, nullptr);
    vkDestroyShaderModule(device, vs, nullptr);
    vkDestroyShaderModule(device, fs, nullptr);
    vkDestroyDevice(device, nullptr);

    char buf[256];
    snprintf(buf, sizeof(buf),
             "{\"success\":true,\"errorCode\":\"\",\"errorMessage\":\"\","
             "\"fps\":%.1f,\"avgFrameMs\":%.2f,\"frameCount\":%d}",
             FRAMES / secs, secs * 1000.0 / FRAMES, FRAMES);
    return std::string(buf);
}

// Loader 路径渲染测试（vktest 同款）：加载自定义驱动 → 离屏渲染三角形 → FPS
static std::string renderDriverViaLoaderJson(const char* driverDir, const char* driverName,
                                             const char* tmpDir, const char* hookLibDir) {
    void* lib = nullptr;
    GetInstanceProcAddrFn getInst = nullptr;
    std::string err = loadDriverViaLoader(driverDir, driverName, tmpDir, &lib, &getInst);
    if (!err.empty()) return err;

    VulkanDispatch vk;
    vk.libHandle = lib;
    vk.vkCreateInstance = getInst
            ? reinterpret_cast<PFN_CreateInstance>(getInst(VK_NULL_HANDLE, "vkCreateInstance"))
            : nullptr;
    if (!vk.vkCreateInstance) {
        vk.vkCreateInstance = reinterpret_cast<PFN_CreateInstance>(dlsym(lib, "vkCreateInstance"));
    }
    vk.vkDestroyInstance = getInst
            ? reinterpret_cast<PFN_DestroyInstance>(getInst(VK_NULL_HANDLE, "vkDestroyInstance"))
            : nullptr;
    if (!vk.vkDestroyInstance) {
        vk.vkDestroyInstance = reinterpret_cast<PFN_DestroyInstance>(dlsym(lib, "vkDestroyInstance"));
    }
    if (!vk.vkCreateInstance || !vk.vkDestroyInstance) {
        dlclose(lib);
        unsetenv("VK_ICD_FILENAMES");
        return "{\"success\":false,\"errorCode\":\"INSTANCE_FUNCS_MISSING\",\"errorMessage\":\"render: driver lacks instance funcs\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }
    VkInstance instance = createVulkanInstance(vk.vkCreateInstance);
    if (instance == VK_NULL_HANDLE) {
        dlclose(lib);
        unsetenv("VK_ICD_FILENAMES");
        return "{\"success\":false,\"errorCode\":\"CREATE_INSTANCE_FAILED\",\"errorMessage\":\"render: vkCreateInstance failed\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }
    vk.vkEnumeratePhysicalDevices = reinterpret_cast<PFN_EnumeratePhysicalDevices>(
            getInst(instance, "vkEnumeratePhysicalDevices"));
    vk.vkGetPhysicalDeviceProperties = reinterpret_cast<PFN_GetPhysicalDeviceProperties>(
            getInst(instance, "vkGetPhysicalDeviceProperties"));
    vk.vkGetPhysicalDeviceQueueFamilyProperties = reinterpret_cast<PFN_GetPhysicalDeviceQueueFamilyProperties>(
            getInst(instance, "vkGetPhysicalDeviceQueueFamilyProperties"));
    vk.vkGetPhysicalDeviceMemoryProperties = reinterpret_cast<PFN_GetPhysicalDeviceMemoryProperties>(
            getInst(instance, "vkGetPhysicalDeviceMemoryProperties"));
    if (!vk.vkEnumeratePhysicalDevices || !vk.vkGetPhysicalDeviceQueueFamilyProperties ||
        !vk.vkGetPhysicalDeviceMemoryProperties) {
        vk.vkDestroyInstance(instance, nullptr);
        dlclose(lib);
        unsetenv("VK_ICD_FILENAMES");
        return "{\"success\":false,\"errorCode\":\"PHYS_FUNCS_MISSING\",\"errorMessage\":\"render: driver lacks phys funcs\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }
    uint32_t deviceCount = 0;
    vk.vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
    if (deviceCount == 0) {
        vk.vkDestroyInstance(instance, nullptr);
        dlclose(lib);
        unsetenv("VK_ICD_FILENAMES");
        return "{\"success\":false,\"errorCode\":\"NO_DEVICE\",\"errorMessage\":\"render: no physical devices\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }
    std::vector<VkPhysicalDevice> devices(deviceCount);
    vk.vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data());
    std::string res = runRenderTest(vk, instance, devices[0], getInst);
    vk.vkDestroyInstance(instance, nullptr);
    dlclose(lib);
    unsetenv("VK_ICD_FILENAMES");
    return res;
}

// AdrenoTools 路径跑分：fill/copy 带宽 + 计算吞吐（复用 runBenchmark）
static std::string benchmarkDriverAdrenotoolsJson(const char* driverDir, const char* driverName,
                                                  const char* tmpDir, const char* hookLibDir) {
    void* lib = nullptr;
    GetInstanceProcAddrFn getInst = nullptr;
    std::string err = loadDriverAdrenoTools(driverDir, driverName, tmpDir, hookLibDir, false, &lib, &getInst);
    if (!err.empty()) return err;

    VulkanDispatch vk;
    vk.libHandle = lib;
    // 同 collect 路径：classic 入口对 NULL 实例查询返回空时，dlsym 直取符号。
    vk.vkCreateInstance = getInst
            ? reinterpret_cast<PFN_CreateInstance>(getInst(VK_NULL_HANDLE, "vkCreateInstance"))
            : nullptr;
    if (!vk.vkCreateInstance) {
        vk.vkCreateInstance = reinterpret_cast<PFN_CreateInstance>(dlsym(lib, "vkCreateInstance"));
    }
    vk.vkDestroyInstance = getInst
            ? reinterpret_cast<PFN_DestroyInstance>(getInst(VK_NULL_HANDLE, "vkDestroyInstance"))
            : nullptr;
    if (!vk.vkDestroyInstance) {
        vk.vkDestroyInstance = reinterpret_cast<PFN_DestroyInstance>(dlsym(lib, "vkDestroyInstance"));
    }
    if (!vk.vkCreateInstance || !vk.vkDestroyInstance) {
        const char* symCreate = dlsym(lib, "vkCreateInstance") ? "1" : "0";
        const char* symIcd = dlsym(lib, "vk_icdGetInstanceProcAddr") ? "1" : "0";
        const char* symClassic = dlsym(lib, "vkGetInstanceProcAddr") ? "1" : "0";
        LOGI("AdrenoTools benchmark INSTANCE_FUNCS_MISSING: sym_create=%s icd=%s classic=%s",
             symCreate, symIcd, symClassic);
        dlclose(lib);
        return "{\"success\":false,\"errorCode\":\"INSTANCE_FUNCS_MISSING\",\"errorMessage\":\""
               "driver lacks vkCreateInstance/vkDestroyInstance [diag: sym_create=" + std::string(symCreate)
               + " icd=" + std::string(symIcd) + " classic=" + std::string(symClassic)
               + "]\",\"fillBandwidthGBs\":0,"
               "\"copyBandwidthGBs\":0,\"computeGFLOPS\":0,\"computeError\":\"\",\"totalScore\":0}";
    }

    VkInstance instance = createVulkanInstance(vk.vkCreateInstance);
    if (instance == VK_NULL_HANDLE) {
        dlclose(lib);
        return "{\"success\":false,\"errorCode\":\"CREATE_INSTANCE_FAILED\",\"errorMessage\":\""
               "driver vkCreateInstance failed\",\"fillBandwidthGBs\":0,\"copyBandwidthGBs\":0,"
               "\"computeGFLOPS\":0,\"computeError\":\"\",\"totalScore\":0}";
    }

    vk.vkEnumeratePhysicalDevices = reinterpret_cast<PFN_EnumeratePhysicalDevices>(
            getInst(instance, "vkEnumeratePhysicalDevices"));
    vk.vkGetPhysicalDeviceProperties = reinterpret_cast<PFN_GetPhysicalDeviceProperties>(
            getInst(instance, "vkGetPhysicalDeviceProperties"));
    vk.vkGetPhysicalDeviceMemoryProperties = reinterpret_cast<PFN_GetPhysicalDeviceMemoryProperties>(
            getInst(instance, "vkGetPhysicalDeviceMemoryProperties"));
    vk.vkGetPhysicalDeviceQueueFamilyProperties = reinterpret_cast<PFN_GetPhysicalDeviceQueueFamilyProperties>(
            getInst(instance, "vkGetPhysicalDeviceQueueFamilyProperties"));

    uint32_t deviceCount = 0;
    if (!vk.vkEnumeratePhysicalDevices || vk.vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr) != VK_SUCCESS
            || deviceCount == 0) {
        vk.vkDestroyInstance(instance, nullptr);
        dlclose(lib);
        return "{\"success\":false,\"errorCode\":\"NO_DEVICE\",\"errorMessage\":\""
               "driver reports no Vulkan physical device\",\"fillBandwidthGBs\":0,"
               "\"copyBandwidthGBs\":0,\"computeGFLOPS\":0,\"computeError\":\"\",\"totalScore\":0}";
    }
    std::vector<VkPhysicalDevice> devices(deviceCount);
    vk.vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data());

    std::string result = runBenchmark(vk, instance, devices[0], getInst, true);
    vk.vkDestroyInstance(instance, nullptr);
    dlclose(lib);
    return result;
}

// ── JNI 入口：AdrenoTools 实测采集 ────────────────────────────────────────
// Kotlin: com.emubox.app.NativeVulkanBridge.collectDriverAdrenotools(...)
// 参数均为 JNI 字符串：driverDir/driverName/tmpDir/hookLibDir
extern "C" JNIEXPORT jstring JNICALL
Java_com_emubox_app_NativeVulkanBridge_collectDriverAdrenotools(
        JNIEnv* env, jobject /* thiz */, jstring driverDir, jstring driverName,
        jstring tmpDir, jstring hookLibDir) {
    const char* dd = driverDir ? env->GetStringUTFChars(driverDir, nullptr) : nullptr;
    const char* dn = driverName ? env->GetStringUTFChars(driverName, nullptr) : nullptr;
    const char* td = tmpDir ? env->GetStringUTFChars(tmpDir, nullptr) : nullptr;
    const char* hl = hookLibDir ? env->GetStringUTFChars(hookLibDir, nullptr) : nullptr;
    if (!dd || !dn || !td || !hl) {
        if (dd) env->ReleaseStringUTFChars(driverDir, dd);
        if (dn) env->ReleaseStringUTFChars(driverName, dn);
        if (td) env->ReleaseStringUTFChars(tmpDir, td);
        if (hl) env->ReleaseStringUTFChars(hookLibDir, hl);
        return env->NewStringUTF("{\"success\":false,\"errorCode\":\"BAD_ARGS\",\"errorMessage\":\"missing args\",\"deviceCount\":0,\"devices\":[]}");
    }
    std::string json = runBenchmarkIsolated([&]() {
        return collectDriverAdrenotoolsJson(dd, dn, td, hl);
    });
    env->ReleaseStringUTFChars(driverDir, dd);
    env->ReleaseStringUTFChars(driverName, dn);
    env->ReleaseStringUTFChars(tmpDir, td);
    env->ReleaseStringUTFChars(hookLibDir, hl);
    return env->NewStringUTF(json.c_str());
}

// ── JNI 入口：AdrenoTools 实测跑分 ─────────────────────────────────────────
// Kotlin: com.emubox.app.NativeVulkanBridge.benchmarkDriverAdrenotools(...)
extern "C" JNIEXPORT jstring JNICALL
Java_com_emubox_app_NativeVulkanBridge_benchmarkDriverAdrenotools(
        JNIEnv* env, jobject /* thiz */, jstring driverDir, jstring driverName,
        jstring tmpDir, jstring hookLibDir) {
    const char* dd = driverDir ? env->GetStringUTFChars(driverDir, nullptr) : nullptr;
    const char* dn = driverName ? env->GetStringUTFChars(driverName, nullptr) : nullptr;
    const char* td = tmpDir ? env->GetStringUTFChars(tmpDir, nullptr) : nullptr;
    const char* hl = hookLibDir ? env->GetStringUTFChars(hookLibDir, nullptr) : nullptr;
    if (!dd || !dn || !td || !hl) {
        if (dd) env->ReleaseStringUTFChars(driverDir, dd);
        if (dn) env->ReleaseStringUTFChars(driverName, dn);
        if (td) env->ReleaseStringUTFChars(tmpDir, td);
        if (hl) env->ReleaseStringUTFChars(hookLibDir, hl);
        return env->NewStringUTF("{\"success\":false,\"errorCode\":\"BAD_ARGS\",\"errorMessage\":\"missing args\",\"fillBandwidthGBs\":0,\"copyBandwidthGBs\":0,\"computeGFLOPS\":0,\"computeError\":\"\",\"totalScore\":0}");
    }
    std::string json = runBenchmarkIsolated([&]() {
        return benchmarkDriverAdrenotoolsJson(dd, dn, td, hl);
    });
    env->ReleaseStringUTFChars(driverDir, dd);
    env->ReleaseStringUTFChars(driverName, dn);
    env->ReleaseStringUTFChars(tmpDir, td);
    env->ReleaseStringUTFChars(hookLibDir, hl);
    return env->NewStringUTF(json.c_str());
}

// ── JNI 入口（独立进程版）：AdrenoTools 采集，不 fork ───────────────────────
// 由 :drivertest service 进程调用。该进程由系统全新启动，未初始化系统
// Vulkan/GPU 状态，adrenotools 加载 turnip/panvk 时 GPU 状态干净，
// 与 vktest（独立 App 进程加载）同构，规避主进程 fork 继承 GPU 状态
// 导致的 DRIVER_CRASHED。驱动崩溃只杀 service 进程，主进程无感。
extern "C" JNIEXPORT jstring JNICALL
Java_com_emubox_app_NativeVulkanBridge_collectDriverAdrenotoolsInProcess(
        JNIEnv* env, jobject /* thiz */, jstring driverDir, jstring driverName,
        jstring tmpDir, jstring hookLibDir) {
    const char* dd = driverDir ? env->GetStringUTFChars(driverDir, nullptr) : nullptr;
    const char* dn = driverName ? env->GetStringUTFChars(driverName, nullptr) : nullptr;
    const char* td = tmpDir ? env->GetStringUTFChars(tmpDir, nullptr) : nullptr;
    const char* hl = hookLibDir ? env->GetStringUTFChars(hookLibDir, nullptr) : nullptr;
    if (!dd || !dn || !td || !hl) {
        if (dd) env->ReleaseStringUTFChars(driverDir, dd);
        if (dn) env->ReleaseStringUTFChars(driverName, dn);
        if (td) env->ReleaseStringUTFChars(tmpDir, td);
        if (hl) env->ReleaseStringUTFChars(hookLibDir, hl);
        return env->NewStringUTF("{\"success\":false,\"errorCode\":\"BAD_ARGS\",\"errorMessage\":\"missing args\",\"deviceCount\":0,\"devices\":[]}");
    }
    // 双路径：vktest 同款 loader 优先；失败（驱动与 loader 不兼容等）回退 adrenotools
    std::string json = collectDriverViaLoaderJson(dd, dn, td, hl);
    if (json.find("\"success\":false") != std::string::npos) {
        std::string fb = collectDriverAdrenotoolsJson(dd, dn, td, hl);
        if (fb.find("\"success\":false") == std::string::npos) {
            json = fb;  // fallback 成功，采用 adrenotools 结果（loader 错误已记录日志）
        } else {
            // 双路径均失败：返回 loader 错误（更接近 vktest 根因），附 fallback 错误
            std::string fbMsg = " | fallback: " + fb;
            json = "{\"success\":false,\"errorCode\":\"DUAL_PATH_FAILED\",\"errorMessage\":\""
                   + jsonEscape("loader+adrenotools both failed: " + fbMsg) + "\",\"deviceCount\":0,\"devices\":[]}";
        }
    }
    env->ReleaseStringUTFChars(driverDir, dd);
    env->ReleaseStringUTFChars(driverName, dn);
    env->ReleaseStringUTFChars(tmpDir, td);
    env->ReleaseStringUTFChars(hookLibDir, hl);
    return env->NewStringUTF(json.c_str());
}

// ── JNI 入口（独立进程版）：AdrenoTools 跑分，不 fork ──────────────────────

// AdrenoTools 路径渲染测试（与 loader 同构，仅加载段不同）
static std::string renderDriverAdrenotoolsJson(const char* driverDir, const char* driverName,
                                               const char* tmpDir, const char* hookLibDir) {
    void* lib = nullptr;
    GetInstanceProcAddrFn getInst = nullptr;
    std::string err = loadDriverAdrenoTools(driverDir, driverName, tmpDir, hookLibDir, false, &lib, &getInst);
    if (!err.empty()) return err;
    VulkanDispatch vk;
    vk.libHandle = lib;
    vk.vkCreateInstance = getInst
            ? reinterpret_cast<PFN_CreateInstance>(getInst(VK_NULL_HANDLE, "vkCreateInstance"))
            : nullptr;
    if (!vk.vkCreateInstance) {
        vk.vkCreateInstance = reinterpret_cast<PFN_CreateInstance>(dlsym(lib, "vkCreateInstance"));
    }
    vk.vkDestroyInstance = getInst
            ? reinterpret_cast<PFN_DestroyInstance>(getInst(VK_NULL_HANDLE, "vkDestroyInstance"))
            : nullptr;
    if (!vk.vkDestroyInstance) {
        vk.vkDestroyInstance = reinterpret_cast<PFN_DestroyInstance>(dlsym(lib, "vkDestroyInstance"));
    }
    if (!vk.vkCreateInstance || !vk.vkDestroyInstance) {
        dlclose(lib);
        return "{\"success\":false,\"errorCode\":\"INSTANCE_FUNCS_MISSING\",\"errorMessage\":\"adt render: driver lacks instance funcs\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }
    VkInstance instance = createVulkanInstance(vk.vkCreateInstance);
    if (instance == VK_NULL_HANDLE) {
        dlclose(lib);
        return "{\"success\":false,\"errorCode\":\"CREATE_INSTANCE_FAILED\",\"errorMessage\":\"adt render: vkCreateInstance failed\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }
    vk.vkEnumeratePhysicalDevices = reinterpret_cast<PFN_EnumeratePhysicalDevices>(
            getInst(instance, "vkEnumeratePhysicalDevices"));
    vk.vkGetPhysicalDeviceQueueFamilyProperties = reinterpret_cast<PFN_GetPhysicalDeviceQueueFamilyProperties>(
            getInst(instance, "vkGetPhysicalDeviceQueueFamilyProperties"));
    vk.vkGetPhysicalDeviceMemoryProperties = reinterpret_cast<PFN_GetPhysicalDeviceMemoryProperties>(
            getInst(instance, "vkGetPhysicalDeviceMemoryProperties"));
    uint32_t deviceCount = 0;
    vk.vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
    if (deviceCount == 0) {
        vk.vkDestroyInstance(instance, nullptr);
        dlclose(lib);
        return "{\"success\":false,\"errorCode\":\"NO_DEVICE\",\"errorMessage\":\"adt render: no devices\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
    }
    std::vector<VkPhysicalDevice> devices(deviceCount);
    vk.vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data());
    std::string res = runRenderTest(vk, instance, devices[0], getInst);
    vk.vkDestroyInstance(instance, nullptr);
    dlclose(lib);
    return res;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_emubox_app_NativeVulkanBridge_benchmarkDriverAdrenotoolsInProcess(
        JNIEnv* env, jobject /* thiz */, jstring driverDir, jstring driverName,
        jstring tmpDir, jstring hookLibDir) {
    const char* dd = driverDir ? env->GetStringUTFChars(driverDir, nullptr) : nullptr;
    const char* dn = driverName ? env->GetStringUTFChars(driverName, nullptr) : nullptr;
    const char* td = tmpDir ? env->GetStringUTFChars(tmpDir, nullptr) : nullptr;
    const char* hl = hookLibDir ? env->GetStringUTFChars(hookLibDir, nullptr) : nullptr;
    if (!dd || !dn || !td || !hl) {
        if (dd) env->ReleaseStringUTFChars(driverDir, dd);
        if (dn) env->ReleaseStringUTFChars(driverName, dn);
        if (td) env->ReleaseStringUTFChars(tmpDir, td);
        if (hl) env->ReleaseStringUTFChars(hookLibDir, hl);
        return env->NewStringUTF("{\"success\":false,\"errorCode\":\"BAD_ARGS\",\"errorMessage\":\"missing args\",\"fillBandwidthGBs\":0,\"copyBandwidthGBs\":0,\"computeGFLOPS\":0,\"computeError\":\"\",\"totalScore\":0}");
    }
    // 双路径：vktest 同款 loader 优先；失败回退 adrenotools（同 collect）
    std::string json = benchmarkDriverViaLoaderJson(dd, dn, td, hl);
    if (json.find("\"success\":false") != std::string::npos) {
        std::string fb = benchmarkDriverAdrenotoolsJson(dd, dn, td, hl);
        if (fb.find("\"success\":false") == std::string::npos) {
            json = fb;
        } else {
            std::string fbMsg = " | fallback: " + fb;
            json = "{\"success\":false,\"errorCode\":\"DUAL_PATH_FAILED\",\"errorMessage\":\""
                   + jsonEscape("loader+adrenotools both failed: " + fbMsg)
                   + "\",\"fillBandwidthGBs\":0,\"copyBandwidthGBs\":0,\"computeGFLOPS\":0,"
                   "\"computeError\":\"\",\"totalScore\":0}";
        }
    }
    env->ReleaseStringUTFChars(driverDir, dd);
    env->ReleaseStringUTFChars(driverName, dn);
    env->ReleaseStringUTFChars(tmpDir, td);
    env->ReleaseStringUTFChars(hookLibDir, hl);
    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_emubox_app_NativeVulkanBridge_renderDriverAdrenotoolsInProcess(
        JNIEnv* env, jobject /* thiz */, jstring driverDir, jstring driverName,
        jstring tmpDir, jstring hookLibDir) {
    const char* dd = driverDir ? env->GetStringUTFChars(driverDir, nullptr) : nullptr;
    const char* dn = driverName ? env->GetStringUTFChars(driverName, nullptr) : nullptr;
    const char* td = tmpDir ? env->GetStringUTFChars(tmpDir, nullptr) : nullptr;
    const char* hl = hookLibDir ? env->GetStringUTFChars(hookLibDir, nullptr) : nullptr;
    if (!dd || !dn || !td || !hl) {
        if (dd) env->ReleaseStringUTFChars(driverDir, dd);
        if (dn) env->ReleaseStringUTFChars(driverName, dn);
        if (td) env->ReleaseStringUTFChars(tmpDir, td);
        if (hl) env->ReleaseStringUTFChars(hookLibDir, hl);
        return env->NewStringUTF("{\"success\":false,\"errorCode\":\"BAD_ARGS\",\"errorMessage\":\"missing args\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}");
    }
    // 渲染测试（vktest 同款）：loader 路径优先，失败回退 adrenotools
    std::string json = renderDriverViaLoaderJson(dd, dn, td, hl);
    if (json.find("\"success\":false") != std::string::npos) {
        std::string fb = renderDriverAdrenotoolsJson(dd, dn, td, hl);
        if (fb.find("\"success\":false") == std::string::npos) {
            json = fb;
        } else {
            std::string fbMsg = " | fallback: " + fb;
            json = "{\"success\":false,\"errorCode\":\"DUAL_RENDER_FAILED\",\"errorMessage\":\""
                   + jsonEscape("render loader+adrenotools both failed: " + fbMsg)
                   + "\",\"fps\":0,\"avgFrameMs\":0,\"frameCount\":0}";
        }
    }
    env->ReleaseStringUTFChars(driverDir, dd);
    env->ReleaseStringUTFChars(driverName, dn);
    env->ReleaseStringUTFChars(tmpDir, td);
    env->ReleaseStringUTFChars(hookLibDir, hl);
    return env->NewStringUTF(json.c_str());
}
