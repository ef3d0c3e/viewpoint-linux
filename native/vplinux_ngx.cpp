// SPDX-FileCopyrightText: 2026 ef3d0c3e
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception

// Thin C wrapper around NVIDIA's NGX SDK (DLSS Super Resolution, Vulkan, Linux) for the
// Viewpoint Linux add-on. NGX for Linux ships as a static library, so Java cannot call it
// directly; this file is linked with it into libvplinux_ngx.so, which lives inside the
// add-on's (signed) jar.
//
// Every exported function has the shape `int fn(int64_t *slots)`: Java fills an array of
// 8-byte slots (pointers and integers as int64, floats as double bits) and reads results
// back from the same array. That keeps the Java side to one LWJGL call shape, JNI.callPI.
// Return values are NVSDK_NGX_Result codes (0x1 is success), or VPL_* below.

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <vulkan/vulkan.h>

#include "nvsdk_ngx_vk.h"
#include "nvsdk_ngx_helpers.h"
#include "nvsdk_ngx_helpers_vk.h"

#define VPL_EXPORT extern "C" __attribute__((visibility("default")))

namespace {

constexpr int VPL_NO_CONTEXT = -1;
constexpr int VPL_BUFFER_TOO_SMALL = -2;
constexpr int VPL_NOT_AVAILABLE = -3;

// A fixed, GUID-like project ID, as NGX asks of custom engines without an NVIDIA app ID.
constexpr const char *PROJECT_ID = "3e431bf1-33eb-4b5c-8f8a-39d1955fe4bf";
constexpr const char *ENGINE_VERSION = "1.0";

struct Context {
    VkDevice device = VK_NULL_HANDLE;
    NVSDK_NGX_Parameter *params = nullptr;
    NVSDK_NGX_Handle *dlss = nullptr;
};

double asDouble(int64_t slot) {
    double value;
    std::memcpy(&value, &slot, sizeof value);
    return value;
}

// NGX takes wchar_t paths (4-byte UTF-32 on Linux). Decode UTF-8 by hand rather than
// switching the game process's locale for mbstowcs.
std::wstring widen(const char *utf8) {
    std::wstring out;
    for (const unsigned char *p = reinterpret_cast<const unsigned char *>(utf8); *p;) {
        uint32_t c = *p;
        int extra = c >= 0xF0 ? 3 : c >= 0xE0 ? 2 : c >= 0xC0 ? 1 : 0;
        c &= extra == 3 ? 0x07 : extra == 2 ? 0x0F : extra == 1 ? 0x1F : 0x7F;
        ++p;
        for (int i = 0; i < extra && (*p & 0xC0) == 0x80; ++i, ++p) {
            c = (c << 6) | (*p & 0x3F);
        }
        out.push_back(static_cast<wchar_t>(c));
    }
    return out;
}

// NGX logs hundreds of lines per start, including "errors" for optional config files that
// do not exist. Pass on only real warnings and errors, unless VPLINUX_NGX_LOG=1 asks for all.
bool verboseLog() {
    static const bool verbose = [] {
        const char *v = std::getenv("VPLINUX_NGX_LOG");
        return v && *v && std::strcmp(v, "0") != 0;
    }();
    return verbose;
}

bool worthShowing(const char *message) {
    // Known harmless noise: optional config files that do not exist, and no writable folder
    // for over-the-air model updates (NGX falls back to the driver's models directory).
    static const char *const harmless[] = {
        "NGXReadConfigFile", "NGXReadJSONConfigFile", "NGXCreateModelsFolder", "models directory",
    };
    for (const char *h : harmless) {
        if (std::strstr(message, h)) {
            return false;
        }
    }
    return std::strstr(message, "error") || std::strstr(message, "Error") || std::strstr(message, "warning")
        || std::strstr(message, "Warning");
}

void NVSDK_CONV logToConsole(const char *message, NVSDK_NGX_Logging_Level, NVSDK_NGX_Feature) {
    if (!verboseLog() && !worthShowing(message)) {
        return;
    }
    std::fprintf(stderr, "[ViewpointLinux/NGX] %s", message);
    size_t n = std::strlen(message);
    if (n == 0 || message[n - 1] != '\n') {
        std::fputc('\n', stderr);
    }
}

// Everything NGX needs to find libnvidia-ngx-dlss.so.* and to write its logs.
struct Paths {
    std::wstring feature;
    std::wstring data;
    const wchar_t *list[1];
    NVSDK_NGX_FeatureCommonInfo info;

