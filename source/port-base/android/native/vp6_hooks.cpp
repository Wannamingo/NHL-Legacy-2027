#include "generated/default/nhllegacy_init.h"
#include "vp6_decoder.h"
#include <rex/cvar.h>
#include <rex/hook.h>
#include <rex/logging.h>
#include <cstring>
#include <map>
#include <mutex>

REXCVAR_DEFINE_BOOL(nhl_native_vp6, true, "Android/Video",
                   "Decode movie packets with the native VP6 decoder");

namespace {
struct Stream {
  nhl::Vp6Decoder decoder;
  std::map<uint32_t, nhl::Vp6Frame> frames;
  uint32_t last_index = 0;
  unsigned decoded = 0, applied = 0, failures = 0;
  bool ended = false;
};
std::mutex streams_mutex;
std::map<uint32_t, std::unique_ptr<Stream>> streams;

bool GuestRange(uint32_t address, size_t length) {
  return address >= 0x10000 && length && uint64_t(address) + length <= 0x100000000ull;
}

void DecodePacket(uint8_t* base, uint32_t player, uint32_t index, uint32_t record) {
  if (!GuestRange(record, 12) || !player) return;
  const uint32_t size = REX_LOAD_U32(record + 4);
  const uint32_t address = REX_LOAD_U32(record + 8);
  if (size > 16 * 1024 * 1024 || !GuestRange(address, size)) return;
  auto data = std::span<const uint8_t>(REX_RAW_ADDR(address), size);
  // The queue normally holds an elementary frame; accept an EA chunk header
  // only when its tag and inclusive little-endian size both match the record.
  if (size >= 8 && (!std::memcmp(data.data(), "MV0K", 4) ||
                    !std::memcmp(data.data(), "MV0F", 4))) {
    uint32_t chunk_size;
    std::memcpy(&chunk_size, data.data() + 4, 4);
    if (chunk_size == size) data = data.subspan(8);
  }
  std::lock_guard lock(streams_mutex);
  if (!streams.contains(player) && streams.size() >= 8) {
    for (auto it = streams.begin(); it != streams.end();) {
      if (it->second->ended) it = streams.erase(it); else ++it;
    }
    if (streams.size() >= 8) return;
  }
  auto& stream = streams[player];
  if (!stream || (stream->decoded && index <= stream->last_index))
    stream = std::make_unique<Stream>();
  auto& s = *stream;
  nhl::Vp6Frame frame;
  const bool decoded = s.decoder.Decode(data, frame);
  if (s.decoded + s.failures < 3)
    REXLOG_INFO("NHL native VP6 packet player={:08X} index={} bytes={} prefix={:02X} {:02X} result={} {}x{}",
                player, index, size, data.empty() ? 0 : data[0], data.size() < 2 ? 0 : data[1],
                decoded, frame.width, frame.height);
  s.last_index = index;
  if (!decoded) { ++s.failures; return; }
  ++s.decoded;
  // Bounded pending frames: never guess a frame if asynchronous decoding
  // publishes late, or if a different output format bypasses the planar hook.
  if (frame.width <= 1920 && frame.height <= 1080) {
    s.frames[index] = std::move(frame);
    while (s.frames.size() > 8) s.frames.erase(s.frames.begin());
  }
}

void PublishFrame(uint8_t* base, uint32_t player, uint32_t index,
                  uint32_t slot, uint32_t width, uint32_t height) {
  std::lock_guard lock(streams_mutex);
  auto stream = streams.find(player);
  if (stream == streams.end()) return;
  auto& s = *stream->second;
  auto pending = s.frames.find(index);
  if (pending == s.frames.end()) return;
  auto frame = std::move(pending->second);
  s.frames.erase(pending);
  if (frame.width != width || frame.height != height || !GuestRange(slot, 100)) return;
  const uint32_t format = REX_LOAD_U32(slot + 80);
  if (format != 3 && format != 4) return;
  const bool contiguous = REX_LOAD_U32(slot + 88) == 1;
  const uint32_t y = REX_LOAD_U32(slot + 16);
  const uint32_t addresses[3] = {y, contiguous ? y + width * height : REX_LOAD_U32(slot + 20),
      contiguous ? y + width * height * 5 / 4 : REX_LOAD_U32(slot + 24)};
  const uint32_t y_pitch = REX_LOAD_U32(slot + 48);
  const uint32_t pitches[3] = {y_pitch, contiguous ? y_pitch / 2 : REX_LOAD_U32(slot + 52),
      contiguous ? y_pitch / 2 : REX_LOAD_U32(slot + 56)};
  for (unsigned plane = 0; plane < 3; ++plane) {
    const uint32_t w = plane ? width / 2 : width, h = plane ? height / 2 : height;
    if (pitches[plane] < w || pitches[plane] > 16384 ||
        !GuestRange(addresses[plane], uint64_t(pitches[plane]) * (h - 1) + w)) return;
  }
  size_t offset = 0;
  for (unsigned plane = 0; plane < 3; ++plane) {
    const uint32_t w = plane ? width / 2 : width, h = plane ? height / 2 : height;
    auto* output = REX_RAW_ADDR(addresses[plane]);
    for (uint32_t row = 0; row < h; ++row) {
      // Retail texture presentation consumes these planes bottom-to-top.
      std::memcpy(output + size_t(h - 1 - row) * pitches[plane], frame.pixels.data() + offset, w);
      offset += w;
    }
  }
  if (++s.applied <= 3 || !(s.applied % 120))
    REXLOG_INFO("NHL native VP6 published player={:08X} index={} {}x{} format={} contiguous={} count={}",
                player, index, width, height, format, contiguous, s.applied);
  if (s.ended && s.frames.empty()) {
    REXLOG_INFO("NHL native VP6 drained player={:08X} decoded={} published={} failures={}",
                player, s.decoded, s.applied, s.failures);
    streams.erase(stream);
  }
}
}

