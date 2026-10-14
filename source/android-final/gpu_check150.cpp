#include "driver_loader132.h"
#include <jni.h>
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <algorithm>
#include <cstring>
#include <fstream>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sstream>
#include <vector>

namespace {
std::string quote(const std::string& value) {
  std::string out="\""; const char* hex="0123456789abcdef";
  for(unsigned char c:value) {
    if(c=='"' || c=='\\') { out+='\\';out+=char(c); }
    else if(c<32 || c>=127) { out+="\\u00";out+=hex[c>>4];out+=hex[c&15]; }
    else out+=char(c);
  }
  return out+'"';
}
std::string failure(const std::string& why) { return "{\"ok\":false,\"message\":"+quote(why)+"}"; }
bool mapped_file(const std::filesystem::path& file) {
  struct stat wanted{};if(stat(file.c_str(),&wanted))return false;
  std::ifstream maps("/proc/self/maps");std::string line;
  while(std::getline(maps,line)) {
    unsigned long begin=0,end=0,offset=0,inode=0;unsigned maj=0,min=0;char mode[5]{};
    if(std::sscanf(line.c_str(),"%lx-%lx %4s %lx %x:%x %lu",&begin,&end,mode,&offset,&maj,&min,&inode)==7
       && inode==static_cast<unsigned long>(wanted.st_ino) && maj==major(wanted.st_dev) && min==minor(wanted.st_dev))return true;
  }
  return false;
}
std::string function_file(PFN_vkVoidFunction function) {
  Dl_info info{};if(!function || !dladdr(reinterpret_cast<const void*>(function),&info) || !info.dli_fname)return {};
  return info.dli_fname;
}
bool in_directory(const std::string& file,const std::string& directory) {
  std::error_code error;auto path=std::filesystem::canonical(file,error);if(error)return false;
  auto parent=std::filesystem::canonical(directory,error);if(error)return false;
  return path.string().starts_with(parent.string()+"/");
}
std::string check(const std::string& directory,const std::string& library,const std::string& hooks,const std::string& files) {
  const bool custom=!directory.empty();
  const auto selected=driver132::Open(directory.c_str(),library.c_str(),hooks.c_str(),files.c_str());
  // A requested driver must be the driver actually tested. Never silently
  // substitute the system loader and then report the imported one as passing.
  if(custom && !selected.loader) return failure("Custom driver failed to load through AdrenoTools (status "+std::to_string(int(selected.status))+"). Select System or another compatible driver ZIP.");
  void* loader=selected.loader?selected.loader:dlopen("libvulkan.so",RTLD_NOW|RTLD_LOCAL);
  if(!loader)return failure("Vulkan loader unavailable.");
  auto gpa=reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(loader,"vkGetInstanceProcAddr"));
  if(!gpa)return failure("Vulkan entry point unavailable.");
  auto create=reinterpret_cast<PFN_vkCreateInstance>(gpa(nullptr,"vkCreateInstance"));
  if(!create)return failure("vkCreateInstance unavailable.");
  VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};app.pApplicationName="NHL GPU compatibility";app.apiVersion=VK_API_VERSION_1_2;
  VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};ci.pApplicationInfo=&app;
  VkInstance instance{};VkResult status=create(&ci,nullptr,&instance);
  if(status!=VK_SUCCESS)return failure("Vulkan 1.2 instance failed: "+std::to_string(status));
#define FN(name) auto name=reinterpret_cast<PFN_##name>(gpa(instance,#name))
  FN(vkDestroyInstance);FN(vkEnumeratePhysicalDevices);FN(vkGetPhysicalDeviceProperties2);FN(vkGetPhysicalDeviceFeatures2);FN(vkGetPhysicalDeviceQueueFamilyProperties);FN(vkEnumerateDeviceExtensionProperties);FN(vkCreateDevice);FN(vkGetDeviceProcAddr);
