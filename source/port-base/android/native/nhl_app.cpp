// Android entry for the NHL title. Desktop-only UI/patching code is separate.
#include "generated/default/nhllegacy_init.h"
#include "render_diagnostics.h"
#include <rex/cvar.h>
#include <rex/filesystem/devices/host_path_device.h>
#include <rex/filesystem/vfs.h>
#include <rex/graphics/vulkan/graphics_system.h>
#include <rex/logging.h>
#include <rex/rex_app.h>
#include <cstdlib>
#include <cstring>

REXCVAR_DECLARE(bool, protect_zero);
REXCVAR_DECLARE(bool, scribble_heap);
REXCVAR_DECLARE(bool, vsync);
REXCVAR_DECLARE(bool, gpu_allow_invalid_fetch_constants);
REXCVAR_DECLARE(int32_t, draw_resolution_scale_x);
REXCVAR_DECLARE(int32_t, draw_resolution_scale_y);
REXCVAR_DECLARE(int32_t, audio_maxqframes);
REXCVAR_DECLARE(std::string, render_target_path_vulkan);
REXCVAR_DECLARE(std::string, readback_resolve);
REXCVAR_DECLARE(bool, vulkan_readback_resolve);
REXCVAR_DECLARE(int32_t, vulkan_readback_resolve_max_length);
REXCVAR_DECLARE(std::string, vulkan_driver_pipeline_cache_root);
REXCVAR_DECLARE(int32_t, vulkan_driver_pipeline_cache_title);
namespace rex {
REXCVAR_DECLARE(bool, show_fps_counter);
}
REXCVAR_DECLARE(bool, present_letterbox);
REXCVAR_DECLARE(bool, present_original_aspect);
REXCVAR_DECLARE(int32_t, guest_frame_limit);
REXCVAR_DECLARE(int32_t, video_mode_width);
REXCVAR_DECLARE(int32_t, video_mode_height);
REXCVAR_DECLARE(int32_t, window_width);
REXCVAR_DECLARE(int32_t, window_height);
REXCVAR_DECLARE(bool, shadow_filter_linear);
REXCVAR_DECLARE(int32_t, shadow_softness);
REXCVAR_DEFINE_BOOL(nhl_render_diagnostics, false, "Android/Diagnostics",
                    "Log render timings every five seconds");
REXCVAR_DEFINE_BOOL(nhl_capture_gpu_frame, false, "Android/Diagnostics",
                    "Capture one GPU frame for local analysis");
REXCVAR_DEFINE_BOOL(nhl_benchmark_frames, false, "Android/Diagnostics",
                    "Record guest frame intervals to a private CSV for device comparisons");
REXCVAR_DEFINE_BOOL(nhl_frame_time_hud, false, "Android/Diagnostics",
                    "Display guest frame percentiles and measured GPU waits")
    .lifecycle(rex::cvar::Lifecycle::kHotReload);

