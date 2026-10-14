#include "generated/default/nhllegacy_init.h"
#include <array>
#include <cstdio>
#include <cstring>
#include <vector>

// Exercise the actual recompiled retail upload routine, including ARM SIMD
// left/right loads. A byte comparison catches lost float components directly.
int main() {
  std::vector<uint8_t> memory(0x50000);
  uint8_t* base = memory.data();
  constexpr uint32_t device = 0x20000, first_register = 40;
  constexpr uint32_t destination = device + (first_register + 120) * 16;
  constexpr uint64_t dirty_before = 0x8000000000000001ULL;
  constexpr uint64_t dirty_upload = 0x0004000000000000ULL;
  const std::array<float, 8> z_values = {-32.0f, 32.0f, -31.703125f, 31.703125f,
                                       0.0f, -0.0f, -32.71875f, 20.421875f};
  unsigned cases = 0;
  for (unsigned alignment = 0; alignment < 16; ++alignment) {
    for (unsigned vectors = 0; vectors <= 64; ++vectors) {
      const uint32_t source = 0x10000 + alignment, bytes = vectors * 16;
      std::memset(base + device, 0xA5, 0x4000);
      for (unsigned i = 0; i < bytes + 32; ++i)
        base[source + i] = uint8_t((i * 73 + 19) & 255);
      for (unsigned i = 0; i + 16 <= bytes; i += 16) {
        const float values[] = {-0.63671875f, 0.33081055f,
                                z_values[(i / 16) % z_values.size()], 6.28125f};
        for (unsigned lane = 0; lane < 4; ++lane) {
          uint32_t bits;
          std::memcpy(&bits, &values[lane], 4);
          REX_STORE_U32(source + i + lane * 4, bits);
        }
      }
      REX_STORE_U64(device, dirty_before);
      PPCContext ctx{};
      ctx.r3.u32 = device;
      ctx.r4.u32 = first_register;
      ctx.r5.u32 = source;
      ctx.r6.u32 = vectors;
      ctx.r7.u64 = dirty_upload;
      __imp__sub_827E6480(ctx, base);
      if (std::memcmp(base + destination, base + source, bytes) != 0 ||
          base[destination - 1] != 0xA5 || base[destination + bytes] != 0xA5 ||
          REX_LOAD_U64(device) != (dirty_before | dirty_upload)) {
        std::fprintf(stderr, "FAIL alignment=%u vectors=%u\n", alignment, vectors);
        return 1;
      }
      ++cases;
    }
  }
  std::printf("PASS retail VS upload: %u cases, all 16 alignments, 0..64 vectors, float Z and canaries\n", cases);
  return 0;
}