    Paths(const char *featureDir, const char *dataDir) : feature(widen(featureDir)), data(widen(dataDir)) {
        list[0] = feature.c_str();
        std::memset(&info, 0, sizeof info);
        info.PathListInfo.Path = list;
        info.PathListInfo.Length = 1;
        info.LoggingInfo.LoggingCallback = logToConsole;
        // NGX's own sinks would print everything to stderr a second time; with the full log
        // asked for, keep them too (they also write log files into the data directory).
        info.LoggingInfo.MinimumLoggingLevel = verboseLog() ? NVSDK_NGX_LOGGING_LEVEL_VERBOSE : NVSDK_NGX_LOGGING_LEVEL_ON;
        info.LoggingInfo.DisableOtherLoggingSinks = !verboseLog();
    }

    NVSDK_NGX_FeatureDiscoveryInfo discovery() {
        NVSDK_NGX_FeatureDiscoveryInfo d;
        std::memset(&d, 0, sizeof d);
        d.SDKVersion = NVSDK_NGX_Version_API;
        d.FeatureID = NVSDK_NGX_Feature_SuperSampling;
        d.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Project_Id;
        d.Identifier.v.ProjectDesc.ProjectId = PROJECT_ID;
        d.Identifier.v.ProjectDesc.EngineType = NVSDK_NGX_ENGINE_TYPE_CUSTOM;
        d.Identifier.v.ProjectDesc.EngineVersion = ENGINE_VERSION;
        d.ApplicationDataPath = data.c_str();
        d.FeatureInfo = &info;
        return d;
    }
};

// Writes the names as "a\0b\0\0" into the caller's buffer.
int writeNames(const VkExtensionProperties *props, uint32_t count, int64_t bufferSlot, int64_t sizeSlot) {
    char *out = reinterpret_cast<char *>(bufferSlot);
    size_t size = static_cast<size_t>(sizeSlot), used = 0;
    for (uint32_t i = 0; i < count; ++i) {
        size_t n = std::strlen(props[i].extensionName) + 1;
        if (used + n + 1 > size) {
            return VPL_BUFFER_TOO_SMALL;
        }
        std::memcpy(out + used, props[i].extensionName, n);
        used += n;
    }
    if (used + 1 > size) {
        return VPL_BUFFER_TOO_SMALL;
    }
    out[used] = '\0';
    return NVSDK_NGX_Result_Success;
}

NVSDK_NGX_Resource_VK image(const int64_t *s) {
    // s: VkImage, VkImageView, VkFormat, width, height
    VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    return NVSDK_NGX_Create_ImageView_Resource_VK(
        reinterpret_cast<VkImageView>(s[1]), reinterpret_cast<VkImage>(s[0]), range,
        static_cast<VkFormat>(s[2]), static_cast<unsigned>(s[3]), static_cast<unsigned>(s[4]), false);
}

} // namespace

// in:  [0] buffer, [1] buffer size, [2] feature dir (utf8), [3] data dir (utf8)
VPL_EXPORT int vpl_instance_extensions(int64_t *s) {
    Paths paths(reinterpret_cast<const char *>(s[2]), reinterpret_cast<const char *>(s[3]));
    NVSDK_NGX_FeatureDiscoveryInfo discovery = paths.discovery();
    uint32_t count = 0;
    VkExtensionProperties *props = nullptr;
    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(&discovery, &count, &props);
    return NVSDK_NGX_FAILED(r) ? static_cast<int>(r) : writeNames(props, count, s[0], s[1]);
}

