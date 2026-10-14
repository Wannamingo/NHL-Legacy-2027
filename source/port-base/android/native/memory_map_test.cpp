#include "proc_maps_reader.h"
#include <rex/memory/utils.h>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <sys/mman.h>
#include <sys/syscall.h>

using rex::memory::detail::LinuxMapEntry;
using rex::memory::detail::ProcMapsReader;
using rex::memory::PageAccess;
#define CHECK(condition) do { if (!(condition)) { std::fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #condition); return 1; } } while (0)

static bool OldLookup(void* address, LinuxMapEntry& entry) {
  std::ifstream maps("/proc/self/maps");
  std::string line;
  while (std::getline(maps, line)) {
    unsigned long long start = 0, end = 0;
    char perms[5] = {};
    if (std::sscanf(line.c_str(), "%llx-%llx %4s", &start, &end, perms) != 3) continue;
    if (uintptr_t(address) >= start && uintptr_t(address) < end) {
      entry.start = start;
      entry.end = end;
      std::memcpy(entry.perms, perms, sizeof(perms));
      return true;
    }
  }
  return false;
}

int main() {
  LinuxMapEntry entry;
  const char* valid = "7fffffff0000-800000000000 rw-p 00000000 00:00 0";
  CHECK(ProcMapsReader::ParsePrefix(valid, std::strlen(valid), entry));
  CHECK(entry.start == 0x7fffffff0000ULL && entry.end == 0x800000000000ULL);
  CHECK(std::strcmp(entry.perms, "rw-p") == 0);
  for (const char* invalid : {"", "-10 rw-p", "20-10 rw-p", "10-10 rw-p", "10-20 rw", "10000000000000000-20 rw-p", "10-20 xx-p"})
    CHECK(!ProcMapsReader::ParsePrefix(invalid, std::strlen(invalid), entry));

  // Exercise prefixes split over reads, long pathnames, malformed records,
  // and the final record without a newline using the production reader.
  int fixture = static_cast<int>(syscall(__NR_memfd_create, "nhl-maps-test", 1));
  CHECK(fixture >= 0);
  const std::string records = "10-20 r--p " + std::string(4090, 'a') +
      "\ninvalid line\n20-30 ---s\n30-40 rw-p";
  CHECK(write(fixture, records.data(), records.size()) == static_cast<ssize_t>(records.size()));
  CHECK(lseek(fixture, 0, SEEK_SET) == 0);
  {
    ProcMapsReader reader(fixture);
    CHECK(reader.Next(entry) && entry.start == 0x10 && entry.end == 0x20);
    CHECK(reader.Next(entry) && entry.start == 0x20 && std::strcmp(entry.perms, "---s") == 0);
    CHECK(reader.Next(entry) && entry.start == 0x30 && entry.end == 0x40);
    CHECK(!reader.Next(entry) && !reader.Next(entry));
  }

  const size_t page = rex::memory::page_size();
  auto* region = static_cast<unsigned char*>(mmap(nullptr, page * 3, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
  CHECK(region != MAP_FAILED);
  size_t length = 0;
  PageAccess access;
  CHECK(rex::memory::QueryProtect(region + page, length, access) && access == PageAccess::kNoAccess);
  CHECK(mprotect(region + page, page, PROT_READ) == 0);
  CHECK(rex::memory::QueryProtect(region + page + 7, length, access) && access == PageAccess::kReadOnly);
  CHECK(length == page - 7);
  CHECK(mprotect(region + page, page, PROT_READ | PROT_WRITE) == 0);
  CHECK(rex::memory::QueryProtect(region + page, length, access) && access == PageAccess::kReadWrite);

  // Compare old and new queries against the same unchanged mapping.
  LinuxMapEntry old_entry;
  CHECK(OldLookup(region + page, old_entry));
  CHECK(length == old_entry.end - uintptr_t(region + page));
  constexpr int iterations = 2000;
  const auto begin = std::chrono::steady_clock::now();
  for (int i = 0; i != iterations; ++i) CHECK(OldLookup(region + page, old_entry));
  const auto middle = std::chrono::steady_clock::now();
  for (int i = 0; i != iterations; ++i) CHECK(rex::memory::QueryProtect(region + page, length, access));
  const auto end = std::chrono::steady_clock::now();
  CHECK(access == PageAccess::kReadWrite && length == old_entry.end - uintptr_t(region + page));
  CHECK(munmap(region + page, page) == 0);
  CHECK(!rex::memory::QueryProtect(region + page, length, access));
  CHECK(length == 0 && access == PageAccess::kNoAccess);
  CHECK(munmap(region, page) == 0 && munmap(region + 2 * page, page) == 0);
  std::printf("PASS: parser, permissions, mapping boundaries, unmap; %d queries: old %.3f ms, new %.3f ms\n", iterations,
      std::chrono::duration<double, std::milli>(middle - begin).count(),
      std::chrono::duration<double, std::milli>(end - middle).count());
  return 0;
}
