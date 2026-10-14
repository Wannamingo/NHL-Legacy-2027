#include "generated/default/nhllegacy_init.h"
#include "frame_diagnostics.h"
#include <rex/cvar.h>
#include <rex/hook.h>
#include <chrono>
#include <thread>
#include <unistd.h>

REXCVAR_DEFINE_INT32(nhl_fence_wait_us, 0, "Android/Diagnostics",
                    "Experimental pause after 128 GPU watchdog polls (0..1000 us)");
REXCVAR_DEFINE_INT32(nhl_fence_pending_backoff_us, 0, "Android/Diagnostics",
                    "Bounded pause only while the frame fence is still pending (0..250 us)")
    .range(0, 250)
    .lifecycle(rex::cvar::Lifecycle::kHotReload);

REX_HOOK_RAW(sub_827EDAB0) {
  const bool measure = rex::ui::diagnostics::Enabled();
  if (!measure) { __imp__sub_827EDAB0(ctx, base); return; }
  const auto wall = std::chrono::steady_clock::now();
  const auto cpu = nhl::diagnostics::ThreadCpuNanoseconds();
  nhl::diagnostics::FenceObservation observation;
  observation.pc = uint32_t(ctx.lr);
  auto* parent = nhl::diagnostics::active_fence;
  {
    nhl::diagnostics::FenceContext context(observation);
    __imp__sub_827EDAB0(ctx, base);
  }
  const auto cpu_end = nhl::diagnostics::ThreadCpuNanoseconds();
  nhl::diagnostics::RecordFence(observation, cpu_end - cpu, uint64_t(
      std::chrono::duration_cast<std::chrono::nanoseconds>(
          std::chrono::steady_clock::now() - wall).count()), parent == nullptr);
}

REX_HOOK_RAW(sub_827E2BC8) {
  const uint32_t watchdog = ctx.r3.u32;
  // Only this caller has the verified device / fence register layout.
  const bool frame_fence = ctx.lr == 0x827EDB70;
  const uint32_t device = ctx.r31.u32;
  const uint32_t target = ctx.r30.u32;
  __imp__sub_827E2BC8(ctx, base);
  auto* observation = frame_fence ? nhl::diagnostics::active_fence : nullptr;
  if (observation) {
    ++observation->polls;
    if (ctx.r3.u32 == 1) ++observation->pending;
  }
  auto pause = [&](int microseconds) {
    const auto start = observation ? std::chrono::steady_clock::now()
                                   : std::chrono::steady_clock::time_point{};
    std::this_thread::sleep_for(std::chrono::microseconds(microseconds));
    if (observation) {
      ++observation->sleeps;
      observation->sleep_ns += uint64_t(std::chrono::duration_cast<std::chrono::nanoseconds>(
          std::chrono::steady_clock::now() - start).count());
    }
  };
  const int backoff_us = REXCVAR_GET(nhl_fence_pending_backoff_us);
  if (frame_fence && ctx.r3.u32 == 1 && backoff_us > 0 && backoff_us <= 250) {
    // Same unsigned wraparound comparison as sub_827EDAB0. The watchdog's
    // result means "keep waiting", not "fence complete". Preserve its error
    // checks and timeout accounting, then recheck progress before sleeping.
    const uint32_t writeback = REX_LOAD_U32(device + 11024);
    const uint32_t latest = REX_LOAD_U32(device + 11036);
    const uint32_t completed = REX_LOAD_U32(writeback);
    if (uint32_t(latest - target) < uint32_t(latest - completed)) {
      pause(backoff_us);
    }
    return;
  }
  const int pause_us = REXCVAR_GET(nhl_fence_wait_us);
  if (pause_us <= 0 || pause_us > 1000) return;
  // Keep all retail fence checks and timeout accounting. Only back off after
  // repeated polling of the same watchdog. This legacy experiment does not
  // distinguish an active watchdog from a still-pending fence.
  thread_local uint32_t last_watchdog = 0;
  thread_local unsigned polls = 0;
  if (ctx.r3.u32 != 1 || watchdog != last_watchdog) polls = 0;
  last_watchdog = watchdog;
  if (ctx.r3.u32 == 1 && ++polls >= 128) {
    polls = 0;
    pause(pause_us);
  }
}
