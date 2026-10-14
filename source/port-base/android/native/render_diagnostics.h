#pragma once
#include "frame_time_hud.h"
#include <rex/cvar.h>
#include <rex/graphics/graphics_system.h>
#include <rex/graphics/command_processor.h>
#include <rex/logging.h>
#include <rex/thread.h>
#include <rex/ui/imgui_dialog.h>
#include <rex/ui/presenter.h>
#include <chrono>
#include <filesystem>
#include <fstream>

REXCVAR_DECLARE(bool, nhl_render_diagnostics);
REXCVAR_DECLARE(bool, nhl_capture_gpu_frame);
REXCVAR_DECLARE(bool, nhl_benchmark_frames);
REXCVAR_DECLARE(bool, nhl_frame_time_hud);
REXCVAR_DECLARE(std::string, android_thread_placement_map);

// Optional developer diagnostics. Never requests extra repaints. The HUD is
// opt-in and is independent of file recording and expensive GPU timestamps.
class NhlRenderDiagnosticsDialog final : public rex::ui::ImGuiDialog {
 public:
  NhlRenderDiagnosticsDialog(rex::ui::ImGuiDrawer* drawer,
                            rex::graphics::GraphicsSystem* graphics,
                            std::filesystem::path settings)
      : ImGuiDialog(drawer), graphics_(graphics), settings_(std::move(settings)) {}
  bool WantsContinuousRepaint() const override { return false; }
 protected:
  void OnDraw(ImGuiIO& io) override {
    const auto now = Clock::now();
    auto* presenter = graphics_ ? graphics_->presenter() : nullptr;
    const bool measure = REXCVAR_GET(nhl_frame_time_hud) || REXCVAR_GET(nhl_render_diagnostics);
    if (measure != measuring_) {
      measuring_ = measure;
      rex::ui::diagnostics::SetEnabled(measure);
      device_sampler_.Reset();
      next_sample_ = {};
    }
    if (presenter && now >= next_sample_) {
      next_sample_ = now + std::chrono::seconds(1);
      if (REXCVAR_GET(nhl_frame_time_hud))
        frame_window_ = nhl::diagnostics::SummarizeFrames(
            presenter->GetGuestFrameTimestampsMicroseconds());
      if (measure) {
        device_sample_ = device_sampler_.Sample(presenter->GetGuestFrameStats().frame_count,
                                                settings_.parent_path());
        if (REXCVAR_GET(nhl_benchmark_frames)) {
          if (!device_log_.is_open()) {
            device_log_.open(settings_.parent_path() / "android-device-times.csv", std::ios::trunc);
            device_log_ << "sample_us,frames,cpu_game_ms,cpu_render_ms,fence_spin_ms,fence_wall_ms,"
                "acquire_ms,present_ms,retire_ms,cap_ms,queue_lock_ms,render_queue_ms,render_submit_ms,"
                "gpu_span_ms,gpu_mhz,cpu_big_mhz,"
                "skin_c,gpu_c,cpu_c,battery_c,thermal_status,sample_cpu_ms,"
                "fence_calls_per_frame,fence_polls_per_frame,fence_pending_per_frame,"
                "fence_backoff_calls_per_frame,fence_backoff_ms_per_frame,"
                "fence_max_cpu_ms,fence_max_wall_ms,fence_unattributed_calls_per_frame\n";
          }
          const auto stats = presenter->GetGuestFrameStats();
          device_log_ << std::chrono::duration_cast<std::chrono::microseconds>(now.time_since_epoch()).count()
                      << ',' << stats.frame_count;
          auto write = [&](const std::optional<double>& value) {
            device_log_ << ','; if (value) device_log_ << *value;
          };
          write(device_sample_.cpu_game_ms); write(device_sample_.cpu_render_ms);
          write(device_sample_.fence_spin_ms); write(device_sample_.fence_wall_ms);
          for (const auto& value : device_sample_.waits_ms) write(value);
          device_log_ << ','; if (stats.gpu_ms > 0) device_log_ << stats.gpu_ms;
          write(device_sample_.gpu_mhz); write(device_sample_.cpu_big_mhz);
          write(device_sample_.skin_c); write(device_sample_.gpu_c); write(device_sample_.cpu_c);
          write(device_sample_.battery_c);
          device_log_ << ',' << device_sample_.thermal_status << ',' << device_sample_.sample_cpu_ms;
          if (device_sample_.fences) {
            const auto& fence = *device_sample_.fences;
            device_log_ << ',' << fence.calls << ',' << fence.polls << ',' << fence.pending
                        << ',' << fence.sleeps << ',' << fence.sleep_ms << ',' << fence.max_cpu_ms
                        << ',' << fence.max_wall_ms << ',' << fence.overflow_calls;
            if (!fence_log_.is_open()) {
              fence_log_.open(settings_.parent_path() / "android-fence-callers.csv", std::ios::trunc);
              fence_log_ << "sample_us,frames,guest_return_pc,calls_per_frame,"
                            "inclusive_cpu_ms_per_frame,inclusive_wall_ms_per_frame\n";
            }
            for (const auto& site : fence.top) if (site.pc) {
              fence_log_ << std::chrono::duration_cast<std::chrono::microseconds>(
                  now.time_since_epoch()).count() << ',' << stats.frame_count << ",0x"
                  << std::hex << site.pc << std::dec << ',' << site.calls << ','
                  << site.cpu_ms << ',' << site.wall_ms << '\n';
            }
            fence_log_.flush();
          } else device_log_ << ",,,,,,,,";
          device_log_ << '\n';
          device_log_.flush();
        }
      }
      if (!measure || !REXCVAR_GET(nhl_benchmark_frames)) {
        if (device_log_.is_open()) device_log_.close();
        if (fence_log_.is_open()) fence_log_.close();
      }
      if (REXCVAR_GET(nhl_benchmark_frames)) {
        auto times = presenter->GetGuestFrameTimestampsMicroseconds();
        if (!frame_log_.is_open()) {
          frame_log_.open(settings_.parent_path() / "android-frame-times.csv", std::ios::trunc);
          frame_log_ << "timestamp_us,frame_us\n";
          // Start with frames produced after diagnostics was enabled.
          last_frame_time_ = times.empty() ? 0 : times.back();
        }
        for (uint64_t time : times) if (time > last_frame_time_) {
          if (last_frame_time_) frame_log_ << time << ',' << time - last_frame_time_ << '\n';
          last_frame_time_ = time;
        }
        frame_log_.flush();
      } else if (frame_log_.is_open()) {
        frame_log_.close(); last_frame_time_ = 0;
      }
    }
    if (now >= next_check_) {
      next_check_ = now + std::chrono::seconds(2);
      std::error_code error;
      const auto modified = std::filesystem::last_write_time(settings_, error);
      if (!error && modified != last_modified_) {
        last_modified_ = modified;
        // The readback mode is a string consumed by the GPU worker. Update
        // it on that worker between packets, rather than while it is read.
        const auto settings = settings_;
        graphics_->command_processor()->CallInThread([settings] {
          const auto previous_placement = REXCVAR_GET(android_thread_placement_map);
          rex::cvar::LoadConfig(settings);
          // Android permits the running thread to change its own placement.
          // Use the SDK's existing name-based policy for live comparisons.
          // Guest thread placement still takes effect only at creation.
          const auto& placement = REXCVAR_GET(android_thread_placement_map);
          if (!placement.empty() && placement != previous_placement) {
            auto* thread = rex::thread::Thread::GetCurrentThread();
            if (thread && thread->name().rfind("GPU Commands", 0) == 0)
              rex::thread::set_current_thread_name(thread->name());
          }
          REXLOG_INFO("NHL renderer overrides reloaded");
        });
      }
    }
    if (!REXCVAR_GET(nhl_render_diagnostics) &&
        !REXCVAR_GET(nhl_capture_gpu_frame) && !REXCVAR_GET(nhl_frame_time_hud)) return;
    if (!presenter) return;
    const auto stats = presenter->GetGuestFrameStats();
    if (REXCVAR_GET(nhl_frame_time_hud))
      nhl::diagnostics::DrawFrameTimeHud(io, stats, frame_window_, device_sample_);
    if (stats.frame_count && REXCVAR_GET(nhl_capture_gpu_frame)) {
      REXCVAR_SET(nhl_capture_gpu_frame, false);
      // Trace state belongs to the GPU command thread.
      auto* graphics = graphics_;
      graphics_->command_processor()->CallInThread(
          [graphics] { graphics->RequestFrameTrace(); });
      REXLOG_INFO("NHL GPU frame capture requested");
    }
    if (REXCVAR_GET(nhl_render_diagnostics) && now >= next_log_) {
      next_log_ = now + std::chrono::seconds(5);
      REXLOG_INFO("NHL render timing: fps={:.1f} frame={:.1f}ms gpu={:.1f}ms "
                  "wait={:.1f}ms draw={:.1f}ms resolve={:.1f}ms dump={:.1f}ms",
                  stats.fps, stats.frame_time_ms, stats.gpu_ms, stats.wait_ms,
                  stats.gpu_draw_ms, stats.gpu_resolve_ms, stats.gpu_dump_ms);
    }
  }
 private:
  using Clock = std::chrono::steady_clock;
  rex::graphics::GraphicsSystem* graphics_;
  std::filesystem::path settings_;
  std::filesystem::file_time_type last_modified_{};
  Clock::time_point next_check_{}, next_log_{};
  Clock::time_point next_sample_{};
  std::ofstream frame_log_;
  uint64_t last_frame_time_ = 0;
  nhl::diagnostics::FrameTimeWindow frame_window_;
  nhl::diagnostics::DeviceSampler device_sampler_;
  nhl::diagnostics::DeviceSample device_sample_;
  std::ofstream device_log_;
  std::ofstream fence_log_;
  bool measuring_ = false;
};
