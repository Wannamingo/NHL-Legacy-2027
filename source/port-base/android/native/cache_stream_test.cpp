#include <rex/filesystem.h>
#include <array>
#include <cstdio>
#include <cstring>

int main() {
  const std::array<uint32_t, 8> contents = {0x53504558, 0, 7, 0x12345678,
                                           0x87654321, 0x00004000, 0, 0xFFFFFFFF};
  const char* path = "cache-stream-fixture.bin";
  FILE* file = rex::filesystem::OpenFile(path, "wb");
  if (!file || std::fwrite(contents.data(), sizeof(contents), 1, file) != 1) return 1;
  if (std::fclose(file)) return 1;
  file = rex::filesystem::OpenFile(path, "a+b");
  if (!file) return 1;
  const int64_t initial_position = rex::filesystem::Tell(file);
  uint32_t header = 0;
  const bool initial_read_ok = std::fread(&header, sizeof(header), 1, file) == 1 &&
                               header == contents[0];
  std::printf("Android a+b position=%lld, header without seek=%s\n",
              static_cast<long long>(initial_position), initial_read_ok ? "read" : "missing");
  // Match the proposed pipeline/shader reader correction. Seeking also clears
  // EOF, and must retain the complete previously stored file.
  if (!rex::filesystem::Seek(file, 0, SEEK_SET)) return 1;
  std::array<uint32_t, 8> restored{};
  if (std::fread(restored.data(), sizeof(restored), 1, file) != 1 || restored != contents)
    return 1;
  const uint32_t appended = 0xDEADBEEF;
  if (!rex::filesystem::Seek(file, 0, SEEK_END) ||
      std::fwrite(&appended, sizeof(appended), 1, file) != 1 || std::fflush(file) ||
      !rex::filesystem::Seek(file, 0, SEEK_SET) ||
      std::fread(restored.data(), sizeof(restored), 1, file) != 1 || restored != contents)
    return 1;
  header = 0;
  if (std::fread(&header, sizeof(header), 1, file) != 1 || header != appended || std::fclose(file))
    return 1;
  std::puts("PASS explicit seek restores header/data, append preserves prior cache bytes");
  return 0;
}
