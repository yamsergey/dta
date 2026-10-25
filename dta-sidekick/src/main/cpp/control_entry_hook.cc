#include "control_entry_hook.h"

#include <string>
#include <vector>

#include "slicer/dex_ir_builder.h"

namespace dta {

namespace {

// v0: array size, then array index · v1: the Object[] · v2: a value · v3: the hook's result.
// v2/v3 also hold a wide primitive when unboxing for an early return.
constexpr dex::u4 kIndex = 0;
constexpr dex::u4 kArray = 1;
constexpr dex::u4 kValue = 2;
constexpr dex::u4 kResult = 3;
constexpr dex::u4 kScratch = 4;

struct BytecodeFinder : public lir::Visitor {
  lir::Bytecode* out = nullptr;
  bool Visit(lir::Bytecode* bytecode) override {
    out = bytecode;
    return true;
  }
};

struct Box {
  const char* type;
  const char* unbox;
};

Box BoxOf(char descriptor) {
  switch (descriptor) {
    case 'Z': return {"Ljava/lang/Boolean;", "booleanValue"};
    case 'B': return {"Ljava/lang/Byte;", "byteValue"};
    case 'C': return {"Ljava/lang/Character;", "charValue"};
    case 'S': return {"Ljava/lang/Short;", "shortValue"};
    case 'I': return {"Ljava/lang/Integer;", "intValue"};
    case 'J': return {"Ljava/lang/Long;", "longValue"};
    case 'F': return {"Ljava/lang/Float;", "floatValue"};
    case 'D': return {"Ljava/lang/Double;", "doubleValue"};
  }
  return {nullptr, nullptr};
}

/** Appends instructions before a fixed position. */
class Emitter {
 public:
  Emitter(lir::CodeIr* code_ir, lir::Instruction* position)
      : ir_(code_ir), pos_(position), b_(code_ir->dex_ir) {}

  lir::Bytecode* Op(dex::Opcode opcode) {
    auto op = ir_->Alloc<lir::Bytecode>();
    op->opcode = opcode;
    ir_->instructions.InsertBefore(pos_, op);
    return op;
  }

  void Place(lir::Label* label) { ir_->instructions.InsertBefore(pos_, label); }

  lir::VReg* R(dex::u4 reg) { return ir_->Alloc<lir::VReg>(reg); }
  lir::VRegPair* Pair(dex::u4 reg) { return ir_->Alloc<lir::VRegPair>(reg); }
  lir::VRegRange* Range(dex::u4 reg, int count) { return ir_->Alloc<lir::VRegRange>(reg, count); }

  lir::Type* T(ir::Type* type) { return ir_->Alloc<lir::Type>(type, type->orig_index); }
  lir::Type* T(const char* descriptor) { return T(b_.GetType(descriptor)); }

  lir::Method* M(const char* parent, const char* name, ir::Type* ret, std::vector<ir::Type*> params) {
    auto proto = b_.GetProto(ret, b_.GetTypeList(params));
    auto decl = b_.GetMethodDecl(b_.GetAsciiString(name), proto, b_.GetType(parent));
    return ir_->Alloc<lir::Method>(decl, decl->orig_index);
  }

  lir::Field* F(const char* parent, const char* name, const char* type) {
    auto decl = b_.GetFieldDecl(b_.GetAsciiString(name), b_.GetType(type), b_.GetType(parent));
    return ir_->Alloc<lir::Field>(decl, decl->orig_index);
  }

  ir::Type* Ty(const char* descriptor) { return b_.GetType(descriptor); }

  void Const(dex::u4 reg, dex::u4 value) {
    auto op = Op(dex::OP_CONST);
    op->operands.push_back(R(reg));
    op->operands.push_back(ir_->Alloc<lir::Const32>(value));
  }

  void Move(dex::Opcode opcode, dex::u4 dst, dex::u4 src, bool wide) {
    auto op = Op(opcode);
    if (wide) {
      op->operands.push_back(Pair(dst));
      op->operands.push_back(Pair(src));
    } else {
      op->operands.push_back(R(dst));
      op->operands.push_back(R(src));
    }
  }

  void CheckCast(dex::u4 reg, lir::Type* type) {
    auto op = Op(dex::OP_CHECK_CAST);
    op->operands.push_back(R(reg));
    op->operands.push_back(type);
  }

  void Invoke(dex::Opcode opcode, lir::VRegRange* args, lir::Method* method) {
    auto op = Op(opcode);
    op->operands.push_back(args);
    op->operands.push_back(method);
  }

