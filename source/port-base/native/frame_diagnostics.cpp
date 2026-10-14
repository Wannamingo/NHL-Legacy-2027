#include "frame_diagnostics.h"
#include <algorithm>
#include <cmath>
#include <fstream>
#include <sstream>
#include <time.h>
#include <unistd.h>
#include <jni.h>

namespace nhl::diagnostics {
namespace {
std::atomic<float> battery_temperature{-1};
std::atomic<int> thermal_status{-1};
double ClockSeconds(clockid_t clock) {
  timespec t{};
  if (clock_gettime(clock, &t)) return 0;
  return double(t.tv_sec) + double(t.tv_nsec) * 1e-9;
}
std::optional<double> ReadNumber(const std::filesystem::path& path, double scale) {
  std::ifstream input(path);
  double value;
  if (!(input >> value) || !std::isfinite(value) || value <= 0) return {};
  return value * scale;
}
void ReadHardware(const std::filesystem::path& user, DeviceSample& out) {
  // kHz on Linux CPU cpufreq, Hz on KGSL devfreq. Read once per HUD sample.
  for (int cpu = 2; cpu < 8; ++cpu) {
    auto value = ReadNumber("/sys/devices/system/cpu/cpu" + std::to_string(cpu) +
                           "/cpufreq/scaling_cur_freq", .001);
    if (value && (!out.cpu_big_mhz || *value > *out.cpu_big_mhz)) out.cpu_big_mhz = value;
  }
  out.gpu_mhz = ReadNumber("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq", .000001);
  const float battery = battery_temperature.load(std::memory_order_relaxed);
  if (battery >= -20 && battery <= 100 && battery != -1) out.battery_c = battery;
  out.thermal_status = thermal_status.load(std::memory_order_relaxed);
  // Optional ADB companion uses fresh HAL readings; stale samples never persist
  // in the HUD. CLOCK_BOOTTIME matches /proc/uptime (also across suspend).
  std::ifstream input(user / "android-hardware.txt");
  double time, gpu, cpu, skin, gpu_temp, cpu_temp;
  int status;
  const double now = ClockSeconds(CLOCK_BOOTTIME);
  if (input >> time >> gpu >> cpu >> skin >> gpu_temp >> cpu_temp >> status) {
    if (std::isfinite(time) && now >= time && now - time <= 8) {
      if (std::isfinite(gpu) && gpu > 0) out.gpu_mhz = gpu;
      if (std::isfinite(cpu) && cpu > 0 && !out.cpu_big_mhz) out.cpu_big_mhz = cpu;
      auto temperature = [](double t) -> std::optional<double> {
        return std::isfinite(t) && t >= -20 && t <= 130 ? std::optional(t) : std::nullopt;
      };
      out.skin_c = temperature(skin); out.gpu_c = temperature(gpu_temp);
      out.cpu_c = temperature(cpu_temp);
      if (status >= 0 && status <= 6) out.thermal_status = status;
    }
  }
}
}  // namespace

uint64_t ThreadCpuNanoseconds() {
  timespec t{};
  if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &t)) return 0;
  return uint64_t(t.tv_sec) * 1000000000 + uint64_t(t.tv_nsec);
}
std::optional<ThreadTicks> ParseThreadStat(const std::string& text) {
  const auto start = text.find('('), end = text.rfind(") ");
  if (start == std::string::npos || end == std::string::npos || end <= start) return {};
  ThreadTicks result;
  std::istringstream pid(text.substr(0, start));
  if (!(pid >> result.tid) || result.tid <= 0) return {};
  result.name = text.substr(start + 1, end - start - 1);
  std::istringstream fields(text.substr(end + 2));
  std::string field;
  uint64_t user = 0, system = 0;
  // fields start at field 3 (state); utime/stime are fields 14 and 15.
  for (int i = 0; i <= 12; ++i) {
    if (!(fields >> field)) return {};
    if (i == 11 || i == 12) {
      std::istringstream number(field);
      uint64_t value;
      if (!(number >> value)) return {};
      if (i == 11) user = value; else system = value;
    }
  }
  result.ticks = user + system;
  return result;
}
void DeviceSampler::Reset() {
  primed_ = false; last_guest_.clear(); last_render_.reset();
}
DeviceSample DeviceSampler::Sample(uint64_t frames, const std::filesystem::path& user) {
  const auto sample_cpu_start = ThreadCpuNanoseconds();
  DeviceSample out;
  std::map<int, ThreadTicks> guest;
  std::optional<ThreadTicks> render;
  const int render_tid = rex::ui::diagnostics::RenderThreadId();
  std::error_code error;
  for (const auto& task : std::filesystem::directory_iterator("/proc/self/task", error)) {
    std::ifstream input(task.path() / "stat");
    std::string text; std::getline(input, text);
    auto stat = ParseThreadStat(text);
    if (!stat) continue;
    if (stat->name.rfind("XThread", 0) == 0 || stat->name.rfind("Main XThread", 0) == 0)
      guest.emplace(stat->tid, *stat);
    if (stat->tid == render_tid) render = stat;
  }
  const auto waits = rex::ui::diagnostics::ReadTotals();
  const auto fences = ReadFences(true);
  const auto cpu = fences.total.cpu_ns;
  const auto wall = fences.total.wall_ns;
  const double now = ClockSeconds(CLOCK_MONOTONIC);
  static const double tick_ms = 1000.0 / sysconf(_SC_CLK_TCK);
  if (primed_ && frames > last_frames_ && now - last_time_ <= 5) {
    const double count = double(frames - last_frames_);
    out.fences = SummarizeFences(last_fences_, fences, frames - last_frames_);
    auto elapsed = [&](const auto& previous, const auto& current) -> std::optional<double> {
      if (!previous || !current || previous->tid != current->tid ||
          current->ticks < previous->ticks) return {};
      return double(current->ticks - previous->ticks) * tick_ms / count;
    };
    std::optional<double> total;
    for (const auto& [tid, current] : guest) {
      const auto previous = last_guest_.find(tid);
      if (previous != last_guest_.end() && current.ticks >= previous->second.ticks) {
        if (!total) total = 0;
        *total += double(current.ticks - previous->second.ticks) * tick_ms / count;
      }
    }
    out.cpu_render_ms = elapsed(last_render_, render);
    if (total) {
      out.fence_spin_ms = double(cpu - last_fence_cpu_) * 1e-6 / count;
      out.fence_wall_ms = double(wall - last_fence_wall_) * 1e-6 / count;
      out.cpu_game_ms = std::max(0.0, *total - *out.fence_spin_ms);
    }
    for (size_t i = 0; i < waits.calls.size(); ++i) {
      const auto calls = waits.calls[i] - last_waits_.calls[i];
      if (calls) {
        // Render submits may happen several times per guest frame. Report the
        // accumulated host blocking per frame, rather than per submit call.
        const bool render_wait = i == size_t(rex::ui::diagnostics::Wait::kRenderQueueLock) ||
                                 i == size_t(rex::ui::diagnostics::Wait::kRenderSubmit);
        out.waits_ms[i] = double(waits.nanoseconds[i] - last_waits_.nanoseconds[i]) *
                         1e-6 / (render_wait ? count : double(calls));
      }
    }
  }
  last_guest_ = std::move(guest); last_render_ = render; last_frames_ = frames;
  last_fence_cpu_ = cpu; last_fence_wall_ = wall; last_waits_ = waits;
  last_fences_ = fences;
  last_time_ = now; primed_ = true;
  ReadHardware(user, out);
  out.sample_cpu_ms = double(ThreadCpuNanoseconds() - sample_cpu_start) * 1e-6;
  return out;
}
}  // namespace nhl::diagnostics

extern "C" JNIEXPORT jboolean JNICALL
Java_org_nhllegacy_port_GameActivity_nativeDiagnosticsEnabled(JNIEnv*, jclass) {
  return rex::ui::diagnostics::Enabled() ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL
Java_org_nhllegacy_port_GameActivity_nativeHardwareSample(JNIEnv*, jclass, jfloat battery, jint status) {
  nhl::diagnostics::battery_temperature.store(battery, std::memory_order_relaxed);
  nhl::diagnostics::thermal_status.store(status, std::memory_order_relaxed);
}