// in:  [0] VkInstance, [1] VkPhysicalDevice, [2] buffer, [3] buffer size, [4] feature dir, [5] data dir
VPL_EXPORT int vpl_device_extensions(int64_t *s) {
    Paths paths(reinterpret_cast<const char *>(s[4]), reinterpret_cast<const char *>(s[5]));
    NVSDK_NGX_FeatureDiscoveryInfo discovery = paths.discovery();
    uint32_t count = 0;
    VkExtensionProperties *props = nullptr;
    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(
        reinterpret_cast<VkInstance>(s[0]), reinterpret_cast<VkPhysicalDevice>(s[1]), &discovery, &count, &props);
    return NVSDK_NGX_FAILED(r) ? static_cast<int>(r) : writeNames(props, count, s[2], s[3]);
}

// in:  [0] VkInstance, [1] VkPhysicalDevice, [2] VkDevice, [3] vkGetInstanceProcAddr,
//      [4] vkGetDeviceProcAddr, [5] feature dir, [6] data dir
// out: [7] context, [8] DLSS available (0/1), [9] needs newer driver (0/1),
//      [10] minimum driver major, [11] feature init result
VPL_EXPORT int vpl_init(int64_t *s) {
    s[7] = 0;
    Paths paths(reinterpret_cast<const char *>(s[5]), reinterpret_cast<const char *>(s[6]));
    VkDevice device = reinterpret_cast<VkDevice>(s[2]);
    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_Init_with_ProjectID(
        PROJECT_ID, NVSDK_NGX_ENGINE_TYPE_CUSTOM, ENGINE_VERSION, paths.data.c_str(),
        reinterpret_cast<VkInstance>(s[0]), reinterpret_cast<VkPhysicalDevice>(s[1]), device,
        reinterpret_cast<PFN_vkGetInstanceProcAddr>(s[3]), reinterpret_cast<PFN_vkGetDeviceProcAddr>(s[4]),
        &paths.info, NVSDK_NGX_Version_API);
    if (NVSDK_NGX_FAILED(r)) {
        return r;
    }

    Context *ctx = new Context();
    ctx->device = device;
    r = NVSDK_NGX_VULKAN_GetCapabilityParameters(&ctx->params);
    if (NVSDK_NGX_FAILED(r)) {
        NVSDK_NGX_VULKAN_Shutdown1(device);
        delete ctx;
        return r;
    }

    int available = 0, needsDriver = 0, initResult = 0;
    unsigned minDriver = 0;
    NVSDK_NGX_Parameter_GetI(ctx->params, NVSDK_NGX_Parameter_SuperSampling_Available, &available);
    NVSDK_NGX_Parameter_GetI(ctx->params, NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver, &needsDriver);
    NVSDK_NGX_Parameter_GetUI(ctx->params, NVSDK_NGX_Parameter_SuperSampling_MinDriverVersionMajor, &minDriver);
    NVSDK_NGX_Parameter_GetI(ctx->params, NVSDK_NGX_Parameter_SuperSampling_FeatureInitResult, &initResult);
    s[7] = reinterpret_cast<int64_t>(ctx);
    s[8] = available;
    s[9] = needsDriver;
    s[10] = minDriver;
    s[11] = initResult;
    return NVSDK_NGX_Result_Success;
}

// in:  [0] context, [1] output width, [2] output height, [3] NVSDK_NGX_PerfQuality_Value
// out: [4] render width, [5] render height
VPL_EXPORT int vpl_optimal(int64_t *s) {
    Context *ctx = reinterpret_cast<Context *>(s[0]);
    if (!ctx) {
        return VPL_NO_CONTEXT;
    }
    unsigned w = 0, h = 0, maxW = 0, maxH = 0, minW = 0, minH = 0;
    float sharpness = 0.0f;
    NVSDK_NGX_Result r = NGX_DLSS_GET_OPTIMAL_SETTINGS(
        ctx->params, static_cast<unsigned>(s[1]), static_cast<unsigned>(s[2]),
        static_cast<NVSDK_NGX_PerfQuality_Value>(s[3]), &w, &h, &maxW, &maxH, &minW, &minH, &sharpness);
    if (NVSDK_NGX_FAILED(r)) {
        return r;
    }
    if (w == 0 || h == 0) {
        return VPL_NOT_AVAILABLE;
    }
    s[4] = w;
    s[5] = h;
    return NVSDK_NGX_Result_Success;
}

