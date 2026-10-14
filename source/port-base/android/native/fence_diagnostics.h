#pragma once
#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>

namespace nhl::diagnostics {
// A hook counts polls in its own stack-local observation. Publish once on
// return, rather than taking a clock or updating shared atomics per poll.
struct FenceObservation {
  uint32_t pc = 0;
  uint64_t polls = 0, pending = 0, sleeps = 0, sleep_ns = 0;
};
inline thread_local FenceObservation* active_fence = nullptr;
struct FenceContext {
  FenceObservation* previous;
  explicit FenceContext(FenceObservation& observation) : previous(active_fence) {
    active_fence = &observation;
  }
  ~FenceContext() { active_fence = previous; }
  FenceContext(const FenceContext&) = delete;
  FenceContext& operator=(const FenceContext&) = delete;
};
inline std::atomic<uint64_t> fence_cpu_ns{0}, fence_wall_ns{0};

struct FenceTotals {
  uint64_t calls = 0, polls = 0, pending = 0, sleeps = 0, sleep_ns = 0;
  uint64_t cpu_ns = 0, wall_ns = 0, max_cpu_ns = 0, max_wall_ns = 0;
  uint64_t overflow_calls = 0;
};
struct FenceSiteTotals { uint32_t pc = 0; uint64_t calls = 0, cpu_ns = 0, wall_ns = 0; };
constexpr size_t kFenceSites = 32;
struct FenceSnapshot {
  FenceTotals total;
  std::array<FenceSiteTotals, kFenceSites> sites{};
};
namespace fence_detail {
inline std::atomic<uint64_t> calls{0}, polls{0}, pending{0}, sleeps{0}, sleep_ns{0};
inline std::atomic<uint64_t> max_cpu_ns{0}, max_wall_ns{0}, overflow_calls{0};
struct Site {
  std::atomic<uint32_t> pc{0};
  std::atomic<uint64_t> calls{0}, cpu_ns{0}, wall_ns{0};
};
inline std::array<Site, kFenceSites> sites{};
inline void Max(std::atomic<uint64_t>& counter, uint64_t value) {
  auto previous = counter.load(std::memory_order_relaxed);
  while (previous < value && !counter.compare_exchange_weak(
      previous, value, std::memory_order_relaxed)) { }
}
}  // namespace fence_detail

inline void RecordFence(const FenceObservation& observation, uint64_t cpu_ns,
                        uint64_t wall_ns, bool outermost) {
  using namespace fence_detail;
  calls.fetch_add(1, std::memory_order_relaxed);
  polls.fetch_add(observation.polls, std::memory_order_relaxed);
  pending.fetch_add(observation.pending, std::memory_order_relaxed);
  sleeps.fetch_add(observation.sleeps, std::memory_order_relaxed);
  sleep_ns.fetch_add(observation.sleep_ns, std::memory_order_relaxed);
  // Retail submission can recursively call the fence function. Inclusive
  // site attribution is useful, but aggregate CPU/wall must count it once.
  if (outermost) {
    fence_cpu_ns.fetch_add(cpu_ns, std::memory_order_relaxed);
    fence_wall_ns.fetch_add(wall_ns, std::memory_order_relaxed);
    Max(max_cpu_ns, cpu_ns); Max(max_wall_ns, wall_ns);
  }
  if (observation.pc) for (auto& site : sites) {
    uint32_t pc = site.pc.load(std::memory_order_relaxed);
    if (!pc && site.pc.compare_exchange_strong(pc, observation.pc,
                                               std::memory_order_relaxed))
      pc = observation.pc;
    if (pc != observation.pc) continue;
    site.calls.fetch_add(1, std::memory_order_relaxed);
    site.cpu_ns.fetch_add(cpu_ns, std::memory_order_relaxed);
    site.wall_ns.fetch_add(wall_ns, std::memory_order_relaxed);
    return;
  }
  overflow_calls.fetch_add(1, std::memory_order_relaxed);
}

inline FenceSnapshot ReadFences(bool consume_peaks) {
  using namespace fence_detail;
  FenceSnapshot result;
  auto& total = result.total;
  total.calls = calls.load(std::memory_order_relaxed);
  total.polls = polls.load(std::memory_order_relaxed);
  total.pending = pending.load(std::memory_order_relaxed);
  total.sleeps = sleeps.load(std::memory_order_relaxed);
  total.sleep_ns = sleep_ns.load(std::memory_order_relaxed);
  total.cpu_ns = fence_cpu_ns.load(std::memory_order_relaxed);
  total.wall_ns = fence_wall_ns.load(std::memory_order_relaxed);
  total.max_cpu_ns = consume_peaks ? max_cpu_ns.exchange(0, std::memory_order_relaxed)
                                  : max_cpu_ns.load(std::memory_order_relaxed);
  total.max_wall_ns = consume_peaks ? max_wall_ns.exchange(0, std::memory_order_relaxed)
                                   : max_wall_ns.load(std::memory_order_relaxed);
  total.overflow_calls = overflow_calls.load(std::memory_order_relaxed);
  for (size_t i = 0; i < sites.size(); ++i) {
    result.sites[i] = {sites[i].pc.load(std::memory_order_relaxed),
        sites[i].calls.load(std::memory_order_relaxed),
        sites[i].cpu_ns.load(std::memory_order_relaxed),
        sites[i].wall_ns.load(std::memory_order_relaxed)};
  }
  return result;
}
struct FenceSiteSample {
  uint32_t pc = 0;
  double calls = 0, cpu_ms = 0, wall_ms = 0;
};
struct FenceSample {
  double calls = 0, polls = 0, pending = 0, sleeps = 0, sleep_ms = 0;
  double max_cpu_ms = 0, max_wall_ms = 0, overflow_calls = 0;
  std::array<FenceSiteSample, 5> top{};
};
inline FenceSample SummarizeFences(const FenceSnapshot& before,
                                 const FenceSnapshot& after, uint64_t frames) {
  FenceSample result;
  if (!frames) return result;
  const double count = double(frames);
  const auto& a = after.total; const auto& b = before.total;
  result.calls = double(a.calls - b.calls) / count;
  result.polls = double(a.polls - b.polls) / count;
  result.pending = double(a.pending - b.pending) / count;
  result.sleeps = double(a.sleeps - b.sleeps) / count;
  result.sleep_ms = double(a.sleep_ns - b.sleep_ns) * 1e-6 / count;
  result.max_cpu_ms = double(a.max_cpu_ns) * 1e-6;
  result.max_wall_ms = double(a.max_wall_ns) * 1e-6;
  result.overflow_calls = double(a.overflow_calls - b.overflow_calls) / count;
  std::array<FenceSiteSample, kFenceSites> sites{};
  for (size_t i = 0; i < sites.size(); ++i) {
    const auto& current = after.sites[i]; const auto& previous = before.sites[i];
    if (!current.pc || current.calls <= previous.calls) continue;
    sites[i] = {current.pc, double(current.calls - previous.calls) / count,
        double(current.cpu_ns - previous.cpu_ns) * 1e-6 / count,
        double(current.wall_ns - previous.wall_ns) * 1e-6 / count};
  }
  std::sort(sites.begin(), sites.end(), [](const auto& a, const auto& b) {
    return a.cpu_ms > b.cpu_ms || (a.cpu_ms == b.cpu_ms && a.calls > b.calls);
  });
  std::copy_n(sites.begin(), result.top.size(), result.top.begin());
  return result;
}
}  // namespace nhl::diagnostics
