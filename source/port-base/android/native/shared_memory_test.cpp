#include <rex/cvar.h>
#include <rex/graphics/shared_memory.h>
#include <rex/logging.h>
#include <cstdio>

#define CHECK(value) do { if (!(value)) { std::fprintf(stderr, "FAIL %d: %s\n", __LINE__, #value); return 1; } } while (0)

class UploadProbe final : public rex::graphics::SharedMemory {
 public:
  explicit UploadProbe(rex::memory::Memory& memory) : SharedMemory(memory) { InitializeCommon(); }
  unsigned uploads = 0;
 private:
  bool UploadRanges(const std::vector<std::pair<uint32_t, uint32_t>>& ranges) override {
    for (const auto& [first, count] : ranges) {
      MakeRangeValid(first << page_size_log2(), count << page_size_log2(), false);
      ++uploads;
    }
    return true;
  }
};

int main(int argc, char** argv) {
  rex::cvar::Init(argc, argv);
  rex::InitLogging("shared-memory-test.log");
  rex::memory::Memory memory;
  CHECK(memory.Initialize());
  UploadProbe gpu(memory);
  const uint32_t page = uint32_t(rex::memory::page_size());
  const uint32_t cpu_address = 0x400000, gpu_address = 0x800000;
  CHECK(gpu.RequestRange(cpu_address, page));
  CHECK(gpu.uploads == 1);
  gpu.RangeWrittenByGpu(gpu_address, page);
  gpu.SetSystemPageBlocksValidWithGpuDataWritten();
  CHECK(gpu.RequestRange(gpu_address, page));
  CHECK(gpu.uploads == 1); // GPU data must never be replaced from guest RAM.
  CHECK(gpu.RequestRange(cpu_address, page));
  CHECK(gpu.uploads == 2);
  for (unsigned i = 0; i < 100; ++i) {
    gpu.SetSystemPageBlocksValidWithGpuDataWritten();
    CHECK(gpu.RequestRange(cpu_address, page));
    CHECK(gpu.uploads == 3 + i); // Refresh CPU data even without a new GPU write.
    CHECK(gpu.RequestRange(gpu_address, page));
    CHECK(gpu.uploads == 3 + i);
  }
  gpu.MemoryInvalidationCallback(gpu_address, page, true);
  gpu.SetSystemPageBlocksValidWithGpuDataWritten();
  CHECK(gpu.RequestRange(gpu_address, page));
  CHECK(gpu.uploads == 103); // A CPU overwrite must invalidate previous GPU data.
  std::puts("PASS CPU refresh over 100 frames, GPU preservation, CPU overwrite");
  return 0;
}