// in:  [0] context, [1] VkCommandBuffer (recording), [2] render width, [3] render height,
//      [4] output width, [5] output height, [6] NVSDK_NGX_PerfQuality_Value, [7] feature flags
VPL_EXPORT int vpl_create(int64_t *s) {
    Context *ctx = reinterpret_cast<Context *>(s[0]);
    if (!ctx) {
        return VPL_NO_CONTEXT;
    }
    if (ctx->dlss) {
        NVSDK_NGX_VULKAN_ReleaseFeature(ctx->dlss);
        ctx->dlss = nullptr;
    }
    NVSDK_NGX_DLSS_Create_Params create;
    std::memset(&create, 0, sizeof create);
    create.Feature.InWidth = static_cast<unsigned>(s[2]);
    create.Feature.InHeight = static_cast<unsigned>(s[3]);
    create.Feature.InTargetWidth = static_cast<unsigned>(s[4]);
    create.Feature.InTargetHeight = static_cast<unsigned>(s[5]);
    create.Feature.InPerfQualityValue = static_cast<NVSDK_NGX_PerfQuality_Value>(s[6]);
    create.InFeatureCreateFlags = static_cast<int>(s[7]);
    return NGX_VULKAN_CREATE_DLSS_EXT1(
        ctx->device, reinterpret_cast<VkCommandBuffer>(s[1]), 1, 1, &ctx->dlss, ctx->params, &create);
}

// in:  [0] context, [1] VkCommandBuffer (recording),
//      [2..6] colour, [7..11] depth, [12..16] motion, [17..21] output
//             (each: VkImage, VkImageView, VkFormat, width, height),
//      [22] jitter x, [23] jitter y, [24] mv scale x, [25] mv scale y (doubles),
//      [26] reset (0/1), [27] render width, [28] render height, [29] frame ms (double)
VPL_EXPORT int vpl_evaluate(int64_t *s) {
    Context *ctx = reinterpret_cast<Context *>(s[0]);
    if (!ctx || !ctx->dlss) {
        return VPL_NO_CONTEXT;
    }
    NVSDK_NGX_Resource_VK color = image(s + 2), depth = image(s + 7), motion = image(s + 12), output = image(s + 17);
    output.ReadWrite = true;

    NVSDK_NGX_VK_DLSS_Eval_Params eval;
    std::memset(&eval, 0, sizeof eval);
    eval.Feature.pInColor = &color;
    eval.Feature.pInOutput = &output;
    eval.pInDepth = &depth;
    eval.pInMotionVectors = &motion;
    eval.InJitterOffsetX = static_cast<float>(asDouble(s[22]));
    eval.InJitterOffsetY = static_cast<float>(asDouble(s[23]));
    eval.InMVScaleX = static_cast<float>(asDouble(s[24]));
    eval.InMVScaleY = static_cast<float>(asDouble(s[25]));
    eval.InReset = s[26] != 0;
    eval.InRenderSubrectDimensions.Width = static_cast<unsigned>(s[27]);
    eval.InRenderSubrectDimensions.Height = static_cast<unsigned>(s[28]);
    eval.InPreExposure = 1.0f;
    eval.InExposureScale = 1.0f;
    eval.InFrameTimeDeltaInMsec = static_cast<float>(asDouble(s[29]));
    return NGX_VULKAN_EVALUATE_DLSS_EXT(reinterpret_cast<VkCommandBuffer>(s[1]), ctx->dlss, ctx->params, &eval);
}

// in:  [0] context. The device must be idle.
VPL_EXPORT int vpl_shutdown(int64_t *s) {
    Context *ctx = reinterpret_cast<Context *>(s[0]);
    if (!ctx) {
        return VPL_NO_CONTEXT;
    }
    if (ctx->dlss) {
        NVSDK_NGX_VULKAN_ReleaseFeature(ctx->dlss);
    }
    if (ctx->params) {
        NVSDK_NGX_VULKAN_DestroyParameters(ctx->params);
    }
    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_Shutdown1(ctx->device);
    delete ctx;
    s[0] = 0;
    return r;
}
