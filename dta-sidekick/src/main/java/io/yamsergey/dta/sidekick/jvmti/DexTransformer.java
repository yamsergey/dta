package io.yamsergey.dta.sidekick.jvmti;

import io.yamsergey.dta.sidekick.SidekickLog;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler;
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.MethodParameter;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableField;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Transforms DEX bytecode to inject hook callbacks using dexlib2.
 *
 * <p>This transformer modifies class bytecode at load time to inject calls
 * to {@link HookDispatcher} at method entry and exit points.</p>
 */
public class DexTransformer {

    private static final String TAG = "DexTransformer";

    // HookDispatcher method references
    private static final String DISPATCHER_TYPE = "Lio/yamsergey/dta/sidekick/jvmti/HookDispatcher;";
    private static final String ON_ENTER_NAME = "onEnter";
    private static final List<String> ON_ENTER_PARAMS = Arrays.asList(
            "Ljava/lang/String;", "Ljava/lang/Object;", "[Ljava/lang/Object;"
    );
    private static final String ON_ENTER_RETURN = "V";

    private static final String ON_EXIT_NAME = "onExit";
    private static final List<String> ON_EXIT_PARAMS = Arrays.asList(
            "Ljava/lang/String;", "Ljava/lang/Object;", "Ljava/lang/Object;"
    );
    private static final String ON_EXIT_RETURN = "Ljava/lang/Object;";

    /**
     * Transforms a DEX class to inject hooks.
     *
     * @param className    internal class name (e.g., "okhttp3/OkHttpClient")
     * @param classData    original DEX bytecode
     * @param classDataLen length of bytecode
     * @return transformed bytecode, or null if no transformation needed
     */
    public static byte[] transform(String className, byte[] classData, int classDataLen) {
        try {
            // Check if we have hooks for this class
            String dotClassName = className.replace('/', '.');
            List<MethodHook> hooks = HookRegistry.getHooksForClass(dotClassName);

            if (hooks.isEmpty()) {
                SidekickLog.d(TAG, "No hooks registered for: " + className);
                return null;
            }

            SidekickLog.d(TAG, "Transforming class: " + className + " with " + hooks.size() + " hooks");

            // Parse the DEX data
            byte[] dexData = classDataLen < classData.length
                    ? Arrays.copyOf(classData, classDataLen)
                    : classData;

            DexBackedDexFile dexFile = new DexBackedDexFile(Opcodes.getDefault(), dexData);

            // Find and transform the target class
            // JVMTI requires the transformed DEX to contain exactly ONE class
            ClassDef transformedClass = null;

            for (ClassDef classDef : dexFile.getClasses()) {
                String classType = classDef.getType();
                // Class type is like "Lokhttp3/OkHttpClient;"
                String currentClassName = classType.substring(1, classType.length() - 1);

                if (currentClassName.equals(className)) {
                    transformedClass = transformClass(classDef, hooks);
                    if (transformedClass != null) {
                        SidekickLog.i(TAG, "Transformed class: " + className);
                    }
                    break; // Found the target class
                }
            }

            if (transformedClass == null) {
                SidekickLog.d(TAG, "No methods transformed in: " + className);
                return null;
            }

            // Write only the transformed class (JVMTI requirement)
            DexPool dexPool = new DexPool(Opcodes.getDefault());
            dexPool.internClass(transformedClass);

            MemoryDataStore dataStore = new MemoryDataStore();
            dexPool.writeTo(dataStore);

            byte[] result = Arrays.copyOf(dataStore.getBuffer(), dataStore.getSize());
            SidekickLog.i(TAG, "Transformation complete: " + className +
                    " (" + classDataLen + " -> " + result.length + " bytes)");
            return result;

        } catch (Exception e) {
            SidekickLog.e(TAG, "Error transforming class: " + className, e);
            return null;
        }
    }

