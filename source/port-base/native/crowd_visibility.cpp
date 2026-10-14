#include "generated/default/nhllegacy_init.h"
#include <rex/hook.h>
#include <rex/logging.h>
#include <atomic>
#include <cstdlib>
#include <cstring>

namespace {
bool CrowdEnabled() {
  // Fixed for a game launch, like the resolution and shadow selections.
  static const bool enabled = [] {
    const char* value = std::getenv("NHL_CROWD_ENABLED");
    const bool result = !value || std::strcmp(value, "false") != 0;
    REXLOG_INFO("NHL spectators: {}", result ? "enabled" : "disabled");
    return result;
  }();
  return enabled;
}
bool SkipCrowdPass(uint32_t address) {
  if (CrowdEnabled()) return false;
  static std::atomic<unsigned> logged{0};
  if (logged.fetch_add(1, std::memory_order_relaxed) < 4)
    REXLOG_INFO("NHL spectator render pass suppressed: {:08X}", address);
  return true;
}
}

// Skip each complete crowd render pass, including state setup and instance
// uploads. The dynamic retail renderer already has an early visibility exit;
// neither pass owns simulation updates or has a required return value.
// The normal crowd update and asset loading remain intact.
REX_HOOK_RAW(sub_8368FDE0) {
  if (SkipCrowdPass(0x8368FDE0)) return;
  __imp__sub_8368FDE0(ctx, base);
}
REX_HOOK_RAW(sub_83691160) {
  if (SkipCrowdPass(0x83691160)) return;
  __imp__sub_83691160(ctx, base);
}

REX_HOOK_RAW(sub_827EED20) {
  const uint32_t caller = uint32_t(ctx.lr);
  // Only the two retail crowd instance renderers. Other indexed draws include
  // players, boards and arena geometry and must always reach the real routine.
  // Both call sites ignore volatile output registers after the draw.
  const bool crowd = (caller >= 0x8368FDE0 && caller < 0x83690240) ||
                     (caller >= 0x83691160 && caller < 0x83691678);
  if (crowd && !CrowdEnabled()) {
    static std::atomic<unsigned> logged{0};
    if (logged.fetch_add(1, std::memory_order_relaxed) < 4)
      REXLOG_INFO("NHL spectator draw suppressed: caller={:08X}, indices={}", caller, ctx.r7.u32);
    return;
  }
  __imp__sub_827EED20(ctx, base);
}
