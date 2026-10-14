// Offline Android Vulkan replay. Packet flattening follows tools/replay/src/xtr_player.cpp.
// This development utility does not execute guest game threads.
#include <rex/cvar.h>
#include <rex/graphics/command_processor.h>
#include <rex/graphics/graphics_system.h>
#include <rex/graphics/vulkan/graphics_system.h>
#include <rex/ui/vulkan/provider.h>
#include <rex/graphics/register_file.h>
#include <rex/graphics/pipeline/shader/shader.h>
#include <rex/graphics/pipeline/texture/util.h>
#include <rex/kernel/init.h>
#include <rex/logging.h>
#include <rex/memory.h>
#include <rex/runtime.h>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <future>
#include <iostream>
#include <chrono>
#include <sstream>
#include <thread>
#include <vector>
#include <span>
#include <algorithm>
#include <numeric>

REXCVAR_DECLARE(std::string, render_target_path_vulkan);
REXCVAR_DECLARE(std::string, readback_resolve);
REXCVAR_DECLARE(bool, gpu_allow_invalid_fetch_constants);
REXCVAR_DECLARE(bool, async_shader_compilation);
REXCVAR_DECLARE(bool, protect_zero);
REXCVAR_DECLARE(int32_t, draw_resolution_scale_x);
REXCVAR_DECLARE(int32_t, draw_resolution_scale_y);
REXCVAR_DECLARE(int32_t, vulkan_readback_resolve_max_length);
REXCVAR_DECLARE(int32_t, vulkan_readback_resolve_min_address);
REXCVAR_DECLARE(int32_t, vulkan_readback_resolve_max_address);
REXCVAR_DECLARE(std::string, vulkan_driver_pipeline_cache_root);
REXCVAR_DECLARE(int32_t, vulkan_driver_pipeline_cache_title);