REX_HOOK_RAW(sub_8277ABB8) {
  const uint32_t player = ctx.r3.u32;
  if (REXCVAR_GET(nhl_native_vp6)) {
    std::lock_guard lock(streams_mutex);
    streams.erase(player);
  }
  __imp__sub_8277ABB8(ctx, base);
  std::lock_guard lock(streams_mutex);
  auto stream = streams.find(player);
  if (stream != streams.end()) {
    auto& s = *stream->second;
    REXLOG_INFO("NHL native VP6 ended player={:08X} decoded={} published={} failures={}",
                player, s.decoded, s.applied, s.failures);
    // The last async completions can arrive after the packet worker returns.
    // Keep their matching frames until publication; a new worker resets its
    // stream, and the global stream count is bounded as well.
    s.ended = true;
    if (s.frames.empty()) streams.erase(stream);
  }
}

REX_HOOK_RAW(sub_8276AC70) {
  const uint32_t caller = ctx.lr;
  __imp__sub_8276AC70(ctx, base);
  // Only the video queue pop in the movie worker, never its audio queue.
  if (caller == 0x8277AE48 && ctx.r3.u32 && REXCVAR_GET(nhl_native_vp6))
    DecodePacket(base, ctx.r31.u32, ctx.r24.u32, ctx.r3.u32);
}

REX_HOOK_RAW(sub_8277CC98) {
  const uint32_t caller = ctx.lr;
  const uint32_t player = caller == 0x8278AF8C ? ctx.r24.u32 :
                          caller == 0x8278B1E0 ? ctx.r23.u32 : 0;
  const uint32_t index = caller == 0x8278AF8C ? ctx.r28.u32 : ctx.r30.u32;
  const uint32_t slot = ctx.r9.u32, width = ctx.r6.u32, height = ctx.r7.u32;
  // Retain retail decoder completion, ring metadata and callback ownership.
  // Replace only the matching pixels, before the caller marks this slot ready.
  __imp__sub_8277CC98(ctx, base);
  if (player && REXCVAR_GET(nhl_native_vp6)) PublishFrame(base, player, index, slot, width, height);
}
