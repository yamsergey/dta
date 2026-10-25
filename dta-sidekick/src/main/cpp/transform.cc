#include "transform.h"

#include <exception>
#include <memory>

#include "slicer/common.h"
#include "slicer/dex_ir.h"
#include "slicer/dex_ir_builder.h"
#include "slicer/instrumentation.h"
#include "slicer/reader.h"
#include "slicer/writer.h"

namespace dta {

namespace {

constexpr const char* kDispatcher = "Lio/yamsergey/dta/shim/HookDispatcher;";

class CallbackAllocator : public dex::Writer::Allocator {
 public:
  CallbackAllocator(const std::function<void*(size_t)>& alloc,
                    const std::function<void(void*)>& release)
      : alloc_(alloc), release_(release) {}
  void* Allocate(size_t size) override { return alloc_(size); }
  void Free(void* ptr) override { release_(ptr); }

 private:
  const std::function<void*(size_t)>& alloc_;
  const std::function<void(void*)>& release_;
};

bool ApplyOne(std::shared_ptr<ir::DexFile> dex_ir, const std::string& descriptor,
              const HookTarget& target) {
  slicer::MethodInstrumenter mi(dex_ir);
  if (target.entry) {
    mi.AddTransformation<slicer::EntryHook>(ir::MethodId(kDispatcher, "onEntry"),
                                            slicer::EntryHook::Tweak::ArrayParams);
  } else {
    bool primitive_or_void = target.signature.back() != ';';
    auto tweak = primitive_or_void ? slicer::ExitHook::Tweak::None
                                   : slicer::ExitHook::Tweak::ReturnAsObject;
    tweak = tweak | slicer::ExitHook::Tweak::PassMethodSignature;
    mi.AddTransformation<slicer::ExitHook>(ir::MethodId(kDispatcher, "onExit"), tweak);
  }
  return mi.InstrumentMethod(
      ir::MethodId(descriptor.c_str(), target.method.c_str(), target.signature.c_str()));
}

}  // namespace

std::string Transform(const std::string& descriptor,
                      const std::vector<HookTarget>& targets,
                      const unsigned char* class_data, size_t class_data_len,
                      const std::function<void*(size_t)>& alloc,
                      const std::function<void(void*)>& release,
                      unsigned char** out, size_t* out_len) {
  try {
    dex::Reader reader(class_data, class_data_len);
    auto class_index = reader.FindClassIndex(descriptor.c_str());
    if (class_index == dex::kNoIndex) return "class not found in its dex image";
    reader.CreateClassIr(class_index);
    auto dex_ir = reader.GetIr();

    for (const auto& target : targets) {
      if (!ApplyOne(dex_ir, descriptor, target)) {
        return "cannot instrument " + target.method + target.signature +
               " (missing or abstract)";
      }
    }

    CallbackAllocator allocator(alloc, release);
    dex::Writer writer(dex_ir);
    size_t new_size = 0;
    dex::u1* image = writer.CreateImage(&allocator, &new_size);
    if (image == nullptr) return "dex writer produced no image";
    *out = image;
    *out_len = new_size;
    return "";
  } catch (const slicer::CheckFailure& e) {
    return std::string("slicer: ") + e.what();
  } catch (const std::exception& e) {
    return std::string("transform: ") + e.what();
  } catch (...) {
    return "transform: unknown failure";
  }
}

}  // namespace dta
