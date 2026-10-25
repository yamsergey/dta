// Dex transformation on vendored slicer: entry and exit hooks that call the
// frozen HookDispatcher ABI in the bootstrap shim. Mirrors Android Studio's
// app-inspection transform (AppInspectionTransform + slicer hooks).
#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace dta {

struct HookTarget {
  std::string method;     // e.g. "openConnection"
  std::string signature;  // e.g. "()Ljava/net/URLConnection;"
  bool entry;             // true: entry hook, false: exit hook
};

// Rewrites the single-class dex image `class_data` for `descriptor`
// ("Ljava/net/URL;") applying every target. On success stores the new image,
// allocated with `alloc` (released with `release` if the writer discards it), in *out / *out_len and returns an empty string. On
// any failure (method missing, abstract, slicer check) returns the reason and
// leaves *out untouched; the caller keeps the original bytes.
std::string Transform(const std::string& descriptor,
                      const std::vector<HookTarget>& targets,
                      const unsigned char* class_data, size_t class_data_len,
                      const std::function<void*(size_t)>& alloc,
                      const std::function<void(void*)>& release,
                      unsigned char** out, size_t* out_len);

}  // namespace dta