namespace {
namespace fs = rex::filesystem;
// Mirrors the title's loose-file-first cache mount using the portable host
// filesystem implementation. Both layers perform case-insensitive lookup.
class CacheDevice final : public fs::Device {
 public:
  CacheDevice(const std::filesystem::path& upper, const std::filesystem::path& lower)
      : Device("\\CACHE"), upper_("\\CACHE", upper, false),
        lower_("\\CACHE", lower, true) {}
  bool Initialize() override {
    if (!upper_.Initialize()) return false;
    lower_active_ = std::filesystem::is_directory(lower_.host_path()) && lower_.Initialize();
    return true;
  }
  fs::Entry* ResolvePath(std::string_view path) override {
    if (auto* entry = upper_.ResolvePath(path)) return entry;
    return lower_active_ ? lower_.ResolvePath(path) : nullptr;
  }
  void Dump(rex::string::StringBuffer* out) override { upper_.Dump(out); }
  bool is_read_only() const override { return false; }
  const std::string& name() const override { return upper_.name(); }
  uint32_t attributes() const override { return upper_.attributes(); }
  uint32_t component_name_max_length() const override { return 255; }
  uint32_t total_allocation_units() const override { return upper_.total_allocation_units(); }
  uint32_t available_allocation_units() const override { return upper_.available_allocation_units(); }
  uint32_t sectors_per_allocation_unit() const override { return 1; }
  uint32_t bytes_per_sector() const override { return 0x200; }
 private:
  fs::HostPathDevice upper_, lower_;
  bool lower_active_ = false;
};
class NhlAndroidApp final : public rex::ReXApp {
 public:
  static std::unique_ptr<rex::ui::WindowedApp> Create(rex::ui::WindowedAppContext& context) {
    return std::unique_ptr<NhlAndroidApp>(new NhlAndroidApp(context));
  }
 private:
  explicit NhlAndroidApp(rex::ui::WindowedAppContext& context)
      : ReXApp(context, "nhllegacy", PPCImageConfig) {}
  std::string_view GetBuildTitle() const override { return "Android ARM64 development 0.1"; }
  void OnConfigurePaths(rex::PathConfig& paths) override {
    if (const char* path = std::getenv("NHL_GAME_ROOT")) paths.game_data_root = path;
    if (const char* path = std::getenv("NHL_USER_ROOT")) paths.user_data_root = path;
    paths.cache_root = paths.game_data_root / "_android_cache";
    // Preserve already learned shaders when moving the cache into the game folder.
    const auto legacy_shaders = paths.user_data_root / "cache" / "shaders";
    const auto game_shaders = paths.cache_root / "shaders";
    std::error_code error;
    if (std::filesystem::is_directory(legacy_shaders, error) &&
        !std::filesystem::exists(game_shaders, error)) {
      std::filesystem::create_directories(paths.cache_root, error);
      if (!error) std::filesystem::rename(legacy_shaders, game_shaders, error);
      if (error) {
        REXLOG_WARN("NHL shader cache migration failed: {}", error.message());
        paths.cache_root = paths.user_data_root / "cache";
      } else {
        REXLOG_INFO("NHL shader cache moved to {}", game_shaders.string());
      }
    }
    REXLOG_INFO("NHL shader cache folder: {}", paths.cache_root.string());
    REXCVAR_SET(vulkan_driver_pipeline_cache_root,
                (paths.cache_root / "shaders" / "device").string());
    REXCVAR_SET(vulkan_driver_pipeline_cache_title, 0x454109EC);
  }
  void OnPreSetup(rex::RuntimeConfig& config) override {
    config.graphics = std::make_unique<rex::graphics::vulkan::VulkanGraphicsSystem>();
    diagnostics_graphics_ = static_cast<rex::graphics::GraphicsSystem*>(config.graphics.get());
    REXCVAR_SET(protect_zero, false);
    REXCVAR_SET(scribble_heap, false);
    REXCVAR_SET(vsync, true);
    REXCVAR_SET(guest_frame_limit, 60);
    REXLOG_INFO("NHL frame limit: 60 FPS (all guest output)");
    REXCVAR_SET(gpu_allow_invalid_fetch_constants, true);
    REXCVAR_SET(draw_resolution_scale_x, 1);
    REXCVAR_SET(draw_resolution_scale_y, 1);
    REXCVAR_SET(audio_maxqframes, 16);
    rex::FLAGS_show_fps_counter_storage_() = true;
    const char* aspect = std::getenv("NHL_DISPLAY_ASPECT");
    const bool standard = aspect && std::strcmp(aspect, "4:3") == 0;
    const bool original = !standard && (!aspect || std::strcmp(aspect, "16:9") != 0);
    const int32_t display_width = standard ? 960 : 1280;
    const char* resolution = std::getenv("NHL_INTERNAL_RESOLUTION");
    const int32_t height = resolution && std::strcmp(resolution, "540p") == 0 ? 540
                         : resolution && std::strcmp(resolution, "360p") == 0 ? 360 : 720;
    const int32_t width = display_width * height / 720;
    // Tell both the guest video API and the presenter the selected aspect.
    // Android still owns the physical fullscreen surface; letterboxing fits it.
    // The display mode stays HD. Internal targets are resized by the game
    // hooks; reporting an SD display changes the game's rendering path.
    REXCVAR_SET(video_mode_width, display_width);
    REXCVAR_SET(video_mode_height, 720);
    REXCVAR_SET(window_width, display_width);
    REXCVAR_SET(window_height, 720);
    REXCVAR_SET(present_letterbox, true);
    REXCVAR_SET(present_original_aspect, original);
    REXLOG_INFO("NHL display format {}: guest display {}x720, internal {}x{}, preserve source aspect {}; FPS enabled",
                standard ? "4:3" : original ? "original" : "16:9", display_width, width, height, original);
    // Double-buffer CPU-visible resolve copies instead of draining the GPU
    // for each intermediate target. RG477M gameplay validation: ~21-23 FPS
    // versus ~17-18 with full readback, and zero measured readback waits.
    REXCVAR_SET(readback_resolve, std::string("fast"));
    REXCVAR_SET(vulkan_readback_resolve, true);
    REXCVAR_SET(vulkan_readback_resolve_max_length, 3000000);
    // Mali-G615 lacks fragment shader interlock. This is an initial boot path;
    // scene correctness still needs title-specific EDRAM work and device tests.
    REXCVAR_SET(render_target_path_vulkan, std::string("host"));
    const char* shadows = std::getenv("NHL_SHADOW_QUALITY");
    const bool filtered = shadows && std::strcmp(shadows, "native") != 0;
    const bool soft = shadows && std::strcmp(shadows, "soft") == 0;
    REXCVAR_SET(shadow_filter_linear, filtered);
    REXCVAR_SET(shadow_softness, soft ? 2 : 0);
    REXLOG_INFO("NHL shadow quality: {}", soft ? "soft" : filtered ? "filtered" : "native");
    if (const char* user = std::getenv("NHL_USER_ROOT")) {
      render_settings_ = std::filesystem::path(user) / "android-renderer.toml";
      if (std::filesystem::is_regular_file(render_settings_))
        rex::cvar::LoadConfig(render_settings_);
    }
  }
  void OnCreateDialogs(rex::ui::ImGuiDrawer* drawer) override {
    diagnostics_ = std::make_unique<NhlRenderDiagnosticsDialog>(
        drawer, diagnostics_graphics_, render_settings_);
  }
  void OnShutdown() override { diagnostics_.reset(); }
  void OnPostSetup() override {
    const auto guest_cache = user_data_root() / "cache" / "guest";
    auto device = std::make_unique<CacheDevice>(guest_cache, game_data_root() / "_compiled");
    auto* vfs = runtime()->file_system();
    if (!device->Initialize() || !vfs->RegisterDevice(std::move(device))) {
      REXLOG_ERROR("NHL cache mount failed");
      return;
    }
    vfs->RegisterSymbolicLink("cache:", "\\CACHE");
    REXLOG_INFO("NHL cache mounted at {}", guest_cache.string());
  }
  rex::graphics::GraphicsSystem* diagnostics_graphics_ = nullptr;
  std::filesystem::path render_settings_;
  std::unique_ptr<NhlRenderDiagnosticsDialog> diagnostics_;
};
}
REX_DEFINE_APP(nhllegacy, NhlAndroidApp::Create)
