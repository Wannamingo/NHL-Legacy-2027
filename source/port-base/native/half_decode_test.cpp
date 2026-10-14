#include "generated/default/nhllegacy_init.h"
#include <cstdio>
#include <cstring>
#include <vector>

// Check the actual retail scalar conversion used to unpack crowd coordinates.
// Retail flushes half subnormals; all finite normal values must be exact.
int main() {
  std::vector<uint8_t> memory(0x20000);
  uint8_t* base = memory.data();
  unsigned cases = 0;
  for (uint32_t half = 0; half <= 0xFFFF; ++half) {
    const uint32_t exponent = (half >> 10) & 31;
    if (exponent == 31) continue;
    const uint32_t expected = ((half & 0x8000) << 16) |
        (exponent ? ((exponent + 112) << 23) | ((half & 1023) << 13) : 0);
    PPCContext ctx{};
    ctx.r1.u32 = 0x10000;
    ctx.r3.u32 = half;
    __imp__sub_833E5900(ctx, base);
    const float value = float(ctx.f1.f64);
    uint32_t bits;
    std::memcpy(&bits, &value, 4);
    if (bits != expected) {
      std::fprintf(stderr, "FAIL half=%04X expected=%08X actual=%08X\n",
                   half, expected, bits);
      return 1;
    }
    ++cases;
  }
  std::printf("PASS retail scalar half decode: %u finite encodings, exact normals and retail subnormal flush\n", cases);
  return 0;
}
