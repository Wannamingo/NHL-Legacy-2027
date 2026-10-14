#pragma once
#include "fence_diagnostics.h"
#include <rex/ui/frame_diagnostics.h>
#include <array>
#include <atomic>
#include <cstdint>
#include <filesystem>
#include <optional>
#include <string>
#include <map>

namespace nhl::diagnostics {
uint64_t ThreadCpuNanoseconds();

struct ThreadTicks { int tid = 0; uint64_t ticks = 0; std::string name; };
std::optional<ThreadTicks> ParseThreadStat(const std::string& text);
struct DeviceSample {
  std::optional<double> cpu_game_ms, cpu_render_ms, fence_spin_ms, fence_wall_ms;
  std::optional<FenceSample> fences;
  std::array<std::optional<double>, size_t(rex::ui::diagnostics::Wait::kCount)> waits_ms{};
  std::optional<double> gpu_mhz, cpu_big_mhz, battery_c, skin_c, gpu_c, cpu_c;
  int thermal_status = -1;
  double sample_cpu_ms = 0.0;
};
class DeviceSampler {
 public:
  DeviceSample Sample(uint64_t frames, const std::filesystem::path& user);
  void Reset();
 private:
  uint64_t last_frames_ = 0, last_fence_cpu_ = 0, last_fence_wall_ = 0;
  std::map<int, ThreadTicks> last_guest_;
  std::optional<ThreadTicks> last_render_;
  rex::ui::diagnostics::Totals last_waits_{};
  FenceSnapshot last_fences_{};
  double last_time_ = 0;
  bool primed_ = false;
};
}  // namespace nhl::diagnostics
