package io.yamsergey.adt.sidekick.jvmti;

import io.yamsergey.adt.sidekick.SidekickLog;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.BuilderInstruction;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction12x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21s;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.*;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
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
    private static final String DISPATCHER_TYPE = "Lio/yamsergey/adt/sidekick/jvmti/HookDispatcher;";
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
     * <p>Uses a two-pass approach:
     * 1. First pass: calculate offset mapping for all insertions
     * 2. Second pass: build instructions with adjusted branch targets</p>
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

        int originalParamStart = originalRegCount - paramRegCount;
        int extraRegs = 4;
        int newRegCount = originalRegCount + extraRegs;
        int newParamStart = newRegCount - paramRegCount;

        String hookId = getHookId(hook, classDef, method);

        // Count actual parameters (excluding 'this')
        List<? extends MethodParameter> params = method.getParameters();
        int actualParamCount = params.size();

        // We need extra registers: hookId, thisObj, argsArray, null, + 1 for array index
        int extraRegsNeeded = 5;
        extraRegs = extraRegsNeeded;
        newRegCount = originalRegCount + extraRegs;
        newParamStart = newRegCount - paramRegCount;

        // Registers for hook calls (in the new extra space)
        int hookIdReg = originalParamStart;
        int thisObjReg = originalParamStart + 1;
        int argsReg = originalParamStart + 2;
        int nullReg = originalParamStart + 3;
        int tempReg = originalParamStart + 4;

        // Build onEnter prologue
        List<Instruction> newInstructions = new ArrayList<>();

        newInstructions.add(new ImmutableInstruction21c(
                Opcode.CONST_STRING, hookIdReg,
                new ImmutableStringReference(hookId)));

        if (isStatic) {
            newInstructions.add(new ImmutableInstruction21s(Opcode.CONST_16, thisObjReg, 0));
        } else {
            newInstructions.add(new ImmutableInstruction12x(Opcode.MOVE_OBJECT, thisObjReg, newParamStart));
        }

        // Build args array if there are parameters
        if (actualParamCount > 0) {
            // Create new Object[paramCount]
            newInstructions.add(new ImmutableInstruction21s(Opcode.CONST_16, tempReg, actualParamCount));
            newInstructions.add(new ImmutableInstruction22c(
                    Opcode.NEW_ARRAY, argsReg, tempReg,
                    new com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference("[Ljava/lang/Object;")));

            // Fill array with parameters
            int paramIdx = 0;
            int paramReg = isStatic ? newParamStart : newParamStart + 1;
            for (MethodParameter param : params) {
                String paramType = param.getType();

                // Set array index
                newInstructions.add(new ImmutableInstruction21s(Opcode.CONST_16, tempReg, paramIdx));

                // For object types, directly store; for primitives, we'd need boxing (skip for now)
                if (paramType.startsWith("L") || paramType.startsWith("[")) {
                    // Object type - store directly
                    newInstructions.add(new ImmutableInstruction23x(
                            Opcode.APUT_OBJECT, paramReg, argsReg, tempReg));
                }
                // Skip primitive types for now (they require boxing which adds complexity)

                // Move to next parameter register
                if (paramType.equals("J") || paramType.equals("D")) {
                    paramReg += 2; // long and double take 2 registers
                } else {
                    paramReg += 1;
                }
                paramIdx++;
            }
        } else {
            // No parameters - pass null
            newInstructions.add(new ImmutableInstruction21s(Opcode.CONST_16, argsReg, 0));
        }

        newInstructions.add(new ImmutableInstruction35c(
                Opcode.INVOKE_STATIC, 3,
                hookIdReg, thisObjReg, argsReg, 0, 0,
                new ImmutableMethodReference(DISPATCHER_TYPE, ON_ENTER_NAME, ON_ENTER_PARAMS, ON_ENTER_RETURN)));

        int prologueSize = 0;
        for (Instruction insn : newInstructions) {
            prologueSize += insn.getCodeUnits();
        }

        // FIRST PASS: Build offset map (original offset -> shift amount at that point)
        // RETURN_VOID: const/16 (2) + invoke-static (3) = 5 code units
        // RETURN_OBJECT: just invoke-static (3) = 3 code units (return value is in existing register)
        // RETURN/RETURN_WIDE (primitives): const/16 (2) + invoke-static (3) = 5 code units (pass null for primitive)
        final int EXIT_VOID_SIZE = 5;
        final int EXIT_OBJECT_SIZE = 3;
        final int EXIT_PRIMITIVE_SIZE = 5;
        Map<Integer, Integer> cumulativeShiftAtOffset = new HashMap<>();
        int currentOrigOffset = 0;
        int cumulativeShift = 0;

        for (Instruction insn : impl.getInstructions()) {
            cumulativeShiftAtOffset.put(currentOrigOffset, cumulativeShift);
            Opcode opcode = insn.getOpcode();
            if (opcode == Opcode.RETURN_VOID) {
                cumulativeShift += EXIT_VOID_SIZE;
            } else if (opcode == Opcode.RETURN_OBJECT) {
                cumulativeShift += EXIT_OBJECT_SIZE;
            } else if (opcode == Opcode.RETURN || opcode == Opcode.RETURN_WIDE) {
                cumulativeShift += EXIT_PRIMITIVE_SIZE;
            }
            currentOrigOffset += insn.getCodeUnits();
        }
        cumulativeShiftAtOffset.put(currentOrigOffset, cumulativeShift); // End of method

        // SECOND PASS: Build instructions with onExit insertions and adjusted branches
        currentOrigOffset = 0;
        for (Instruction insn : impl.getInstructions()) {
            Opcode opcode = insn.getOpcode();

            // Insert onExit before return instructions
            if (opcode == Opcode.RETURN_VOID) {
                // For void return, pass null as result
                newInstructions.add(new ImmutableInstruction21s(Opcode.CONST_16, nullReg, 0));
                newInstructions.add(new ImmutableInstruction35c(
                        Opcode.INVOKE_STATIC, 3,
                        hookIdReg, thisObjReg, nullReg, 0, 0,
                        new ImmutableMethodReference(DISPATCHER_TYPE, ON_EXIT_NAME, ON_EXIT_PARAMS, ON_EXIT_RETURN)));
            } else if (opcode == Opcode.RETURN_OBJECT) {
                // For object return, pass the actual return value
                Instruction11x returnInsn = (Instruction11x) insn;
                int returnReg = shift(returnInsn.getRegisterA(), originalParamStart, extraRegs);
                newInstructions.add(new ImmutableInstruction35c(
                        Opcode.INVOKE_STATIC, 3,
                        hookIdReg, thisObjReg, returnReg, 0, 0,
                        new ImmutableMethodReference(DISPATCHER_TYPE, ON_EXIT_NAME, ON_EXIT_PARAMS, ON_EXIT_RETURN)));
            } else if (opcode == Opcode.RETURN || opcode == Opcode.RETURN_WIDE) {
                // For primitive return (int, boolean, long, double, etc.), pass null
                // Primitive values can't be passed as Object without boxing, so we skip the return value
                newInstructions.add(new ImmutableInstruction21s(Opcode.CONST_16, nullReg, 0));
                newInstructions.add(new ImmutableInstruction35c(
                        Opcode.INVOKE_STATIC, 3,
                        hookIdReg, thisObjReg, nullReg, 0, 0,
                        new ImmutableMethodReference(DISPATCHER_TYPE, ON_EXIT_NAME, ON_EXIT_PARAMS, ON_EXIT_RETURN)));
            }

            // Shift registers and adjust branch offsets
            Instruction shifted = shiftRegistersAndAdjustBranches(
                    insn, originalParamStart, extraRegs,
                    currentOrigOffset, cumulativeShiftAtOffset);
            newInstructions.add(shifted);

            currentOrigOffset += insn.getCodeUnits();
        }

        // Adjust try blocks
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
     * Shifts registers and adjusts branch offsets for an instruction.
     * Branch targets are adjusted based on the cumulative shift at the target offset.
     */
    private static Instruction shiftRegistersAndAdjustBranches(
            Instruction insn, int paramStart, int shiftAmount,
            int currentOrigOffset, Map<Integer, Integer> cumulativeShiftAtOffset) {

        Opcode opcode = insn.getOpcode();

        // Handle branch instructions - need to adjust offsets
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
            return new ImmutableInstruction21t(opcode,
                    shift(i.getRegisterA(), paramStart, shiftAmount), newOffset);
        }
        if (insn instanceof Instruction22t) {
            Instruction22t i = (Instruction22t) insn;
            int targetOrigOffset = currentOrigOffset + i.getCodeOffset();
            int shiftAtCurrent = cumulativeShiftAtOffset.getOrDefault(currentOrigOffset, 0);
            int shiftAtTarget = cumulativeShiftAtOffset.getOrDefault(targetOrigOffset, 0);
            int newOffset = i.getCodeOffset() + (shiftAtTarget - shiftAtCurrent);
            return new ImmutableInstruction22t(opcode,
                    shift(i.getRegisterA(), paramStart, shiftAmount),
                    shift(i.getRegisterB(), paramStart, shiftAmount), newOffset);
        }

        // For non-branch instructions, just shift registers
        return shiftRegisters(insn, paramStart, shiftAmount);
    }

    /**
     * Shifts register numbers in an instruction.
     * Registers >= paramStart are shifted up by shiftAmount.
     */
    private static Instruction shiftRegisters(Instruction insn, int paramStart, int shiftAmount) {
        Opcode opcode = insn.getOpcode();

        // Handle different instruction formats
        // Each format has different register fields that need shifting

        if (insn instanceof Instruction10t) {
            return insn; // No registers
        }
        if (insn instanceof Instruction10x) {
            return insn; // No registers (nop, return-void)
        }
        if (insn instanceof Instruction11n) {
            Instruction11n i = (Instruction11n) insn;
            return new ImmutableInstruction11n(opcode, shift(i.getRegisterA(), paramStart, shiftAmount), i.getNarrowLiteral());
        }
        if (insn instanceof Instruction11x) {
            Instruction11x i = (Instruction11x) insn;
            return new ImmutableInstruction11x(opcode, shift(i.getRegisterA(), paramStart, shiftAmount));
        }
        if (insn instanceof Instruction12x) {
            Instruction12x i = (Instruction12x) insn;
            return new ImmutableInstruction12x(opcode,
                    shift(i.getRegisterA(), paramStart, shiftAmount),
                    shift(i.getRegisterB(), paramStart, shiftAmount));
        }
        if (insn instanceof Instruction20t) {
            return insn; // No registers (goto/16)
        }
        if (insn instanceof Instruction21c) {
            Instruction21c i = (Instruction21c) insn;
            return new ImmutableInstruction21c(opcode, shift(i.getRegisterA(), paramStart, shiftAmount), i.getReference());
        }
        if (insn instanceof Instruction21ih) {
            Instruction21ih i = (Instruction21ih) insn;
            return new ImmutableInstruction21ih(opcode, shift(i.getRegisterA(), paramStart, shiftAmount), i.getNarrowLiteral());
        }
        if (insn instanceof Instruction21lh) {
            Instruction21lh i = (Instruction21lh) insn;
            return new ImmutableInstruction21lh(opcode, shift(i.getRegisterA(), paramStart, shiftAmount), i.getWideLiteral());
        }
        if (insn instanceof Instruction21s) {
            Instruction21s i = (Instruction21s) insn;
            return new ImmutableInstruction21s(opcode, shift(i.getRegisterA(), paramStart, shiftAmount), i.getNarrowLiteral());
        }
        if (insn instanceof Instruction21t) {
            Instruction21t i = (Instruction21t) insn;
            return new ImmutableInstruction21t(opcode, shift(i.getRegisterA(), paramStart, shiftAmount), i.getCodeOffset());
        }
        if (insn instanceof Instruction22b) {
            Instruction22b i = (Instruction22b) insn;
            return new ImmutableInstruction22b(opcode,
                    shift(i.getRegisterA(), paramStart, shiftAmount),
                    shift(i.getRegisterB(), paramStart, shiftAmount),
                    i.getNarrowLiteral());
        }
        if (insn instanceof Instruction22c) {
            Instruction22c i = (Instruction22c) insn;
            return new ImmutableInstruction22c(opcode,
                    shift(i.getRegisterA(), paramStart, shiftAmount),
                    shift(i.getRegisterB(), paramStart, shiftAmount),
                    i.getReference());
        }
        if (insn instanceof Instruction22s) {
            Instruction22s i = (Instruction22s) insn;
            return new ImmutableInstruction22s(opcode,
                    shift(i.getRegisterA(), paramStart, shiftAmount),
                    shift(i.getRegisterB(), paramStart, shiftAmount),
                    i.getNarrowLiteral());
        }
        if (insn instanceof Instruction22t) {
            Instruction22t i = (Instruction22t) insn;
            return new ImmutableInstruction22t(opcode,
                    shift(i.getRegisterA(), paramStart, shiftAmount),
                    shift(i.getRegisterB(), paramStart, shiftAmount),
                    i.getCodeOffset());
        }
        if (insn instanceof Instruction22x) {
            Instruction22x i = (Instruction22x) insn;
            return new ImmutableInstruction22x(opcode,
                    shift(i.getRegisterA(), paramStart, shiftAmount),
                    shift(i.getRegisterB(), paramStart, shiftAmount));
        }
        if (insn instanceof Instruction23x) {
            Instruction23x i = (Instruction23x) insn;
            return new ImmutableInstruction23x(opcode,
                    shift(i.getRegisterA(), paramStart, shiftAmount),
                    shift(i.getRegisterB(), paramStart, shiftAmount),
                    shift(i.getRegisterC(), paramStart, shiftAmount));
        }
        if (insn instanceof Instruction30t) {
            return insn; // No registers (goto/32)
        }
        if (insn instanceof Instruction31c) {
            Instruction31c i = (Instruction31c) insn;
            return new ImmutableInstruction31c(opcode, shift(i.getRegisterA(), paramStart, shiftAmount), i.getReference());
        }
        if (insn instanceof Instruction31i) {
            Instruction31i i = (Instruction31i) insn;
            return new ImmutableInstruction31i(opcode, shift(i.getRegisterA(), paramStart, shiftAmount), i.getNarrowLiteral());
        }
        if (insn instanceof Instruction31t) {
            Instruction31t i = (Instruction31t) insn;
            return new ImmutableInstruction31t(opcode, shift(i.getRegisterA(), paramStart, shiftAmount), i.getCodeOffset());
        }
        if (insn instanceof Instruction32x) {
            Instruction32x i = (Instruction32x) insn;
            return new ImmutableInstruction32x(opcode,
                    shift(i.getRegisterA(), paramStart, shiftAmount),
                    shift(i.getRegisterB(), paramStart, shiftAmount));
        }
        if (insn instanceof Instruction35c) {
            Instruction35c i = (Instruction35c) insn;
            return new ImmutableInstruction35c(opcode, i.getRegisterCount(),
                    shift(i.getRegisterC(), paramStart, shiftAmount),
                    shift(i.getRegisterD(), paramStart, shiftAmount),
                    shift(i.getRegisterE(), paramStart, shiftAmount),
                    shift(i.getRegisterF(), paramStart, shiftAmount),
                    shift(i.getRegisterG(), paramStart, shiftAmount),
                    i.getReference());
        }
        if (insn instanceof Instruction3rc) {
            Instruction3rc i = (Instruction3rc) insn;
            return new ImmutableInstruction3rc(opcode,
                    shift(i.getStartRegister(), paramStart, shiftAmount),
                    i.getRegisterCount(),
                    i.getReference());
        }
        if (insn instanceof Instruction45cc) {
            Instruction45cc i = (Instruction45cc) insn;
            return new ImmutableInstruction45cc(opcode, i.getRegisterCount(),
                    shift(i.getRegisterC(), paramStart, shiftAmount),
                    shift(i.getRegisterD(), paramStart, shiftAmount),
                    shift(i.getRegisterE(), paramStart, shiftAmount),
                    shift(i.getRegisterF(), paramStart, shiftAmount),
                    shift(i.getRegisterG(), paramStart, shiftAmount),
                    i.getReference(), i.getReference2());
        }
        if (insn instanceof Instruction4rcc) {
            Instruction4rcc i = (Instruction4rcc) insn;
            return new ImmutableInstruction4rcc(opcode,
                    shift(i.getStartRegister(), paramStart, shiftAmount),
                    i.getRegisterCount(),
                    i.getReference(), i.getReference2());
        }
        if (insn instanceof Instruction51l) {
            Instruction51l i = (Instruction51l) insn;
            return new ImmutableInstruction51l(opcode, shift(i.getRegisterA(), paramStart, shiftAmount), i.getWideLiteral());
        }

        // For any unhandled format, return as-is and log a warning
        SidekickLog.w(TAG, "Unhandled instruction format: " + insn.getClass().getSimpleName());
        return insn;
    }

    /**
     * Shifts a register number if it's in the parameter range.
     */
    private static int shift(int reg, int paramStart, int shiftAmount) {
        return reg >= paramStart ? reg + shiftAmount : reg;
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
}
