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

#define LOG_TAG "EmuHubVulkan"
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
    appInfo.pEngineName = "EmuHubVulkanCollector";
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
// Kotlin: com.emuhub.cn.NativeVulkanBridge.collectVulkanInfo(String driverPath)
// driverPath 为 null/空时采集系统 Vulkan（直连 loader，主进程安全）；
// 传入驱动 .so 路径时 fork 子进程采集（防 native 崩溃带崩 App）。
extern "C" JNIEXPORT jstring JNICALL
Java_com_emuhub_cn_NativeVulkanBridge_collectVulkanInfo(JNIEnv* env, jobject /* thiz */, jstring driverPath) {
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
