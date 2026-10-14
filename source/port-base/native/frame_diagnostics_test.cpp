#include "frame_time_hud.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <sstream>
#include <thread>
#include <pthread.h>
#include <unistd.h>

void Check(bool value) { if (!value) { std::puts("FAILED"); std::exit(1); } }
int main() {
  using namespace nhl::diagnostics;
  Check(SummarizeFrames({}).intervals == 0);
  Check(SummarizeFrames({100, 100, 90}).intervals == 0);
  auto frame = SummarizeFrames({0, 16667, 33334, 533334});
  Check(frame.intervals == 3 && frame.spikes_over_100_ms == 1);
  Check(std::abs(frame.percentiles_ms[0] - 16.667) < .001);
  Check(frame.percentiles_ms[1] == 500 && frame.percentiles_ms[2] == 500);
  // Keep a scene change spike crossing the start of the rolling window.
  frame = SummarizeFrames({0, 1000, 3000000, 3010000});
  Check(frame.intervals == 2 && frame.max_ms == 2999);
  std::ostringstream line;
  line << "4321 (Main XThread (x)) R";
  for (int i = 1; i < 11; ++i) line << " 0";
  line << " 120 30 0";
  auto parsed = ParseThreadStat(line.str());
  Check(parsed && parsed->tid == 4321 && parsed->ticks == 150 &&
         parsed->name == "Main XThread (x)");
  Check(!ParseThreadStat("corrupt") && !ParseThreadStat("1 (x) R 0"));
  // Nested retail submission must not subtract the same fence CPU twice
  // from guest CPU. Site attribution remains explicitly inclusive.
  auto fence_before = ReadFences(true);
  FenceObservation outer{0x827EDABC, 10, 8, 1, 500000};
  FenceObservation inner{0x827ED9CC, 4, 3, 0, 0};
  {
    FenceContext a(outer); Check(active_fence == &outer);
    { FenceContext b(inner); Check(active_fence == &inner); }
    Check(active_fence == &outer);
  }
  Check(active_fence == nullptr);
  RecordFence(inner, 1000000, 2000000, false);
  RecordFence(outer, 5000000, 10000000, true);
  auto fence_after = ReadFences(true);
  Check(fence_after.total.cpu_ns - fence_before.total.cpu_ns == 5000000);
  Check(fence_after.total.wall_ns - fence_before.total.wall_ns == 10000000);
  auto summary = SummarizeFences(fence_before, fence_after, 2);
  Check(summary.calls == 1 && summary.polls == 7 && summary.pending == 5.5);
  Check(summary.sleeps == .5 && summary.sleep_ms == .25);
  Check(summary.max_cpu_ms == 5 && summary.max_wall_ms == 10);
  Check(summary.top[0].pc == outer.pc && summary.top[0].cpu_ms == 2.5);
  Check(ReadFences(false).total.max_wall_ns == 0);
  Check(SummarizeFences(fence_before, fence_after, 0).calls == 0);
  // Concurrent completion counters must preserve counts and maxima; no
  // locks or shared updates are needed inside the retail polling loop.
  fence_before = ReadFences(true);
  std::array<std::thread, 4> completions;
  for (auto& worker : completions) worker = std::thread([] {
    for (unsigned i = 0; i < 1000; ++i)
      RecordFence(FenceObservation{0x827EDB70, 3, 2, 0, 0}, 100, 200, true);
  });
  for (auto& worker : completions) worker.join();
  fence_after = ReadFences(true);
  Check(fence_after.total.calls - fence_before.total.calls == 4000);
  Check(fence_after.total.polls - fence_before.total.polls == 12000);
  Check(fence_after.total.cpu_ns - fence_before.total.cpu_ns == 400000);
  summary = SummarizeFences(fence_before, fence_after, 100);
  Check(summary.top[0].pc == 0x827EDB70 && summary.top[0].calls == 40);
  // Bounded attribution never drops aggregate calls when its table fills.
  fence_before = ReadFences(true);
  for (size_t i = 0; i < kFenceSites + 1; ++i)
    RecordFence(FenceObservation{uint32_t(0x90000000 + 4 * i)}, 1, 1, true);
  fence_after = ReadFences(true);
  Check(fence_after.total.calls - fence_before.total.calls == kFenceSites + 1);
  Check(fence_after.total.overflow_calls > fence_before.total.overflow_calls);
  using namespace rex::ui::diagnostics;
  SetEnabled(false);
  auto before = ReadTotals();
  { WaitTimer disabled(Wait::kAcquire); }
  Check(ReadTotals().calls == before.calls);
  SetEnabled(true);
  { WaitTimer enabled(Wait::kAcquire); }
  Check(ReadTotals().calls[0] == before.calls[0] + 1);
  // Reproduce two identically named GPU threads: CPU sampling must follow
  // the registered producer rather than whichever /proc entry comes last.
  std::atomic<bool> stop{false}, ready{false};
  std::thread idle([&] {
    pthread_setname_np(pthread_self(), "GPU Commands");
    while (!stop.load()) std::this_thread::sleep_for(std::chrono::milliseconds(1));
  });
  std::thread render([&] {
    pthread_setname_np(pthread_self(), "GPU Commands");
    SetRenderThreadId(gettid()); ready.store(true);
    while (!stop.load()) { std::atomic_signal_fence(std::memory_order_seq_cst); }
  });
  while (!ready.load()) std::this_thread::yield();
  pthread_setname_np(pthread_self(), "XThreadTest");
  DeviceSampler sampler;
  sampler.Sample(100, "/data/local/tmp/nhl-frame-diagnostics-test");
  const auto cpu_start = ThreadCpuNanoseconds();
  while (ThreadCpuNanoseconds() - cpu_start < 50000000) { }
  for (int submit = 0; submit < 3; ++submit) {
    AddWait(Wait::kRenderQueueLock, 2000000);
    AddWait(Wait::kRenderSubmit, 1000000);
  }
  auto sampled = sampler.Sample(160, "/data/local/tmp/nhl-frame-diagnostics-test");
  stop.store(true); render.join(); idle.join();
  Check(sampled.cpu_game_ms && *sampled.cpu_game_ms > .01);
  Check(sampled.cpu_render_ms && *sampled.cpu_render_ms > .01);
  const auto lock = sampled.waits_ms[size_t(Wait::kRenderQueueLock)];
  const auto submit = sampled.waits_ms[size_t(Wait::kRenderSubmit)];
  Check(lock && *lock > .099 && *lock < .101);
  Check(submit && *submit > .049 && *submit < .051);
  SetEnabled(false);
  std::puts("PASS: scene spikes, CPU parsing, duplicate threads, opt-in timing, nested and concurrent fences");
}
