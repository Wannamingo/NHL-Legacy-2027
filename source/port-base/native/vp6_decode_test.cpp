#include "vp6_decoder.h"
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <numeric>
#include <string>

int main(int argc, char** argv) {
  if (argc != 4) return 2;
  const int target = std::stoi(argv[3]);
  if (target < 0 || target > 10000) return 2;
  std::ifstream input(argv[1], std::ios::binary);
  nhl::Vp6Decoder decoder;
  if (!input || !decoder.ready()) return 3;
  std::vector<double> samples;
  int frame = 0;
  while (input) {
    uint8_t header[8]; input.read(reinterpret_cast<char*>(header), 8);
    if (!input) break;
    const uint32_t length = uint32_t(header[4]) | uint32_t(header[5]) << 8 |
        uint32_t(header[6]) << 16 | uint32_t(header[7]) << 24;
    if (length < 8 || length > 16 * 1024 * 1024) return 4;
    if (std::memcmp(header, "MV0K", 4) && std::memcmp(header, "MV0F", 4)) {
      input.seekg(length - 8, std::ios::cur); continue;
    }
    std::vector<uint8_t> packet(length - 8);
    input.read(reinterpret_cast<char*>(packet.data()), packet.size());
    if (!input) return 5;
    nhl::Vp6Frame output;
    const auto start = std::chrono::steady_clock::now();
    if (!decoder.Decode(packet, output)) return 6;
    samples.push_back(std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - start).count());
    if (frame++ != target) continue;
    std::ofstream file(argv[2], std::ios::binary);
    file.write(reinterpret_cast<const char*>(output.pixels.data()), output.pixels.size());
    if (!file) return 7;
    std::printf("PASS VP6 frame=%d %dx%d bytes=%zu samples=%zu mean_ms=%.3f max_ms=%.3f\n",
        target, output.width, output.height, output.pixels.size(), samples.size(),
        std::accumulate(samples.begin(), samples.end(), 0.0) / samples.size(),
        *std::max_element(samples.begin(), samples.end()));
    return 0;
  }
  return 8;
}