  void MoveResult(dex::Opcode opcode, dex::u4 reg, bool wide) {
    auto op = Op(opcode);
    if (wide) op->operands.push_back(Pair(reg));
    else op->operands.push_back(R(reg));
  }

  /** v[dst] = boxed value of the primitive in v[src] (and v[src+1] if wide). */
  void BoxInto(char descriptor, dex::u4 src, dex::u4 dst) {
    Box box = BoxOf(descriptor);
    std::string prim(1, descriptor);
    bool wide = descriptor == 'J' || descriptor == 'D';
    Invoke(dex::OP_INVOKE_STATIC_RANGE, Range(src, wide ? 2 : 1), M(box.type, "valueOf", Ty(box.type), {Ty(prim.c_str())}));
    MoveResult(dex::OP_MOVE_RESULT_OBJECT, dst, false);
  }

  /** v[dst] (pair if wide) = unboxed primitive of the boxed object in v[src]. */
  void UnboxInto(char descriptor, dex::u4 src, dex::u4 dst) {
    Box box = BoxOf(descriptor);
    std::string prim(1, descriptor);
    bool wide = descriptor == 'J' || descriptor == 'D';
    CheckCast(src, T(box.type));
    Invoke(dex::OP_INVOKE_VIRTUAL_RANGE, Range(src, 1), M(box.type, box.unbox, Ty(prim.c_str()), {}));
    MoveResult(wide ? dex::OP_MOVE_RESULT_WIDE : dex::OP_MOVE_RESULT, dst, wide);
  }

  lir::CodeIr* ir() { return ir_; }

