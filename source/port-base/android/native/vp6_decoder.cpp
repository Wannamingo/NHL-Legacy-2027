#include "vp6_decoder.h"
#include <cstdlib>
#include <cstring>
extern "C" {
#include <libavcodec/avcodec.h>
extern AVCodec ff_vp6_decoder;
}

namespace nhl {
struct Vp6Decoder::State {
  AVCodecContext* context = nullptr;
  AVFrame* frame = nullptr;
  AVPacket* packet = nullptr;
  ~State() {
    av_packet_free(&packet);
    av_frame_free(&frame);
    avcodec_free_context(&context);
  }
};
Vp6Decoder::Vp6Decoder() : state_(std::make_unique<State>()) {
  auto& s = *state_;
  s.context = avcodec_alloc_context3(&ff_vp6_decoder);
  s.frame = av_frame_alloc();
  s.packet = av_packet_alloc();
  if (!s.context || !s.frame || !s.packet) return;
  s.context->thread_count = 1;
  if (avcodec_open2(s.context, &ff_vp6_decoder, nullptr) < 0)
    avcodec_free_context(&s.context);
}
Vp6Decoder::~Vp6Decoder() = default;
bool Vp6Decoder::ready() const {
  return state_->context && state_->frame && state_->packet;
}
bool Vp6Decoder::Decode(std::span<const uint8_t> data, Vp6Frame& output) {
  if (!ready() || data.empty() || data.size() > 16 * 1024 * 1024) return false;
  auto& s = *state_;
  av_packet_unref(s.packet);
  // FFmpeg bit readers require zero padding after the compressed payload.
  if (av_new_packet(s.packet, static_cast<int>(data.size())) < 0) return false;
  std::memcpy(s.packet->data, data.data(), data.size());
  if (avcodec_send_packet(s.context, s.packet) < 0) return false;
  av_frame_unref(s.frame);
  if (avcodec_receive_frame(s.context, s.frame) < 0) return false;
  const int w = s.frame->width, h = s.frame->height;
  if (s.frame->format != AV_PIX_FMT_YUV420P || w <= 0 || h <= 0 ||
      w > 4096 || h > 4096 || (w & 1) || (h & 1)) return false;
  output.width = w; output.height = h;
  output.pixels.resize(size_t(w) * h * 3 / 2);
  size_t offset = 0;
  for (int plane = 0; plane < 3; ++plane) {
    const int pw = plane ? w / 2 : w, ph = plane ? h / 2 : h;
    if (!s.frame->data[plane] || std::abs(s.frame->linesize[plane]) < pw) return false;
    for (int row = 0; row < ph; ++row) {
      std::memcpy(output.pixels.data() + offset,
          s.frame->data[plane] + ptrdiff_t(row) * s.frame->linesize[plane], pw);
      offset += pw;
    }
  }
  return true;
}
}
