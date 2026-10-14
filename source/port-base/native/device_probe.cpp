#include <vulkan/vulkan.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

static bool checked(VkResult result, const char* operation) {
  if (result == VK_SUCCESS) return true;
  std::fprintf(stderr, "%s: VkResult=%d\n", operation, result);
  return false;
}

static std::string json_string(const char* value) {
  std::string result = "\"";
  for (const unsigned char c : std::string(value)) {
    if (c == '"' || c == '\\') result += '\\';
    if (c < 32) {
      char escaped[7];
      std::snprintf(escaped, sizeof(escaped), "\\u%04x", c);
      result += escaped;
    } else result += c;
  }
  return result + "\"";
}

int main() {
  VkApplicationInfo application{VK_STRUCTURE_TYPE_APPLICATION_INFO};
  application.pApplicationName = "NHL Android device capability probe";
  application.apiVersion = VK_API_VERSION_1_1;
  VkInstanceCreateInfo create{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
  create.pApplicationInfo = &application;
  VkInstance instance{};
  if (!checked(vkCreateInstance(&create, nullptr, &instance), "vkCreateInstance")) return 1;
  uint32_t count{};
  if (!checked(vkEnumeratePhysicalDevices(instance, &count, nullptr), "devices") || !count) {
    vkDestroyInstance(instance, nullptr);
    return 2;
  }
  std::vector<VkPhysicalDevice> devices(count);
  if (!checked(vkEnumeratePhysicalDevices(instance, &count, devices.data()), "devices")) {
    vkDestroyInstance(instance, nullptr);
    return 3;
  }
  std::puts("{\"schema\":1,\"devices\":[");
  for (uint32_t i = 0; i < count; ++i) {
    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(devices[i], &properties);
    uint32_t extension_count{};
    if (!checked(vkEnumerateDeviceExtensionProperties(devices[i], nullptr, &extension_count, nullptr), "extensions")) return 4;
    std::vector<VkExtensionProperties> extensions(extension_count);
    if (!checked(vkEnumerateDeviceExtensionProperties(devices[i], nullptr, &extension_count, extensions.data()), "extensions")) return 5;
    auto has = [&](const char* name) {
      for (const auto& extension : extensions)
        if (!std::strcmp(extension.extensionName, name)) return true;
      return false;
    };
    VkPhysicalDeviceFragmentShaderInterlockFeaturesEXT interlock{
      VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FRAGMENT_SHADER_INTERLOCK_FEATURES_EXT};
    VkPhysicalDeviceFeatures2 features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    if (has(VK_EXT_FRAGMENT_SHADER_INTERLOCK_EXTENSION_NAME)) features.pNext = &interlock;
    auto get_features = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
      vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures2"));
    if (!get_features) return 6;
    get_features(devices[i], &features);
    const auto& f = features.features;
    const auto& limits = properties.limits;
    std::printf("%s{\"name\":%s,\"vendor_id\":%u,\"device_id\":%u,\"api_version\":\"%u.%u.%u\",\"driver_version\":%u,\n",
      i ? "," : "", json_string(properties.deviceName).c_str(), properties.vendorID,
      properties.deviceID, VK_API_VERSION_MAJOR(properties.apiVersion),
      VK_API_VERSION_MINOR(properties.apiVersion), VK_API_VERSION_PATCH(properties.apiVersion), properties.driverVersion);
    std::printf("\"features\":{\"fragmentShaderPixelInterlock\":%u,\"fragmentShaderSampleInterlock\":%u,\"fragmentStoresAndAtomics\":%u,\"geometryShader\":%u,\"tessellationShader\":%u,\"depthClamp\":%u,\"shaderClipDistance\":%u,\"shaderCullDistance\":%u,\"shaderStorageImageMultisample\":%u,\"shaderStorageImageExtendedFormats\":%u,\"independentBlend\":%u},\n",
      interlock.fragmentShaderPixelInterlock, interlock.fragmentShaderSampleInterlock,
      f.fragmentStoresAndAtomics, f.geometryShader, f.tessellationShader, f.depthClamp,
      f.shaderClipDistance, f.shaderCullDistance, f.shaderStorageImageMultisample,
      f.shaderStorageImageExtendedFormats, f.independentBlend);
    std::printf("\"limits\":{\"maxStorageBufferRange\":%u,\"maxPerStageDescriptorStorageBuffers\":%u,\"maxPerStageDescriptorSamplers\":%u,\"maxImageDimension2D\":%u,"
      "\"framebufferColorSampleCounts\":%u,\"framebufferDepthSampleCounts\":%u,\"framebufferStencilSampleCounts\":%u,"
      "\"sampledImageColorSampleCounts\":%u,\"sampledImageDepthSampleCounts\":%u,\"sampledImageStencilSampleCounts\":%u},\n\"extensions\":[",
      limits.maxStorageBufferRange, limits.maxPerStageDescriptorStorageBuffers,
      limits.maxPerStageDescriptorSamplers, limits.maxImageDimension2D,
      limits.framebufferColorSampleCounts, limits.framebufferDepthSampleCounts, limits.framebufferStencilSampleCounts,
      limits.sampledImageColorSampleCounts, limits.sampledImageDepthSampleCounts, limits.sampledImageStencilSampleCounts);
    for (uint32_t j = 0; j < extension_count; ++j)
      std::printf("%s%s", j ? "," : "", json_string(extensions[j].extensionName).c_str());
    std::printf("],\"queue_families\":[");
    uint32_t family_count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &family_count, nullptr);
    std::vector<VkQueueFamilyProperties> families(family_count);
    vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &family_count, families.data());
    for (uint32_t j = 0; j < family_count; ++j) {
      const auto& family = families[j];
      std::printf("%s{\"index\":%u,\"flags\":%u,\"count\":%u,\"timestamp_bits\":%u}",
                  j ? "," : "", j, family.queueFlags, family.queueCount,
                  family.timestampValidBits);
    }
    std::puts("]}");
  }
  std::puts("]}");
  vkDestroyInstance(instance, nullptr);
  return 0;
}