 private:
  lir::CodeIr* ir_;
  lir::Instruction* pos_;
  ir::Builder b_;
};

std::string MethodLabel(ir::EncodedMethod* method) {
  return method->decl->parent->Decl() + "->" + method->decl->name->c_str() + method->decl->prototype->Signature();
}

}  // namespace

bool ControlEntryHook::Apply(lir::CodeIr* code_ir) {
  lir::Bytecode* first = nullptr;
  for (auto instr : code_ir->instructions) {
    BytecodeFinder finder;
    instr->Accept(&finder);
    if ((first = finder.out) != nullptr) break;
  }
  if (first == nullptr) return false;

  auto method = code_ir->ir_method;
  auto code = method->code;
  bool is_static = (method->access_flags & dex::kAccStatic) != 0;
  std::vector<ir::Type*> params;
  if (method->decl->prototype->param_types != nullptr) params = method->decl->prototype->param_types->types;
  auto return_type = method->decl->prototype->return_type;
  char return_kind = *return_type->descriptor->c_str();

  // Scratch registers below the parameters; grow the frame when there are too few
  // locals, and move the parameters back down at the end (as ArrayParamsEntryHook).
  const dex::u4 non_param = code->registers - code->ins_count;
  const dex::u4 shift = non_param < kScratch ? kScratch - non_param : 0;
  code->registers += shift;
  const dex::u4 params_start = code->registers - code->ins_count;

  Emitter e(code_ir, first);

  // new Object[2 + n]
  e.Const(kIndex, 2 + static_cast<dex::u4>(params.size()));
  {
    auto op = e.Op(dex::OP_NEW_ARRAY);
    op->operands.push_back(e.R(kArray));
    op->operands.push_back(e.R(kIndex));
    op->operands.push_back(e.T("[Ljava/lang/Object;"));
  }
  auto aput = [&](dex::u4 value_reg, dex::u4 index) {
    e.Const(kIndex, index);
    auto op = e.Op(dex::OP_APUT_OBJECT);
    op->operands.push_back(e.R(value_reg));
    op->operands.push_back(e.R(kArray));
    op->operands.push_back(e.R(kIndex));
  };

  // [0] = label
  {
    auto op = e.Op(dex::OP_CONST_STRING);
    op->operands.push_back(e.R(kValue));
    auto label = ir::Builder(code_ir->dex_ir).GetAsciiString(MethodLabel(method).c_str());
    op->operands.push_back(code_ir->Alloc<lir::String>(label, label->orig_index));
    aput(kValue, 0);
  }
  // [1] = this (static: left null)
  dex::u4 reg = params_start;
  if (!is_static) {
    e.Move(dex::OP_MOVE_OBJECT_16, kValue, reg, false);
    aput(kValue, 1);
    reg += 1;
  }
  // [2 + i] = params, boxed
  std::vector<dex::u4> param_regs;
  for (size_t i = 0; i < params.size(); ++i) {
    auto type = params[i];
    param_regs.push_back(reg);
    switch (type->GetCategory()) {
      case ir::Type::Category::Reference:
        e.Move(dex::OP_MOVE_OBJECT_16, kValue, reg, false);
        reg += 1;
        break;
      case ir::Type::Category::Scalar:
        e.BoxInto(*type->descriptor->c_str(), reg, kValue);
        reg += 1;
        break;
      case ir::Type::Category::WideScalar:
        e.BoxInto(*type->descriptor->c_str(), reg, kValue);
        reg += 2;
        break;
      case ir::Type::Category::Void:
        return false;
    }
    aput(kValue, 2 + static_cast<dex::u4>(i));
  }

  // r = onEntryControl(array)
  e.Invoke(dex::OP_INVOKE_STATIC_RANGE, e.Range(kArray, 1),
           e.M(dispatcher_, "onEntryControl", e.Ty("Ljava/lang/Object;"), {e.Ty("[Ljava/lang/Object;")}));
  e.MoveResult(dex::OP_MOVE_RESULT_OBJECT, kResult, false);
  {
    auto op = e.Op(dex::OP_SGET_OBJECT);
    op->operands.push_back(e.R(kValue));
    op->operands.push_back(e.F(dispatcher_, "PROCEED", "Ljava/lang/Object;"));
  }
  auto proceed = code_ir->Alloc<lir::Label>(0);
  proceed->refCount++;
  {
    auto op = e.Op(dex::OP_IF_EQ);
    op->operands.push_back(e.R(kResult));
    op->operands.push_back(e.R(kValue));
    op->operands.push_back(code_ir->Alloc<lir::CodeLocation>(proceed));
  }

  // early return
  switch (return_kind) {
    case 'V':
      e.Op(dex::OP_RETURN_VOID);
      break;
    case 'L':
    case '[': {
      e.CheckCast(kResult, e.T(return_type));
      auto op = e.Op(dex::OP_RETURN_OBJECT);
      op->operands.push_back(e.R(kResult));
      break;
    }
    case 'J':
    case 'D': {
      e.UnboxInto(return_kind, kResult, kValue);
      auto op = e.Op(dex::OP_RETURN_WIDE);
      op->operands.push_back(e.Pair(kValue));
      break;
    }
    default: {
      e.UnboxInto(return_kind, kResult, kValue);
      auto op = e.Op(dex::OP_RETURN);
      op->operands.push_back(e.R(kValue));
      break;
    }
  }

  e.Place(proceed);

  // write the (possibly rewritten) params back
  for (size_t i = 0; i < params.size(); ++i) {
    auto type = params[i];
    e.Const(kIndex, 2 + static_cast<dex::u4>(i));
    auto aget = e.Op(dex::OP_AGET_OBJECT);
    aget->operands.push_back(e.R(kValue));
    aget->operands.push_back(e.R(kArray));
    aget->operands.push_back(e.R(kIndex));
    char kind = *type->descriptor->c_str();
    switch (type->GetCategory()) {
      case ir::Type::Category::Reference:
        e.CheckCast(kValue, e.T(type));
        e.Move(dex::OP_MOVE_OBJECT_16, param_regs[i], kValue, false);
        break;
      case ir::Type::Category::Scalar:
        e.UnboxInto(kind, kValue, kValue);
        e.Move(dex::OP_MOVE_16, param_regs[i], kValue, false);
        break;
      case ir::Type::Category::WideScalar:
        e.UnboxInto(kind, kValue, kValue);
        e.Move(dex::OP_MOVE_WIDE_16, param_regs[i], kValue, true);
        break;
      case ir::Type::Category::Void:
        return false;
    }
  }

  // parameters back to where the original code expects them
  if (shift > 0) {
    dex::u4 r = params_start;
    if (!is_static) {
      e.Move(dex::OP_MOVE_OBJECT_16, r - shift, r, false);
      r += 1;
    }
    for (auto type : params) {
      switch (type->GetCategory()) {
        case ir::Type::Category::Reference:
          e.Move(dex::OP_MOVE_OBJECT_16, r - shift, r, false);
          r += 1;
          break;
        case ir::Type::Category::Scalar:
          e.Move(dex::OP_MOVE_16, r - shift, r, false);
          r += 1;
          break;
        case ir::Type::Category::WideScalar:
          e.Move(dex::OP_MOVE_WIDE_16, r - shift, r, true);
          r += 2;
          break;
        case ir::Type::Category::Void:
          return false;
      }
    }
  }
  return true;
}

}  // namespace dta
