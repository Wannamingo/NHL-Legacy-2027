#include <rex/cvar.h>
#include <rex/logging.h>
#include <rex/system/xmemory.h>
#include <atomic>
#include <barrier>
#include <chrono>
#include <cstdio>
#include <thread>
#include <vector>
#include <sys/wait.h>
#include <unistd.h>

REXCVAR_DECLARE(bool, memory_write_watch_fast_path);
#define CHECK(v) do { if (!(v)) { std::fprintf(stderr, "FAIL %d: %s\n", __LINE__, #v); return 1; } } while (0)

int main(int argc, char** argv) {
  rex::cvar::Init(argc, argv);
  rex::InitLogging("write-watch-test.log");
  rex::memory::Memory memory;
  CHECK(memory.Initialize());
  const uint32_t page = uint32_t(rex::memory::page_size());
  const uint32_t address = memory.SystemHeapAlloc(
      page * 2, page, rex::memory::kSystemHeapPhysical);
  CHECK(address);
  const uint32_t physical = memory.GetPhysicalAddress(address);
  CHECK(physical != UINT32_MAX);
  auto* bytes = memory.TranslateVirtual<volatile uint8_t*>(address);
  std::atomic<unsigned> notifications{0};
  auto* watch = memory.RegisterPhysicalMemoryInvalidationCallback(
      [](void* opaque, uint32_t first, uint32_t length, bool) {
        static_cast<std::atomic<unsigned>*>(opaque)->fetch_add(1);
        return std::pair<uint32_t, uint32_t>{first, length};
      }, &notifications);
  for (bool fast : {false, true, false, true}) {
    REXCVAR_SET(memory_write_watch_fast_path, fast);
    const unsigned start_count = notifications.load();
    const auto begin = std::chrono::steady_clock::now();
    for (unsigned round = 0; round < 128; ++round) {
      memory.EnablePhysicalMemoryAccessCallbacks(physical, page, true, false);
      bytes[0] = uint8_t(round);
      CHECK(bytes[0] == uint8_t(round));
      CHECK(notifications.load() == start_count + round + 1);
      // Unwatched writes must not emit another invalidation.
      bytes[1] = uint8_t(round + 1);
      CHECK(notifications.load() == start_count + round + 1);
    }
    std::printf("watch fast=%d mean_us=%.3f\n", int(fast),
        std::chrono::duration<double, std::micro>(
            std::chrono::steady_clock::now() - begin).count() / 128);
    // Competing faults on one page: a stale fault takes the original fallback.
    for (unsigned round = 0; round < 16; ++round) {
      memory.EnablePhysicalMemoryAccessCallbacks(physical, page, true, false);
      const unsigned before = notifications.load();
      std::barrier start(8);
      std::vector<std::thread> workers;
      for (unsigned index = 0; index < 8; ++index) {
        workers.emplace_back([&, index] {
          start.arrive_and_wait();
          bytes[16 + index] = uint8_t(index + round);
        });
      }
      for (auto& worker : workers) worker.join();
      CHECK(notifications.load() == before + 1);
      for (unsigned index = 0; index < 8; ++index)
        CHECK(bytes[16 + index] == uint8_t(index + round));
    }
  }
  const uint32_t guard_address = memory.SystemHeapAlloc(page, page);
  CHECK(guard_address);
  auto* heap = memory.LookupHeap(guard_address);
  CHECK(heap);
  // Guest-requested read-only protection must still reject writes.
  CHECK(heap->Protect(guard_address, page, rex::memory::kMemoryProtectRead));
  for (bool fast : {false, true}) {
    REXCVAR_SET(memory_write_watch_fast_path, fast);
    const pid_t child = fork();
    CHECK(child >= 0);
    if (!child) {
      *memory.TranslateVirtual<volatile uint8_t*>(guard_address) = 77;
      _exit(0);
    }
    int status;
    CHECK(waitpid(child, &status, 0) == child);
    std::printf("read-only fast=%d status=%d\n", int(fast), status);
    CHECK(WIFSIGNALED(status));
  }
  CHECK(heap->Protect(guard_address, page,
      rex::memory::kMemoryProtectRead | rex::memory::kMemoryProtectWrite));
  memory.UnregisterPhysicalMemoryInvalidationCallback(watch);
  memory.SystemHeapFree(address);
  memory.SystemHeapFree(guard_address);
  std::puts("PASS watched/unwatched writes, racing writes, read-only fault");
  return 0;
}
