#include "generated/default/nhllegacy_init.h"
#include <rex/cvar.h>
#include <rex/hook.h>
#include <rex/logging.h>
#include <rex/memory.h>
#include <atomic>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>

REXCVAR_DEFINE_BOOL(nhl_crowd_diagnostics, false, "Android/Diagnostics",
                    "Log a bounded sample of crowd placement uploads");

namespace {
bool Readable(void* address) {
  size_t length = 4;
  rex::memory::PageAccess access;
  return rex::memory::QueryProtect(address, length, access) &&
         access != rex::memory::PageAccess::kNoAccess && length >= 4;
}
float GuestFloat(uint8_t* base, uint32_t address) {
  const uint32_t bits = REX_LOAD_U32(address);
  float value;
  std::memcpy(&value, &bits, sizeof(value));
  return value;
}
void SampleCrowdCopy(PPCContext& ctx, uint8_t* base, uint32_t source,
                     uint32_t length, uint32_t destination, const char* stage) {
  // Inspect bounded uploads only. No changes to data or guest state.
  static std::atomic<unsigned> memcpy_samples{0}, cache_samples{0}, upload_samples{0};
  auto& samples = std::strcmp(stage, "vs-cache") == 0 ? cache_samples :
                  std::strcmp(stage, "vs-upload") == 0 ? upload_samples : memcpy_samples;
  if (samples.load(std::memory_order_relaxed) >= 24) return;
  if (length < 32 || length > 2048 || source < 0x10000 || source > 0xFFFFF000 ||
      !Readable(base + source) || !Readable(base + source + length - 4)) return;
  for (uint32_t offset = 0; offset + 32 <= length; offset += 16) {
    const float x = GuestFloat(base, source + offset);
    const float y = GuestFloat(base, source + offset + 4);
    const float z = GuestFloat(base, source + offset + 8);
    const float angle = GuestFloat(base, source + offset + 12);
    if (!std::isfinite(x) || std::abs(x) > 50 ||
        !(std::abs(y - 0.3308105f) < 0.002f || std::abs(y - 0.8305664f) < 0.002f) ||
        !std::isfinite(z) || std::abs(z) > 50 ||
        !(std::abs(std::abs(angle) - 6.28125f) < 0.002f)) continue;
    const unsigned sample = samples.fetch_add(1, std::memory_order_relaxed);
    if (sample >= 24) return;
    char chain[512];
    int used = std::snprintf(chain, sizeof(chain), "%08X", uint32_t(ctx.lr));
    uint32_t sp = ctx.r1.u32;
    for (unsigned i = 0; i < 12 && sp >= 0x10000 && sp < 0xBFFFFFF0; ++i) {
      if (!Readable(base + sp)) break;
      const uint32_t parent = REX_LOAD_U32(sp);
      if (parent <= sp || parent - sp > 0x400000 || parent >= 0xC0000000) break;
      if (!Readable(base + parent - 8)) break;
      used += std::snprintf(chain + used, sizeof(chain) - used, " <-%08X", REX_LOAD_U32(parent - 8));
      if (used > int(sizeof(chain)) - 32) break;
      sp = parent;
    }
    REXLOG_INFO("NHL crowd copy #{} stage={} src={:08X}+{} dst={:08X} bytes={} pos=({},{},{},{}) stack={}",
                sample, stage, source, offset, destination, length, x, y, z, angle, chain);
    if (const char* root = std::getenv("NHL_USER_ROOT")) {
      const auto path = std::filesystem::path(root) /
                        ("crowd-" + std::string(stage) + "-" + std::to_string(sample) + ".bin");
      if (FILE* file = std::fopen(path.c_str(), "wb")) {
        std::fwrite(base + source, 1, length, file);
        std::fclose(file);
      }
    }
    return;
  }
}
}
REX_HOOK_RAW(sub_83675E60) {
  // Snapshot the packed asset records before the retail placement producer.
  // This is bounded to one array and leaves the producer and guest state intact.
  if (REXCVAR_GET(nhl_crowd_diagnostics)) {
    static std::atomic<bool> captured{false};
    if (!captured.load(std::memory_order_relaxed) && Readable(base + ctx.r3.u32 + 132)) {
      const uint32_t source = REX_LOAD_U32(ctx.r3.u32 + 132);
      const uint32_t count = REX_LOAD_U32(ctx.r3.u32 + 136);
      const uint32_t bytes = count * 32;
      if (count && count <= 65535 && source >= 0x10000 &&
          uint64_t(source) + bytes <= 0x100000000ULL &&
          Readable(base + source) && Readable(base + source + bytes - 4) &&
          !captured.exchange(true, std::memory_order_relaxed)) {
        REXLOG_INFO("NHL crowd packed object={:08X} source={:08X} records={} first_count={} lr={:08X}",
                    ctx.r3.u32, source, count, ctx.r6.u32, uint32_t(ctx.lr));
        if (const char* root = std::getenv("NHL_USER_ROOT")) {
          const auto path = std::filesystem::path(root) / "crowd-packed.bin";
          if (FILE* file = std::fopen(path.c_str(), "wb")) {
            std::fwrite(base + source, 1, bytes, file);
            std::fclose(file);
          }
        }
      }
    }
  }
  __imp__sub_83675E60(ctx, base);
}
REX_HOOK_RAW(sub_8289EA40) {
  if (REXCVAR_GET(nhl_crowd_diagnostics))
    SampleCrowdCopy(ctx, base, ctx.r4.u32, ctx.r5.u32, ctx.r3.u32, "memcpy");
  __imp__sub_8289EA40(ctx, base);
}

REX_HOOK_RAW(sub_827F80D0) {
  if (REXCVAR_GET(nhl_crowd_diagnostics) && ctx.r5.u32 == 0x4000)
    SampleCrowdCopy(ctx, base, ctx.r6.u32, 2048, ctx.r3.u32, "vs-cache");
  __imp__sub_827F80D0(ctx, base);
}

REX_HOOK_RAW(sub_827E6480) {
  const bool crowd_caller = (ctx.lr >= 0x8368FDE0 && ctx.lr < 0x83690240) ||
                           (ctx.lr >= 0x83691160 && ctx.lr < 0x83691678);
  if (REXCVAR_GET(nhl_crowd_diagnostics) && crowd_caller && ctx.r4.u32 == 40) {
    static std::atomic<unsigned> calls{0};
    if (calls.fetch_add(1, std::memory_order_relaxed) < 8)
      REXLOG_INFO("NHL crowd VS input lr={:08X} src={:08X} device={:08X} vectors={} descriptor={:08X}",
                  uint32_t(ctx.lr), ctx.r5.u32, ctx.r3.u32, ctx.r6.u32, ctx.r27.u32);
    const uint32_t destination = ctx.r3.u32 + (ctx.r4.u32 + 120) * 16;
    if (ctx.r6.u32 <= 4096)
      SampleCrowdCopy(ctx, base, ctx.r5.u32, std::min(ctx.r6.u32 * 16, uint32_t(2048)),
                      destination, "vs-upload");
  }
  __imp__sub_827E6480(ctx, base);
}
