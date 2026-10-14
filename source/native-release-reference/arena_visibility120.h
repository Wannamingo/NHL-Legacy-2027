#pragma once
#include <cstdlib>
#include <cstring>

namespace arena120 {
// An unset override retains the existing hot-reloadable minimal-arena control.
inline int ParseOverride(const char* value) {
  if (value && std::strcmp(value, "true") == 0) return 1;
  if (value && std::strcmp(value, "false") == 0) return 0;
  return -1;
}
constexpr bool Resolve(int override_value, bool minimal) {
  return override_value < 0 ? !minimal : override_value != 0;
}
struct Visibility { bool crowd, stands, bench; };
inline Visibility ForLaunch(bool minimal) {
  static const int crowd = ParseOverride(std::getenv("NHL_CROWD_ENABLED"));
  static const int stands = ParseOverride(std::getenv("NHL_STANDS_ENABLED"));
  static const int bench = ParseOverride(std::getenv("NHL_BENCH_ENABLED"));
  return {Resolve(crowd, minimal), Resolve(stands, minimal), Resolve(bench, minimal)};
}
constexpr bool Omit(unsigned role, bool outside, bool crowd, bool stands, bool bench) {
  return (!crowd && role == 4) || (!stands && role == 1) ||
         (!bench && outside && (role == 2 || role == 3));
}
}  // namespace arena120
