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

#define LOG_TAG "EmuBoxVulkan"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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
        return "{\"success\":false,\"errorCode\":\"DRIVER_CRASHED\",\"errorMessage\":\"驱动进程崩溃或超时（驱动可能不兼容当前系统）\",\"deviceCount\":0,\"devices\":[]}";
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
                                PFN_vkGetInstanceProcAddr resolver = nullptr) {
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
    // 跑在 fork 子进程隔离内：驱动在 compute pipeline/dispatch 崩溃只影响本次
    // 跑分，App 主进程不受影响。dispatch 次数取 4000（约 671 亿 flops）降低
    // 崩溃窗口与卡死风险；compute 失败仅降级该指标，不使整个跑分失败。
    std::string computeError;
    double computeGFLOPS = 0.0;
#if 1
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
#endif

    // 数值合法性兜底（防止 inf/NaN 进入 JSON）
    if (!std::isfinite(fillBandwidthGBs) || !std::isfinite(copyBandwidthGBs)) {
        cleanup();
        return "{\"success\":false,\"errorCode\":\"BANDWIDTH_NONFINITE\",\"errorMessage\":\"computed bandwidth is not finite\"}";
    }

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

    std::string result = runBenchmark(vk, instance, devices[0]);

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
    std::string result = runBenchmark(vk, instance, devices[0], getInst);
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
        return "{\"success\":false,\"errorCode\":\"DRIVER_CRASHED\",\"errorMessage\":\"基准进程崩溃或超时（驱动可能不兼容当前系统）\",\"deviceName\":\"\",\"fillBandwidthGBs\":0,\"copyBandwidthGBs\":0,\"computeGFLOPS\":0,\"computeError\":\"\",\"totalScore\":0}";
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