namespace {
struct Stream {
  std::span<const uint8_t> bytes;
  size_t cursor = 0;
  explicit Stream(std::span<const uint8_t> data) : bytes(data) {}
  uint32_t U32() {
    uint32_t value;
    auto data = Bytes(4);
    std::memcpy(&value, data.data(), 4);
    return value;
  }
  std::span<const uint8_t> Bytes(size_t size) {
    if (size > 512*1024*1024) throw std::runtime_error("Oversized trace payload");
    if (size > bytes.size() - cursor) throw std::runtime_error("Truncated payload");
    auto data = bytes.subspan(cursor, size);
    cursor += size;
    return data;
  }
};
std::vector<uint8_t> LoadTrace(const char* path) {
  std::ifstream file(path, std::ios::binary | std::ios::ate);
  if (!file) throw std::runtime_error("Cannot open trace");
  const auto size = file.tellg();
  if (size < 48 || size > 1024*1024*1024) throw std::runtime_error("Invalid trace size");
  std::vector<uint8_t> bytes(static_cast<size_t>(size));
  file.seekg(0);
  if (!file.read(reinterpret_cast<char*>(bytes.data()), bytes.size()))
    throw std::runtime_error("Cannot read trace");
  return bytes;
}
uint32_t BigWord(const uint8_t* p) {
  return (uint32_t(p[0])<<24) | (uint32_t(p[1])<<16) | (uint32_t(p[2])<<8) | p[3];
}
void Replay(std::span<const uint8_t> trace, rex::graphics::GraphicsSystem& graphics, int skip_first, int skip_last,
            bool retain_unchanged = false) {
  Stream s(trace);
  if (s.U32()!=1) throw std::runtime_error("Invalid trace header");
  s.Bytes(44);
  auto& cp = *graphics.command_processor();
  auto& memory = *graphics.memory();
  struct Packet { uint32_t base, count; bool skip; };
  std::vector<Packet> stack;
  uint32_t draw=0;
  uint64_t restored_bytes = 0, retained_bytes = 0;
  std::chrono::nanoseconds restoration_time{0};
  while(s.cursor < s.bytes.size()) {
    const uint32_t tag=s.U32();
    if(tag==0 || tag==2) { s.U32(); s.U32(); }
    else if(tag==1 || tag==3) {}
    else if(tag==4) {
      uint32_t base=s.U32(), count=s.U32();
      auto data=s.Bytes(size_t(count)*4);
      if(base+uint64_t(data.size())>0x20000000) throw std::runtime_error("Packet outside physical memory");
      if(count) std::memcpy(memory.TranslatePhysical(base),data.data(),data.size());
      uint32_t head=count?BigWord(data.data()):0;
      uint32_t op=(head>>8)&127;
      bool skip=!count || ((head>>30)==3 && (op==0x3f || op==0x37 || op==0x3c));
      if((head>>30)==3 && (op==0x22 || op==0x36)) {
        // Retain resolves and clears during geometry bisection. Removing a
        // resolve also removes its tile clear and leaves data from older views.
        const auto mode = graphics.register_file()->Get<rex::graphics::reg::RB_MODECONTROL>().edram_mode;
        if(int(draw)>=skip_first && int(draw)<skip_last && uint32_t(mode)==4) skip=true;
        ++draw;
      }
      stack.push_back({base,count,skip});
    } else if(tag==5) {
      if(!stack.empty()) {
        auto packet=stack.back(); stack.pop_back();
        if(!packet.skip) cp.ExecutePacket(packet.base,packet.count);
      }
    } else if(tag==6 || tag==7) {
      uint32_t base=s.U32(), encoding=s.U32(), size=s.U32(), decoded=s.U32();
      if(encoding || decoded!=size || base+uint64_t(size)>0x20000000) throw std::runtime_error("Unsupported memory payload");
      auto data=s.Bytes(size);
      if(tag==6) {
        const auto restore_start = std::chrono::steady_clock::now();
        auto* destination = memory.TranslatePhysical(base);
        // Diagnostic replay only: keep identical resident inputs instead of
        // invalidating and uploading every texture again on every sample.
        // Changed ranges still restore their exact captured contents.
        if (retain_unchanged && !std::memcmp(destination, data.data(), size)) {
          retained_bytes += size;
        } else {
          std::memcpy(destination,data.data(),size);
          cp.TracePlaybackWroteMemory(base,size);
          restored_bytes += size;
        }
        restoration_time += std::chrono::steady_clock::now() - restore_start;
      }
    } else if(tag==8) {
      uint32_t encoding=s.U32(), size=s.U32();
      if(encoding || size!=10*1024*1024) throw std::runtime_error("Invalid EDRAM snapshot");
      auto data=s.Bytes(size); cp.RestoreEdramSnapshot(data.data());
    } else if(tag==9) { s.U32(); }
    else if(tag==10) {
      uint32_t first=s.U32(), count=s.U32(), callbacks=s.U32(), encoding=s.U32(), size=s.U32();
      if(encoding || size!=count*4 || first+count>0x5003) throw std::runtime_error("Invalid registers");
      auto data=s.Bytes(size);
      std::vector<uint32_t> words(count);
      if (size) std::memcpy(words.data(), data.data(), size);
      cp.RestoreRegisters(first,words.data(),count,bool(callbacks&255));
    } else if(tag==11) {
      uint32_t component=s.U32(), encoding=s.U32(), size=s.U32();
      if(encoding || size!=(256+3*128)*4) throw std::runtime_error("Invalid gamma ramp");
      auto data=s.Bytes(size);
      std::vector<uint32_t> words(size/4);
      std::memcpy(words.data(),data.data(),size);
      cp.RestoreGammaRamp(reinterpret_cast<const rex::graphics::reg::DC_LUT_30_COLOR*>(words.data()),
                         reinterpret_cast<const rex::graphics::reg::DC_LUT_PWL_DATA*>(words.data()+256),uint8_t(component));
    } else throw std::runtime_error("Unknown trace command");
  }
  std::cerr << "Replayed " << draw << " draw packets; restored=" << restored_bytes
            << " retained=" << retained_bytes << " bytes; restoration_ms="
            << std::chrono::duration<double,std::milli>(restoration_time).count() << '\n';
}
void Dump(rex::graphics::GraphicsSystem& graphics,const char* output,uint32_t base,uint32_t width,uint32_t height) {
  const auto* fb=graphics.memory()->TranslatePhysical<const uint8_t*>(base);
  std::ofstream out(output,std::ios::binary);
  out << "P6\n" << width << ' ' << height << "\n255\n";
  for(uint32_t y=0;y<height;++y) for(uint32_t x=0;x<width;++x) {
    uint32_t offset=rex::graphics::texture_util::GetTiledOffset2D(x,y,width,2);
    const uint8_t* pixel=fb+offset;
    const char rgb[] = {char(pixel[2]), char(pixel[1]), char(pixel[0])};
    out.write(rgb,3);
  }
}
}
int main(int argc,char** argv) {
  if(argc<3) { std::cerr << "trace_replay trace.xtr output.ppm [skip_first skip_last] [address width height] [config.toml|-] [iterations] [benchmark_readback] [restore|resident]\n"; return 2; }
  char name[]="nhl-android-replay"; char* fake[]={name,nullptr};
  rex::cvar::Init(1,fake);
  rex::InitLogging("replay.log");
  if (std::string_view(argv[1]) == "--disassemble") {
    if (argc != 4 || (std::string_view(argv[3]) != "vs" && std::string_view(argv[3]) != "ps")) return 2;
    std::ifstream source(argv[2], std::ios::binary | std::ios::ate);
    if (!source || source.tellg() <= 0 || source.tellg() % 4 != 0 || source.tellg() > 65536) return 2;
    std::vector<uint32_t> code(size_t(source.tellg()) / 4);
    source.seekg(0);
    source.read(reinterpret_cast<char*>(code.data()), code.size() * 4);
    if (!source) return 2;
    // Shader's constructor accepts the original guest-endian microcode.
    rex::graphics::Shader shader(std::string_view(argv[3]) == "vs"
        ? rex::graphics::xenos::ShaderType::kVertex : rex::graphics::xenos::ShaderType::kPixel,
        0, code.data(), code.size());
    rex::string::StringBuffer disassembly;
    shader.AnalyzeUcode(disassembly);
    std::cout << shader.ucode_disassembly();
    return 0;
  }
  REXCVAR_SET(render_target_path_vulkan,std::string("host"));
  REXCVAR_SET(readback_resolve,std::string("full"));
  REXCVAR_SET(gpu_allow_invalid_fetch_constants,true);
  REXCVAR_SET(async_shader_compilation,false);
  REXCVAR_SET(protect_zero,false);
  REXCVAR_SET(draw_resolution_scale_x,1);
  REXCVAR_SET(draw_resolution_scale_y,1);
  REXCVAR_SET(vulkan_driver_pipeline_cache_root,
              (std::filesystem::current_path()/"replay-cache"/"shaders"/"device").string());
  REXCVAR_SET(vulkan_driver_pipeline_cache_title,0x454109EC);
  if (argc>8 && std::string_view(argv[8])!="-") rex::cvar::LoadConfig(argv[8]);
  // Pixel comparison requires completed CPU copies of the resolves.
  REXCVAR_SET(readback_resolve,std::string("full"));
  int iterations=1;
  if (argc>9) {
    char* end=nullptr;
    const long value=std::strtol(argv[9],&end,10);
    if (!end || *end || value<1 || value>120) return 2;
    iterations=int(value);
  }
  const std::string benchmark_readback=argc>10?argv[10]:"full";
  if(benchmark_readback!="full" && benchmark_readback!="fast" && benchmark_readback!="none") return 2;
  const std::string memory_mode = argc>11 ? argv[11] : "restore";
  if (memory_mode!="restore" && memory_mode!="resident") return 2;
  const bool retain_unchanged = memory_mode=="resident";
  std::vector<uint8_t> trace;
  try { trace=LoadTrace(argv[1]); }
  catch(const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
  std::filesystem::create_directories("replay-user");
  std::filesystem::create_directories("replay-cache");
  rex::Runtime runtime(std::filesystem::current_path(),"replay-user",{},"replay-cache");
  rex::RuntimeConfig config;
  config.graphics=std::make_unique<rex::graphics::vulkan::VulkanGraphicsSystem>();
  config.kernel_init=rex::kernel::InitializeKernel;
  if(runtime.Setup(std::move(config))!=0) return 1;
  auto* graphics=static_cast<rex::graphics::GraphicsSystem*>(runtime.graphics_system());
  const bool server=argc>3 && std::string_view(argv[3])=="--serve";
  const int first=argc>3?std::atoi(argv[3]):-1, last=argc>4?std::atoi(argv[4]):-1;
  const uint32_t base=argc>5?std::strtoul(argv[5],nullptr,16):0x1CF32000;
  const uint32_t width=argc>6?std::strtoul(argv[6],nullptr,10):1280;
  const uint32_t height=argc>7?std::strtoul(argv[7],nullptr,10):720;
  int previous=-1;
  do {
    int id=0, skip_first=first, skip_last=last;
    uint32_t address=base;
    std::string output=argv[2];
    if(server) {
      std::ifstream command("replay-command.txt");
      std::string address_text;
      if(!(command>>id>>skip_first>>skip_last>>address_text>>output) || id==previous) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100)); continue;
      }
      if(id<0) break;
      if(output.find('/')!=std::string::npos || output.find("..")!=std::string::npos) return 2;
      address=std::strtoul(address_text.c_str(),nullptr,16);
      previous=id;
    }
    const auto start=std::chrono::steady_clock::now();
    std::promise<void> done; auto future=done.get_future();
    graphics->command_processor()->CallInThread([&] {
      try {
        std::vector<double> times;
        std::vector<double> command_times, completion_times;
        auto* cp=graphics->command_processor();
        auto* device=static_cast<rex::ui::vulkan::VulkanProvider*>(graphics->provider())->vulkan_device();
        const auto wait_idle=reinterpret_cast<PFN_vkDeviceWaitIdle>(
            device->vulkan_instance()->functions().vkGetDeviceProcAddr(device->device(),"vkDeviceWaitIdle"));
        const auto complete=[&] {
          // Retire transient resources, then include completed GPU work in the timing.
          cp->IssueSwap(address,width,height);
          if(!wait_idle || wait_idle(device->device())!=VK_SUCCESS)
            throw std::runtime_error("GPU completion failed");
        };
        const int32_t readback_limit = REXCVAR_GET(vulkan_readback_resolve_max_length);
        const int32_t readback_min = REXCVAR_GET(vulkan_readback_resolve_min_address);
        const int32_t readback_max = REXCVAR_GET(vulkan_readback_resolve_max_address);
        REXCVAR_SET(readback_resolve,iterations>1?benchmark_readback:std::string("full"));
        for(int sample=iterations>1?-1:0; sample<iterations; ++sample) {
          const auto frame_start=std::chrono::steady_clock::now();
          Replay(trace,*graphics,skip_first,skip_last,retain_unchanged);
          const auto command_end = std::chrono::steady_clock::now();
          complete();
          const auto frame_end = std::chrono::steady_clock::now();
          if(sample>=0) {
            times.push_back(std::chrono::duration<double,std::milli>(frame_end-frame_start).count());
            command_times.push_back(std::chrono::duration<double,std::milli>(command_end-frame_start).count());
            completion_times.push_back(std::chrono::duration<double,std::milli>(frame_end-command_end).count());
          }
        }
        // Copy current output for pixel comparison outside the measured fast runs.
        if((iterations>1 && benchmark_readback!="full") || readback_limit>0 || readback_max>0) {
          // Pixel export must bypass every measured readback filter. Otherwise
          // the displayed image may be stale guest RAM rather than this run.
          REXCVAR_SET(vulkan_readback_resolve_max_length,0);
          REXCVAR_SET(vulkan_readback_resolve_min_address,0);
          REXCVAR_SET(vulkan_readback_resolve_max_address,0);
          REXCVAR_SET(readback_resolve,std::string("full"));
          Replay(trace,*graphics,skip_first,skip_last,retain_unchanged);
          complete();
        }
        Dump(*graphics,output.c_str(),address,width,height);
        REXCVAR_SET(vulkan_readback_resolve_max_length,readback_limit);
        REXCVAR_SET(vulkan_readback_resolve_min_address,readback_min);
        REXCVAR_SET(vulkan_readback_resolve_max_address,readback_max);
        if(iterations>1) {
          std::cerr << "Replay samples_ms:";
          for(double ms:times) std::cerr << ' ' << ms;
          std::cerr << '\n';
          std::cerr << "Replay command_ms:";
          for(double ms:command_times) std::cerr << ' ' << ms;
          std::cerr << '\n' << "Replay completion_wait_ms:";
          for(double ms:completion_times) std::cerr << ' ' << ms;
          std::cerr << '\n';
          std::sort(times.begin(),times.end());
          std::cerr << "Replay benchmark: readback=" << benchmark_readback << " samples=" << times.size()
                    << " memory=" << memory_mode
                    << " mean_ms=" << std::accumulate(times.begin(),times.end(),0.0)/times.size()
                    << " median_ms=" << times[times.size()/2]
                    << " max_ms=" << times.back() << '\n';
        }
        done.set_value();
      }
      catch(...) { done.set_exception(std::current_exception()); }
    });
    try { future.get(); } catch(const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
    std::cerr << "Frame saved to " << output << " in "
              << std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count() << " ms\n";
    std::ofstream("replay-done.txt") << id << '\n';
  } while(server);
  return 0;
}