    /**
     * Transforms a class definition to add hooks to its methods.
     */
    private static ClassDef transformClass(ClassDef classDef, List<MethodHook> hooks) {
        List<Method> newMethods = new ArrayList<>();
        boolean anyTransformed = false;

        for (Method method : classDef.getMethods()) {
            MethodHook matchingHook = findMatchingHook(method, hooks);

            if (matchingHook != null && method.getImplementation() != null) {
                try {
                    Method newMethod = transformMethod(classDef, method, matchingHook);
                    if (newMethod != null) {
                        newMethods.add(newMethod);
                        anyTransformed = true;
                        SidekickLog.d(TAG, "  Hooked: " + method.getName() + buildMethodSignature(method));
                    } else {
                        // Strip debug info from original method to avoid type index issues
                        newMethods.add(stripDebugInfo(method));
                    }
                } catch (Exception e) {
                    SidekickLog.w(TAG, "  Failed to hook: " + method.getName() + " - " + e.getMessage());
                    newMethods.add(stripDebugInfo(method));
                }
            } else {
                // Strip debug info from all methods when transforming the class
                // because the new DexPool has a different type index mapping
                newMethods.add(stripDebugInfo(method));
            }
        }

        if (!anyTransformed) {
            return null;
        }

        // Explicitly copy fields to ensure they're fully materialized
        // (DexBackedClassDef returns lazy iterables that may not survive DexPool writing)
        List<Field> copiedFields = new ArrayList<>();
        for (Field field : classDef.getFields()) {
            copiedFields.add(new ImmutableField(
                    field.getDefiningClass(),
                    field.getName(),
                    field.getType(),
                    field.getAccessFlags(),
                    field.getInitialValue(),
                    field.getAnnotations(),
                    field.getHiddenApiRestrictions()
            ));
        }

        return new ImmutableClassDef(
                classDef.getType(),
                classDef.getAccessFlags(),
                classDef.getSuperclass(),
                classDef.getInterfaces(),
                classDef.getSourceFile(),
                classDef.getAnnotations(),
                copiedFields,
                newMethods
        );
    }

    /**
     * Creates a copy of a method with debug info stripped.
     * This is necessary because when we write to a new DexPool, the type indices
     * in debug info may no longer be valid.
     */
    private static Method stripDebugInfo(Method method) {
        MethodImplementation impl = method.getImplementation();
        if (impl == null) {
            return method; // No implementation, no debug info to strip
        }

        return new ImmutableMethod(
                method.getDefiningClass(),
                method.getName(),
                method.getParameters(),
                method.getReturnType(),
                method.getAccessFlags(),
                method.getAnnotations(),
                method.getHiddenApiRestrictions(),
                new ImmutableMethodImplementation(
                        impl.getRegisterCount(),
                        impl.getInstructions(),
                        impl.getTryBlocks(),
                        Collections.emptyList()  // Strip debug items
                )
        );
    }

    /**
     * Strips debug info from all methods in a class.
     */
    private static ClassDef stripDebugInfoFromClass(ClassDef classDef) {
        List<Method> newMethods = new ArrayList<>();
        for (Method method : classDef.getMethods()) {
            newMethods.add(stripDebugInfo(method));
        }
        return new ImmutableClassDef(
                classDef.getType(),
                classDef.getAccessFlags(),
                classDef.getSuperclass(),
                classDef.getInterfaces(),
                classDef.getSourceFile(),
                classDef.getAnnotations(),
                classDef.getFields(),
                newMethods
        );
    }

    /**
     * Finds a hook that matches the given method.
     */
    private static MethodHook findMatchingHook(Method method, List<MethodHook> hooks) {
        String methodName = method.getName();

        // Skip constructors and static initializers for now
        if (methodName.equals("<init>") || methodName.equals("<clinit>")) {
            return null;
        }

        for (MethodHook hook : hooks) {
            if (!hook.getTargetMethod().equals(methodName)) {
                continue;
            }

            String hookSig = hook.getMethodSignature();
            if (hookSig == null || hookSig.isEmpty()) {
                return hook; // Match any signature
            }

            String methodSig = buildMethodSignature(method);
            if (hookSig.equals(methodSig)) {
                return hook;
            }
        }
        return null;
    }

    /**
     * Builds a JVM method signature from a dexlib2 Method.
     */
    private static String buildMethodSignature(Method method) {
        StringBuilder sb = new StringBuilder("(");
        for (MethodParameter param : method.getParameters()) {
            sb.append(param.getType());
        }
        sb.append(")");
        sb.append(method.getReturnType());
        return sb.toString();
    }

