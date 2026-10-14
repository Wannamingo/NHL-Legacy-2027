#pragma once
#include <cstdint>
#include <memory>
#include <span>
#include <vector>

namespace nhl {
struct Vp6Frame {
  int width = 0, height = 0;
  // Tight Y, U, V planes in top-to-bottom presentation order.
  std::vector<uint8_t> pixels;
};
class Vp6Decoder {
 public:
  Vp6Decoder();
  ~Vp6Decoder();
  Vp6Decoder(const Vp6Decoder&) = delete;
  Vp6Decoder& operator=(const Vp6Decoder&) = delete;
  bool ready() const;
  bool Decode(std::span<const uint8_t> packet, Vp6Frame& output);
 private:
  struct State;
  std::unique_ptr<State> state_;
};
}