#undef FN
  if(!vkDestroyInstance || !vkEnumeratePhysicalDevices || !vkGetPhysicalDeviceProperties2 || !vkGetPhysicalDeviceFeatures2 || !vkGetPhysicalDeviceQueueFamilyProperties || !vkEnumerateDeviceExtensionProperties || !vkCreateDevice || !vkGetDeviceProcAddr) {
    if(vkDestroyInstance)vkDestroyInstance(instance,nullptr);return failure("Required Vulkan function unavailable.");
  }
  uint32_t count=0;status=vkEnumeratePhysicalDevices(instance,&count,nullptr);
  if(status!=VK_SUCCESS || !count || count>32){vkDestroyInstance(instance,nullptr);return failure("No usable Vulkan GPU.");}
  std::vector<VkPhysicalDevice> devices(count);status=vkEnumeratePhysicalDevices(instance,&count,devices.data());
  if(status!=VK_SUCCESS){vkDestroyInstance(instance,nullptr);return failure("GPU enumeration failed.");}
  const bool library_mapped=!custom || mapped_file(std::filesystem::path(directory)/library);
  if(custom && !library_mapped) {vkDestroyInstance(instance,nullptr);return failure("Selected driver library was not loaded. AdrenoTools fell back to the System driver; choose another compatible ZIP.");}
  std::string output=failure("No GPU passed the basic checks.");
  for(uint32_t i=0;i<count;++i) {
    VkPhysicalDeviceDriverProperties dp{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
    VkPhysicalDeviceProperties2 props{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};props.pNext=&dp;vkGetPhysicalDeviceProperties2(devices[i],&props);
    VkPhysicalDeviceVulkan12Features f12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    VkPhysicalDeviceFeatures2 features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    const bool api12=props.properties.apiVersion>=VK_API_VERSION_1_2;
    features.pNext=api12?&f12:nullptr;vkGetPhysicalDeviceFeatures2(devices[i],&features);
    uint32_t ec=0;std::vector<VkExtensionProperties> extensions;
    if(vkEnumerateDeviceExtensionProperties(devices[i],nullptr,&ec,nullptr)==VK_SUCCESS && ec<=4096) {
      extensions.resize(ec);if(vkEnumerateDeviceExtensionProperties(devices[i],nullptr,&ec,extensions.data())!=VK_SUCCESS)extensions.clear();
    }
    auto has=[&](const char* name){return std::any_of(extensions.begin(),extensions.end(),[&](const auto& e){return !std::strcmp(e.extensionName,name);});};
    const bool dyn=props.properties.apiVersion>=VK_API_VERSION_1_3 || has(VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME);
    VkPhysicalDeviceDynamicRenderingFeatures dynamic{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES};
    if(dyn) { VkPhysicalDeviceFeatures2 query{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};query.pNext=&dynamic;vkGetPhysicalDeviceFeatures2(devices[i],&query); }
    uint32_t qc=0;vkGetPhysicalDeviceQueueFamilyProperties(devices[i],&qc,nullptr);
    std::vector<VkQueueFamilyProperties> queues(qc<=256?qc:0);
    if(!queues.empty())vkGetPhysicalDeviceQueueFamilyProperties(devices[i],&qc,queues.data());
    uint32_t qi=0;while(qi<queues.size() && (queues[qi].queueFlags&(VK_QUEUE_GRAPHICS_BIT|VK_QUEUE_COMPUTE_BIT))!=(VK_QUEUE_GRAPHICS_BIT|VK_QUEUE_COMPUTE_BIT))++qi;
    std::string missing;
    auto need=[&](bool yes,const char* name){if(!yes){if(!missing.empty())missing+=", ";missing+=name;}};
    need(api12,"Vulkan1.2");need(features.features.independentBlend,"independentBlend");need(features.features.fragmentStoresAndAtomics,"fragmentStoresAndAtomics");
    need(features.features.shaderInt64,"shaderInt64");need(f12.bufferDeviceAddress,"bufferDeviceAddress");need(f12.uniformBufferStandardLayout,"uniformBufferStandardLayout");need(qi<queues.size(),"graphics+compute queue");need(has(VK_KHR_SWAPCHAIN_EXTENSION_NAME),"VK_KHR_swapchain");
    VkResult dc=VK_ERROR_FEATURE_NOT_PRESENT;
    std::string function_module;bool implementation_confirmed=!custom;
    if(missing.empty()) {
      float priority=1;VkDeviceQueueCreateInfo queue{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};queue.queueFamilyIndex=qi;queue.queueCount=1;queue.pQueuePriorities=&priority;
      VkPhysicalDeviceVulkan12Features enabled12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};enabled12.bufferDeviceAddress=VK_TRUE;enabled12.uniformBufferStandardLayout=VK_TRUE;
      VkPhysicalDeviceFeatures enabled{};enabled.independentBlend=VK_TRUE;enabled.fragmentStoresAndAtomics=VK_TRUE;enabled.shaderInt64=VK_TRUE;
      const char* names[]={VK_KHR_SWAPCHAIN_EXTENSION_NAME};
      VkDeviceCreateInfo info{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};info.pNext=&enabled12;info.pEnabledFeatures=&enabled;info.queueCreateInfoCount=1;info.pQueueCreateInfos=&queue;info.enabledExtensionCount=1;info.ppEnabledExtensionNames=names;
      VkDevice device{};dc=vkCreateDevice(devices[i],&info,nullptr,&device);
      if(dc==VK_SUCCESS) {
        function_module=function_file(vkGetDeviceProcAddr(device,"vkCreateBuffer"));
        const auto shader_module=function_file(vkGetDeviceProcAddr(device,"vkCreateShaderModule"));
        implementation_confirmed=!custom || (in_directory(function_module,directory) && in_directory(shader_module,directory));
        if(!implementation_confirmed)missing="Selected driver implementation could not be confirmed; possible System fallback";
        auto destroy=reinterpret_cast<PFN_vkDestroyDevice>(vkGetDeviceProcAddr(device,"vkDestroyDevice"));if(destroy)destroy(device,nullptr);else{dc=VK_ERROR_INITIALIZATION_FAILED;missing="vkDestroyDevice";}}
    }
    bool ok=missing.empty() && dc==VK_SUCCESS;
    std::string message=ok?"Vulkan device created successfully.":(!missing.empty()?"Missing: "+missing:"Vulkan device creation failed: "+std::to_string(dc));
    std::ostringstream json;json<<"{\"ok\":"<<(ok?"true":"false")<<",\"message\":"<<quote(message)<<",\"gpu\":"<<quote(props.properties.deviceName)<<",\"driver\":"<<quote(dp.driverName)<<",\"driverInfo\":"<<quote(dp.driverInfo)
      <<",\"api\":"<<quote(std::to_string(VK_API_VERSION_MAJOR(props.properties.apiVersion))+"."+std::to_string(VK_API_VERSION_MINOR(props.properties.apiVersion))+"."+std::to_string(VK_API_VERSION_PATCH(props.properties.apiVersion)))
      <<",\"vendorId\":"<<props.properties.vendorID<<",\"deviceId\":"<<props.properties.deviceID<<",\"custom\":"<<(custom?"true":"false")<<",\"bda\":"<<(f12.bufferDeviceAddress?"true":"false")<<",\"int64\":"<<(features.features.shaderInt64?"true":"false")<<",\"dynamicRendering\":"<<(dynamic.dynamicRendering?"true":"false")<<",\"maxBoundDescriptorSets\":"<<props.properties.limits.maxBoundDescriptorSets<<",\"customLibraryMapped\":"<<(library_mapped?"true":"false")<<",\"implementationConfirmed\":"<<(implementation_confirmed?"true":"false")<<",\"deviceFunctionModule\":"<<quote(function_module)<<",\"deviceResult\":"<<int(dc)<<"}";
    output=json.str();if(ok)break;
  }
  vkDestroyInstance(instance,nullptr);return output;
}
}
extern "C" JNIEXPORT jstring JNICALL Java_org_nhllegacy_port_GpuDriverCheckService_probe(JNIEnv* env,jclass,jstring directory,jstring name,jstring hooks,jstring files) {
  auto text=[&](jstring value){if(!value)return std::string{};const char* data=env->GetStringUTFChars(value,nullptr);if(!data)return std::string{};std::string result(data);env->ReleaseStringUTFChars(value,data);return result;};
  try {auto result=check(text(directory),text(name),text(hooks),text(files));return env->NewStringUTF(result.c_str());}
  catch(const std::exception& e) {auto result=failure(e.what());return env->NewStringUTF(result.c_str());}
}
