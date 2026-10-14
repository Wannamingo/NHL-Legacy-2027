#pragma once
#include "frame_diagnostics.h"
#include <rex/ui/presenter.h>
#include <imgui.h>
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdio>
#include <vector>

namespace nhl::diagnostics {
struct FrameTimeWindow {
  std::array<double, 3> percentiles_ms{};
  double max_ms = 0.0;
  size_t intervals = 0, spikes_over_100_ms = 0;
};

// Nearest-rank percentiles of the latest two seconds of guest output, not
// Android UI repaints. Include transition spikes rather than filtering them.
inline FrameTimeWindow SummarizeFrames(const std::vector<uint64_t>& times) {
  FrameTimeWindow result;
  if (times.size() < 2) return result;
  std::vector<uint64_t> intervals;
  for (size_t i = 1; i < times.size(); ++i) {
    if (times[i] <= times[i - 1] || times.back() - times[i] > 2000000) continue;
    const auto delta = times[i] - times[i - 1];
    intervals.push_back(delta);
    if (delta > 100000) ++result.spikes_over_100_ms;
  }
  if (intervals.empty()) return result;
  std::sort(intervals.begin(), intervals.end());
  result.intervals = intervals.size();
  result.max_ms = intervals.back() / 1000.0;
  const size_t ranks[] = {50, 95, 99};
  for (size_t i = 0; i < 3; ++i)
    result.percentiles_ms[i] = intervals[(intervals.size() * ranks[i] + 99) / 100 - 1] / 1000.0;
  return result;
}

inline std::string HudNumber(const std::optional<double>& value, int digits = 2) {
  if (!value) return "--";
  char text[32]; std::snprintf(text, sizeof(text), "%.*f", digits, *value);
  return text;
}
inline void DrawFrameTimeHud(ImGuiIO& io, const rex::ui::Presenter::GuestFrameStats& stats,
                             const FrameTimeWindow& window, const DeviceSample& device) {
  ImGui::SetNextWindowPos(ImVec2(io.DisplaySize.x - 10.f, 70.f), ImGuiCond_Always, ImVec2(1.f, 0.f));
  ImGui::SetNextWindowBgAlpha(0.72f);
  ImGui::PushFont(nullptr, 12.f);
  if (ImGui::Begin("##nhl_frame_time", nullptr, ImGuiWindowFlags_NoDecoration |
      ImGuiWindowFlags_NoInputs | ImGuiWindowFlags_NoSavedSettings |
      ImGuiWindowFlags_NoFocusOnAppearing | ImGuiWindowFlags_AlwaysAutoResize)) {
    ImGui::Text("Frame %.2f | P50 %.2f | P95 %.2f | P99 %.2f ms", stats.frame_time_ms,
        window.percentiles_ms[0], window.percentiles_ms[1], window.percentiles_ms[2]);
    ImGui::Text("CPU game (sum, no fence) %s | render %s ms/frame",
        HudNumber(device.cpu_game_ms).c_str(), HudNumber(device.cpu_render_ms).c_str());
    ImGui::Text("Guest fence CPU %s | wall %s ms/frame",
        HudNumber(device.fence_spin_ms).c_str(), HudNumber(device.fence_wall_ms).c_str());
    if (device.fences) {
      const auto& fence = *device.fences;
      ImGui::Text("Fence calls %.1f | polls %.0f | pending %.0f | backoff %.1f /frame",
          fence.calls, fence.polls, fence.pending, fence.sleeps);
      ImGui::Text("Fence max CPU %.2f | wall %.2f ms/call | backoff %.2f ms/frame",
          fence.max_cpu_ms, fence.max_wall_ms, fence.sleep_ms);
      std::string sites;
      for (const auto& site : fence.top) if (site.pc) {
        char text[64]; std::snprintf(text, sizeof(text), " %08X:%.2f", site.pc, site.cpu_ms);
        sites += text;
      }
      ImGui::Text("Fence callers%s (inclusive CPU ms/frame)", sites.c_str());
    }
    ImGui::Text("GPU span %s | renderer fence %.2f ms",
        HudNumber(stats.gpu_ms > 0 ? std::optional(stats.gpu_ms) : std::nullopt).c_str(), stats.wait_ms);
    using Wait = rex::ui::diagnostics::Wait;
    ImGui::Text("Present %s | queue lock %s | acquire %s | fence %s ms/call",
        HudNumber(device.waits_ms[size_t(Wait::kPresentCall)]).c_str(),
        HudNumber(device.waits_ms[size_t(Wait::kQueueLock)]).c_str(),
        HudNumber(device.waits_ms[size_t(Wait::kAcquire)]).c_str(),
        HudNumber(device.waits_ms[size_t(Wait::kRetire)]).c_str());
    ImGui::Text("60 FPS cap sleep %s ms/frame | thermal %d",
        HudNumber(device.waits_ms[size_t(Wait::kFrameCap)]).c_str(), device.thermal_status);
    ImGui::Text("Render queue mutex %s | submit call %s ms/frame",
        HudNumber(device.waits_ms[size_t(Wait::kRenderQueueLock)]).c_str(),
        HudNumber(device.waits_ms[size_t(Wait::kRenderSubmit)]).c_str());
    ImGui::Text("GPU %s | CPU perf max current %s MHz",
        HudNumber(device.gpu_mhz, 0).c_str(), HudNumber(device.cpu_big_mhz, 0).c_str());
    ImGui::Text("Temp skin %s | GPU %s | CPU max %s | battery %s C",
        HudNumber(device.skin_c, 1).c_str(), HudNumber(device.gpu_c, 1).c_str(),
        HudNumber(device.cpu_c, 1).c_str(), HudNumber(device.battery_c, 1).c_str());
    ImGui::Text("Max %.2f ms | >100 ms: %zu (2s)", window.max_ms, window.spikes_over_100_ms);
    ImGui::TextUnformatted("Times overlap; GPU span includes queue gaps");
  }
  ImGui::End(); ImGui::PopFont();
}
}  // namespace nhl::diagnostics