    /**
     * Transforms a method to add hook callbacks at entry and exit.
     *
     * <p>Uses the same approach as Android Studio's slicer (instrumentation.cc):
     * scratch registers are always v0..v4 (guaranteed 4-bit safe), and if extra
     * registers are needed they are added by shifting params up. A prologue
     * relocates params back to their original positions before the body runs.
     * The original method body is left completely untouched.</p>
     *
     * <p>Scratch registers: v0=hookId, v1=thisObj, v2=args, v3=null, v4=temp</p>
     */
    private static Method transformMethod(ClassDef classDef, Method method, MethodHook hook) {
        MethodImplementation impl = method.getImplementation();
        if (impl == null) {
            return null;
        }

        int originalRegCount = impl.getRegisterCount();
        if (originalRegCount < 1) {
            return null;
        }

        // Calculate parameter register info
        boolean isStatic = AccessFlags.STATIC.isSet(method.getAccessFlags());
        int paramRegCount = 0;
        for (MethodParameter param : method.getParameters()) {
            String type = param.getType();
            paramRegCount += (type.equals("J") || type.equals("D")) ? 2 : 1;
        }
        if (!isStatic) {
            paramRegCount++; // 'this' reference
        }

        // Following Android Studio's slicer: always use v0..v4 as scratch.
        // If the method doesn't have enough non-param registers, add more.
        int L = originalRegCount - paramRegCount; // non-param (local) register count
        int scratchRegsNeeded = 5;
        boolean needsShift = L < scratchRegsNeeded;
        int extraRegs = needsShift ? scratchRegsNeeded - L : 0;
        int newRegCount = originalRegCount + extraRegs;
        // VM places params in the last paramRegCount registers
        int newParamStart = newRegCount - paramRegCount;

        String hookId = getHookId(hook, classDef, method);
        List<? extends MethodParameter> params = method.getParameters();
        int actualParamCount = params.size();

        // Scratch registers — always v0..v4 (4-bit safe, no overflow possible)
        int hookIdReg  = 0;
        int thisObjReg = 1;
        int argsReg    = 2;
        int nullReg    = 3;
        int tempReg    = 4;

        List<Instruction> newInstructions = new ArrayList<>();

        // ═══ onEnter hook (scratch at v0..v4, params at newParamStart) ═══

        // hookId string
        newInstructions.add(new ImmutableInstruction21c(
                Opcode.CONST_STRING, hookIdReg,
                new ImmutableStringReference(hookId)));

        // thisObj
        if (isStatic) {
            newInstructions.add(new ImmutableInstruction21s(Opcode.CONST_16, thisObjReg, 0));
        } else {
            // move-object/16 for safety — newParamStart could be > 15
            newInstructions.add(new ImmutableInstruction32x(
                    Opcode.MOVE_OBJECT_16, thisObjReg, newParamStart));
        }

        // Build args array — new-array uses 22c (4-bit regs), v2/v4 are always safe
        if (actualParamCount > 0) {
            newInstructions.add(new ImmutableInstruction21s(
                    Opcode.CONST_16, tempReg, actualParamCount));
            newInstructions.add(new ImmutableInstruction22c(
                    Opcode.NEW_ARRAY, argsReg, tempReg,
                    new com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference("[Ljava/lang/Object;")));

            // Fill array with parameters (box primitives for Object[] storage)
            int paramIdx = 0;
            int paramReg = isStatic ? newParamStart : newParamStart + 1;
            for (MethodParameter param : params) {
                String paramType = param.getType();

                newInstructions.add(new ImmutableInstruction21s(Opcode.CONST_16, tempReg, paramIdx));

                if (paramType.startsWith("L") || paramType.startsWith("[")) {
                    // Object type — store directly
                    newInstructions.add(new ImmutableInstruction23x(
                            Opcode.APUT_OBJECT, paramReg, argsReg, tempReg));
                } else {
                    // Primitive type — box via valueOf then store
                    String boxClass = getBoxClass(paramType);
                    if (boxClass != null) {
                        int regCount = (paramType.equals("J") || paramType.equals("D")) ? 2 : 1;
                        newInstructions.add(new ImmutableInstruction3rc(
                                Opcode.INVOKE_STATIC_RANGE, paramReg, regCount,
                                new ImmutableMethodReference(boxClass, "valueOf",
                                        Arrays.asList(paramType), boxClass)));
                        // move-result-object overwrites tempReg (which had the index)
                        // so re-set the index in nullReg before aput
                        newInstructions.add(new ImmutableInstruction11x(
                                Opcode.MOVE_RESULT_OBJECT, tempReg));
                        newInstructions.add(new ImmutableInstruction21s(
                                Opcode.CONST_16, nullReg, paramIdx));
                        newInstructions.add(new ImmutableInstruction23x(
                                Opcode.APUT_OBJECT, tempReg, argsReg, nullReg));
                    }
                }

                paramReg += (paramType.equals("J") || paramType.equals("D")) ? 2 : 1;
                paramIdx++;
            }
        } else {
            newInstructions.add(new ImmutableInstruction21s(Opcode.CONST_16, argsReg, 0));
        }

        // invoke onEnter — v0..v2 are always 4-bit safe, use invoke-static (35c)
        newInstructions.add(new ImmutableInstruction35c(
                Opcode.INVOKE_STATIC, 3,
                hookIdReg, thisObjReg, argsReg, 0, 0,
                new ImmutableMethodReference(DISPATCHER_TYPE, ON_ENTER_NAME, ON_ENTER_PARAMS, ON_ENTER_RETURN)));

        // Read back modified object params from args array (enables mocking)
        if (actualParamCount > 0) {
            int paramIdx = 0;
            int paramReg = isStatic ? newParamStart : newParamStart + 1;
            for (MethodParameter param : params) {
                String paramType = param.getType();

                if (paramType.startsWith("L") || paramType.startsWith("[")) {
                    newInstructions.add(new ImmutableInstruction21s(Opcode.CONST_16, tempReg, paramIdx));
                    newInstructions.add(new ImmutableInstruction23x(
                            Opcode.AGET_OBJECT, tempReg, argsReg, tempReg));
                    newInstructions.add(new ImmutableInstruction21c(
                            Opcode.CHECK_CAST, tempReg,
                            new com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference(paramType)));
                    newInstructions.add(new ImmutableInstruction32x(
                            Opcode.MOVE_OBJECT_16, paramReg, tempReg));
                }

                paramReg += (paramType.equals("J") || paramType.equals("D")) ? 2 : 1;
                paramIdx++;
            }
        }

        // ═══ Cleanup scratch registers (following AS slicer pattern) ═══
        // Set v0..v4 to zero — the "Zero" type is compatible with both int and
        // reference types in the verifier, preventing type conflicts at merge points.
        for (int i = 0; i < scratchRegsNeeded; i++) {
            newInstructions.add(new ImmutableInstruction11n(Opcode.CONST_4, i, 0));
        }

        // ═══ Param relocation (only if we added extra registers) ═══
        if (needsShift) {
            int srcReg = newParamStart;
            int dstReg = L;
            if (!isStatic) {
                newInstructions.add(new ImmutableInstruction32x(
                        Opcode.MOVE_OBJECT_16, dstReg, srcReg));
                srcReg++;
                dstReg++;
            }
            for (MethodParameter param : params) {
                String type = param.getType();
                if (type.equals("J") || type.equals("D")) {
                    newInstructions.add(new ImmutableInstruction32x(
                            Opcode.MOVE_WIDE_16, dstReg, srcReg));
                    srcReg += 2;
                    dstReg += 2;
                } else if (type.startsWith("L") || type.startsWith("[")) {
                    newInstructions.add(new ImmutableInstruction32x(
                            Opcode.MOVE_OBJECT_16, dstReg, srcReg));
                    srcReg++;
                    dstReg++;
                } else {
                    newInstructions.add(new ImmutableInstruction32x(
                            Opcode.MOVE_16, dstReg, srcReg));
                    srcReg++;
                    dstReg++;
                }
            }
        }

        // Calculate prologue size for offset adjustments
        int prologueSize = 0;
        for (Instruction insn : newInstructions) {
            prologueSize += insn.getCodeUnits();
        }

        // ═══ First pass: build offset map for branch adjustment ═══
        // onExit sizes: non-static adds move-object/16 (3) for 'this',
        // object return adds move-object/16 (3) + move-result-object (1) + check-cast (2),
        // primitive return adds save/restore of return register (+6) to avoid clobbering v0..v2
        final int exitVoidSize = isStatic ? 9 : 10;
        final int exitObjectSize = isStatic ? 13 : 14;
        final int exitPrimitiveSize = isStatic ? 15 : 16;

        Map<Integer, Integer> cumulativeShiftAtOffset = new HashMap<>();
        int currentOrigOffset = 0;
        int cumulativeShift = 0;

        for (Instruction insn : impl.getInstructions()) {
            cumulativeShiftAtOffset.put(currentOrigOffset, cumulativeShift);
            Opcode opcode = insn.getOpcode();
            if (opcode == Opcode.RETURN_VOID) {
                cumulativeShift += exitVoidSize;
            } else if (opcode == Opcode.RETURN_OBJECT) {
                cumulativeShift += exitObjectSize;
            } else if (opcode == Opcode.RETURN || opcode == Opcode.RETURN_WIDE) {
                cumulativeShift += exitPrimitiveSize;
            }
            currentOrigOffset += insn.getCodeUnits();
        }
        cumulativeShiftAtOffset.put(currentOrigOffset, cumulativeShift);

        // ═══ Second pass: copy body with onExit hooks and adjusted branches ═══
        // For onExit, reuse v0..v2 as scratch — safe to clobber before return.
        currentOrigOffset = 0;
        for (Instruction insn : impl.getInstructions()) {
            Opcode opcode = insn.getOpcode();

            // Insert onExit hook before return instructions
            if (opcode == Opcode.RETURN_VOID) {
                if (!isStatic) {
                    newInstructions.add(new ImmutableInstruction32x(
                            Opcode.MOVE_OBJECT_16, thisObjReg, newParamStart));
                }
                newInstructions.add(new ImmutableInstruction21c(
                        Opcode.CONST_STRING, hookIdReg,
                        new ImmutableStringReference(hookId)));
                if (isStatic) {
                    newInstructions.add(new ImmutableInstruction21s(
                            Opcode.CONST_16, thisObjReg, 0));
                }
                newInstructions.add(new ImmutableInstruction21s(
                        Opcode.CONST_16, argsReg, 0)); // null result
                newInstructions.add(new ImmutableInstruction35c(
                        Opcode.INVOKE_STATIC, 3,
                        hookIdReg, thisObjReg, argsReg, 0, 0,
                        new ImmutableMethodReference(DISPATCHER_TYPE, ON_EXIT_NAME,
                                ON_EXIT_PARAMS, ON_EXIT_RETURN)));

            } else if (opcode == Opcode.RETURN || opcode == Opcode.RETURN_WIDE) {
                // Primitive return — save return register to v3 (nullReg) before
                // clobbering v0..v2. v3/v4 are not used by onExit so the value is safe.
                Instruction11x returnInsn = (Instruction11x) insn;
                int returnReg = returnInsn.getRegisterA();
                Opcode saveOp = (opcode == Opcode.RETURN_WIDE) ? Opcode.MOVE_WIDE_16 : Opcode.MOVE_16;
                newInstructions.add(new ImmutableInstruction32x(saveOp, nullReg, returnReg));
                // onExit hook
                if (!isStatic) {
                    newInstructions.add(new ImmutableInstruction32x(
                            Opcode.MOVE_OBJECT_16, thisObjReg, newParamStart));
                }
                newInstructions.add(new ImmutableInstruction21c(
                        Opcode.CONST_STRING, hookIdReg,
                        new ImmutableStringReference(hookId)));
                if (isStatic) {
                    newInstructions.add(new ImmutableInstruction21s(
                            Opcode.CONST_16, thisObjReg, 0));
                }
                newInstructions.add(new ImmutableInstruction21s(
                        Opcode.CONST_16, argsReg, 0)); // null result
                newInstructions.add(new ImmutableInstruction35c(
                        Opcode.INVOKE_STATIC, 3,
                        hookIdReg, thisObjReg, argsReg, 0, 0,
                        new ImmutableMethodReference(DISPATCHER_TYPE, ON_EXIT_NAME,
                                ON_EXIT_PARAMS, ON_EXIT_RETURN)));
                // Restore return register from v3
                newInstructions.add(new ImmutableInstruction32x(saveOp, returnReg, nullReg));

            } else if (opcode == Opcode.RETURN_OBJECT) {
                Instruction11x returnInsn = (Instruction11x) insn;
                int returnReg = returnInsn.getRegisterA();
                // Save return value FIRST — returnReg could overlap with v0 or v1
                newInstructions.add(new ImmutableInstruction32x(
                        Opcode.MOVE_OBJECT_16, argsReg, returnReg));
                if (!isStatic) {
                    newInstructions.add(new ImmutableInstruction32x(
                            Opcode.MOVE_OBJECT_16, thisObjReg, newParamStart));
                } else {
                    newInstructions.add(new ImmutableInstruction21s(
                            Opcode.CONST_16, thisObjReg, 0));
                }
                newInstructions.add(new ImmutableInstruction21c(
                        Opcode.CONST_STRING, hookIdReg,
                        new ImmutableStringReference(hookId)));
                newInstructions.add(new ImmutableInstruction35c(
                        Opcode.INVOKE_STATIC, 3,
                        hookIdReg, thisObjReg, argsReg, 0, 0,
                        new ImmutableMethodReference(DISPATCHER_TYPE, ON_EXIT_NAME,
                                ON_EXIT_PARAMS, ON_EXIT_RETURN)));
                // Capture onExit return value — enables mocking
                newInstructions.add(new ImmutableInstruction11x(
                        Opcode.MOVE_RESULT_OBJECT, returnReg));
                newInstructions.add(new ImmutableInstruction21c(
                        Opcode.CHECK_CAST, returnReg,
                        new com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference(
                                method.getReturnType())));
            }

            // Copy body instruction as-is — only adjust branch offsets, NOT registers
            Instruction adjusted = adjustBranchOffsets(
                    insn, currentOrigOffset, cumulativeShiftAtOffset);
            newInstructions.add(adjusted);

            currentOrigOffset += insn.getCodeUnits();
        }

        // Adjust try blocks (offsets shift due to prologue + onExit insertions)
        List<ImmutableTryBlock> newTryBlocks = new ArrayList<>();
        for (var tryBlock : impl.getTryBlocks()) {
            int origStart = tryBlock.getStartCodeAddress();
            int origEnd = origStart + tryBlock.getCodeUnitCount();

            int shiftAtStart = cumulativeShiftAtOffset.getOrDefault(origStart, 0);
            int shiftAtEnd = cumulativeShiftAtOffset.getOrDefault(origEnd, cumulativeShift);

            int newStart = prologueSize + origStart + shiftAtStart;
            int newLength = tryBlock.getCodeUnitCount() + (shiftAtEnd - shiftAtStart);

            List<ImmutableExceptionHandler> newHandlers = new ArrayList<>();
            for (var handler : tryBlock.getExceptionHandlers()) {
                int handlerAddr = handler.getHandlerCodeAddress();
                int shiftAtHandler = cumulativeShiftAtOffset.getOrDefault(handlerAddr, 0);
                newHandlers.add(new ImmutableExceptionHandler(
                        handler.getExceptionType(),
                        prologueSize + handlerAddr + shiftAtHandler));
            }
            newTryBlocks.add(new ImmutableTryBlock(newStart, newLength, newHandlers));
        }

        return new ImmutableMethod(
                method.getDefiningClass(),
                method.getName(),
                method.getParameters(),
                method.getReturnType(),
                method.getAccessFlags(),
                method.getAnnotations(),
                method.getHiddenApiRestrictions(),
                new ImmutableMethodImplementation(
                        newRegCount,
                        newInstructions,
                        newTryBlocks,
                        Collections.emptyList()
                )
        );
    }

