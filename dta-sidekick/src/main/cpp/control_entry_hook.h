// Control entry hook (feature 005): lets a handler rewrite a method's parameters or
// return from it early. Shape after Android Studio's app-inspection
// ArrayParamsEntryHook (Apache 2.0), extended with parameter writeback and an early
// return guarded by HookDispatcher.PROCEED.
#pragma once

#include "slicer/code_ir.h"
#include "slicer/dex_ir.h"
#include "slicer/instrumentation.h"

namespace dta {

// Inserted before the first bytecode:
//
//   Object r = HookDispatcher.onEntryControl(new Object[]{label, this|null, params...});
//   if (r != HookDispatcher.PROCEED) return (T) r;      // void: return
//   params = (T_i) array[2 + i];                         // writeback, then the original body
class ControlEntryHook : public slicer::Transformation {
 public:
  explicit ControlEntryHook(const char* dispatcher_descriptor) : dispatcher_(dispatcher_descriptor) {}

  bool Apply(lir::CodeIr* code_ir) override;

 private:
  const char* dispatcher_;
};

}  // namespace dta
