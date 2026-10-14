#include "generated/default/nhllegacy_init.h"
#include <rex/hook.h>
#include <rex/logging.h>
#include <atomic>
#include <cstdlib>
#include <cstring>

namespace {
int InternalHeight() {
  // Fixed at game startup: render targets cannot safely change mid-match.
  static const int height = [] {
    const char* mode = std::getenv("NHL_INTERNAL_RESOLUTION");
    return mode && std::strcmp(mode, "540p") == 0 ? 540
         : mode && std::strcmp(mode, "360p") == 0 ? 360 : 720;
  }();
  return height;
}
uint32_t SceneWidth() {
  const char* aspect = std::getenv("NHL_DISPLAY_ASPECT");
  return (aspect && std::strcmp(aspect, "4:3") == 0 ? 960u : 1280u) * InternalHeight() / 720;
}
void ResizeSceneSettings(uint8_t* base, uint32_t address, uint32_t offset) {
  if (InternalHeight() == 720 || !address) return;
  // These are retail NHL scene descriptors, separate from the D3D device.
  // Only change the default full-screen extent, never unrelated texture sizes.
  if (REX_LOAD_U32(address + offset) == 1280 &&
      REX_LOAD_U32(address + offset + 4) == 720) {
    REX_STORE_U32(address + offset, SceneWidth());
    REX_STORE_U32(address + offset + 4, InternalHeight());
    static std::atomic<unsigned> messages{0};
    if (messages.fetch_add(1, std::memory_order_relaxed) < 12)
      REXLOG_INFO("NHL scene descriptor {:08X}+{}: {}x{}", address, offset,
                  SceneWidth(), InternalHeight());
  }
}
}
void NhlInternalWidth(PPCRegister& width) {
  width.u64 = width.u32 * InternalHeight() / 720;
  REXLOG_INFO("NHL internal render buffers: {}x{}", width.u32, InternalHeight());
}
void NhlInternalHeight(PPCRegister& height) {
  height.u64 = InternalHeight();
}

// Strong hooks wrap the generated __imp__ functions; no generated edits.
REX_HOOK_RAW(sub_836DE460) {
  if (InternalHeight() != 720 && ctx.r4.u32 == 1280 && ctx.r5.u32 == 720) {
    ctx.r4.u64 = SceneWidth(); ctx.r5.u64 = InternalHeight();
    REXLOG_INFO("NHL scene manager extent: {}x{}", SceneWidth(), InternalHeight());
  }
  __imp__sub_836DE460(ctx, base);
}
REX_HOOK_RAW(sub_8367CB30) {
  const uint32_t settings = ctx.r3.u32;
  __imp__sub_8367CB30(ctx, base);
  ResizeSceneSettings(base, settings, 16);
}
REX_HOOK_RAW(sub_836827B0) {
  ResizeSceneSettings(base, ctx.r3.u32, 80);
  __imp__sub_836827B0(ctx, base);
}

REX_HOOK_RAW(sub_83677B10) {
  const uint32_t settings = ctx.r3.u32;
  __imp__sub_83677B10(ctx, base);
  // The device query refreshes the descriptor at every frame, after the
  // caller's entry hook. Keep the scene extent consistent after that query.
  ResizeSceneSettings(base, settings, 80);
}

REX_HOOK_RAW(sub_8346B000) {
  // The UI render loop registers this descriptor after writing retail 720p
  // dimensions. Resize it before registration, while it is still unused.
  if (uint32_t(ctx.lr) == 0x8360FEE8)
    ResizeSceneSettings(base, ctx.r4.u32, 16);
  __imp__sub_8346B000(ctx, base);
}
