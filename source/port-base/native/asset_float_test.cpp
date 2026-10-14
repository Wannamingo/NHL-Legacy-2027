#include "generated/default/nhllegacy_init.h"
#include <array>
#include <cstdio>
#include <cstring>
#include <vector>

int main(int argc, char** argv) {
  const bool original = argc == 2 && std::strcmp(argv[1], "--original") == 0;
  std::vector<uint8_t> memory(0x30000);
  uint8_t* base = memory.data();
  constexpr uint32_t source = 0x10000, cursor = 0x11000,
                     remaining = 0x12000, destination = 0x14000;
  const std::array<float, 12> values = {-32.f, 32.f, -31.703125f, 31.703125f,
      -1.892578125f, .330810546875f, .83056640625f, 6.28125f,
      1.875f, -1.875f, 0.f, -0.f};
  unsigned mismatches = 0;
  for (const float value : values) {
    uint32_t expected;
    std::memcpy(&expected, &value, 4);
    for (unsigned i = 0; i < 4; ++i) base[source + i] = uint8_t(expected >> (i * 8));
    REX_STORE_U32(cursor, source);
    REX_STORE_U32(remaining, 100);
    REX_STORE_U32(destination, 0xA5A5A5A5);
    PPCContext ctx{};
    ctx.fpscr.InitHost();
    const uint32_t saved_fpu = ctx.fpscr.fpu_csr;
    ctx.r1.u32 = 0x20000;
    ctx.r3.u32 = destination;
    ctx.r4.u32 = cursor;
    ctx.r5.u32 = remaining;
    if (original) __imp__sub_8368E758(ctx, base);
    else sub_8368E758(ctx, base);
    const uint32_t actual = REX_LOAD_U32(destination);
    const bool pass = actual == expected && REX_LOAD_U32(cursor) == source + 4 &&
                      REX_LOAD_U32(remaining) == 96 && ctx.fpscr.fpu_csr == saved_fpu &&
                      ctx.fpscr.getcsr() == saved_fpu;
    std::printf("%s value=%g expected=%08X actual=%08X\n",
                pass ? "PASS" : "FAIL", value, expected, actual);
    mismatches += !pass;
  }
  std::printf("%s serialized float reader: %u/%zu mismatches with initialized guest FPSCR\n",
              original ? "Original" : "Fixed", mismatches, values.size());
  return mismatches ? 1 : 0;
}