    /**
     * Adjusts branch offsets for an instruction to account for onExit code insertions.
     * Body register numbers are NOT modified — only branch targets are adjusted.
     */
    private static Instruction adjustBranchOffsets(
            Instruction insn, int currentOrigOffset,
            Map<Integer, Integer> cumulativeShiftAtOffset) {

        Opcode opcode = insn.getOpcode();

        if (insn instanceof Instruction10t) {
            Instruction10t i = (Instruction10t) insn;
            int targetOrigOffset = currentOrigOffset + i.getCodeOffset();
            int shiftAtCurrent = cumulativeShiftAtOffset.getOrDefault(currentOrigOffset, 0);
            int shiftAtTarget = cumulativeShiftAtOffset.getOrDefault(targetOrigOffset, 0);
            int newOffset = i.getCodeOffset() + (shiftAtTarget - shiftAtCurrent);
            return new ImmutableInstruction10t(opcode, newOffset);
        }
        if (insn instanceof Instruction20t) {
            Instruction20t i = (Instruction20t) insn;
            int targetOrigOffset = currentOrigOffset + i.getCodeOffset();
            int shiftAtCurrent = cumulativeShiftAtOffset.getOrDefault(currentOrigOffset, 0);
            int shiftAtTarget = cumulativeShiftAtOffset.getOrDefault(targetOrigOffset, 0);
            int newOffset = i.getCodeOffset() + (shiftAtTarget - shiftAtCurrent);
            return new ImmutableInstruction20t(opcode, newOffset);
        }
        if (insn instanceof Instruction30t) {
            Instruction30t i = (Instruction30t) insn;
            int targetOrigOffset = currentOrigOffset + i.getCodeOffset();
            int shiftAtCurrent = cumulativeShiftAtOffset.getOrDefault(currentOrigOffset, 0);
            int shiftAtTarget = cumulativeShiftAtOffset.getOrDefault(targetOrigOffset, 0);
            int newOffset = i.getCodeOffset() + (shiftAtTarget - shiftAtCurrent);
            return new ImmutableInstruction30t(opcode, newOffset);
        }
        if (insn instanceof Instruction21t) {
            Instruction21t i = (Instruction21t) insn;
            int targetOrigOffset = currentOrigOffset + i.getCodeOffset();
            int shiftAtCurrent = cumulativeShiftAtOffset.getOrDefault(currentOrigOffset, 0);
            int shiftAtTarget = cumulativeShiftAtOffset.getOrDefault(targetOrigOffset, 0);
            int newOffset = i.getCodeOffset() + (shiftAtTarget - shiftAtCurrent);
            return new ImmutableInstruction21t(opcode, i.getRegisterA(), newOffset);
        }
        if (insn instanceof Instruction22t) {
            Instruction22t i = (Instruction22t) insn;
            int targetOrigOffset = currentOrigOffset + i.getCodeOffset();
            int shiftAtCurrent = cumulativeShiftAtOffset.getOrDefault(currentOrigOffset, 0);
            int shiftAtTarget = cumulativeShiftAtOffset.getOrDefault(targetOrigOffset, 0);
            int newOffset = i.getCodeOffset() + (shiftAtTarget - shiftAtCurrent);
            return new ImmutableInstruction22t(opcode,
                    i.getRegisterA(), i.getRegisterB(), newOffset);
        }

        // Non-branch instructions pass through unchanged
        return insn;
    }

    /**
     * Gets or generates the hook ID.
     */
    private static String getHookId(MethodHook hook, ClassDef classDef, Method method) {
        String id = hook.getId();
        if (id != null && !id.isEmpty()) {
            return id;
        }

        String classType = classDef.getType();
        String className = classType.substring(1, classType.length() - 1).replace('/', '.');
        return className + "#" + method.getName() + buildMethodSignature(method);
    }

    /**
     * Returns the box class descriptor for a primitive type.
     * Used to generate valueOf() calls for primitive parameter boxing.
     */
    private static String getBoxClass(String primitiveType) {
        if ("I".equals(primitiveType)) return "Ljava/lang/Integer;";
        if ("J".equals(primitiveType)) return "Ljava/lang/Long;";
        if ("F".equals(primitiveType)) return "Ljava/lang/Float;";
        if ("D".equals(primitiveType)) return "Ljava/lang/Double;";
        if ("Z".equals(primitiveType)) return "Ljava/lang/Boolean;";
        if ("B".equals(primitiveType)) return "Ljava/lang/Byte;";
        if ("S".equals(primitiveType)) return "Ljava/lang/Short;";
        if ("C".equals(primitiveType)) return "Ljava/lang/Character;";
        return null;
    }
}
