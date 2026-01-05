package io.yamsergey.adt.sidekick.jvmti;

import android.util.Log;

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
import java.util.List;

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
                Log.d(TAG, "No hooks registered for: " + className);
                return null;
            }

            Log.d(TAG, "Transforming class: " + className + " with " + hooks.size() + " hooks");

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
                        Log.i(TAG, "Transformed class: " + className);
                    }
                    break; // Found the target class
                }
            }

            if (transformedClass == null) {
                Log.d(TAG, "No methods transformed in: " + className);
                return null;
            }

            // Write only the transformed class (JVMTI requirement)
            DexPool dexPool = new DexPool(Opcodes.getDefault());
            dexPool.internClass(transformedClass);

            MemoryDataStore dataStore = new MemoryDataStore();
            dexPool.writeTo(dataStore);

            byte[] result = Arrays.copyOf(dataStore.getBuffer(), dataStore.getSize());
            Log.i(TAG, "Transformation complete: " + className +
                    " (" + classDataLen + " -> " + result.length + " bytes)");
            return result;

        } catch (Exception e) {
            Log.e(TAG, "Error transforming class: " + className, e);
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
                        Log.d(TAG, "  Hooked: " + method.getName() + buildMethodSignature(method));
                    } else {
                        // Strip debug info from original method to avoid type index issues
                        newMethods.add(stripDebugInfo(method));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "  Failed to hook: " + method.getName() + " - " + e.getMessage());
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
     * Transforms a method to add hook callbacks at entry.
     *
     * <p>When we add extra registers to a method, we're effectively inserting them
     * at the beginning of the register space. This shifts all parameter register
     * references up by the number of added registers. We must rewrite all instructions
     * to use the new register numbers.</p>
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

        // In the original method:
        // - Local registers: v0 to v(originalRegCount - paramRegCount - 1)
        // - Parameter registers: v(originalRegCount - paramRegCount) to v(originalRegCount - 1)
        int originalParamStart = originalRegCount - paramRegCount;

        // We need 4 extra registers for our hook call
        // These go at the END of local registers (before params in the new layout)
        int extraRegs = 4;
        int newRegCount = originalRegCount + extraRegs;
        int newParamStart = newRegCount - paramRegCount;

        // Build the list of new instructions
        List<Instruction> newInstructions = new ArrayList<>();

        // Get hook ID
        String hookId = getHookId(hook, classDef, method);

        // Use registers in the new extra space (between old locals and new params)
        int hookIdReg = originalParamStart;  // First new register
        int thisObjReg = originalParamStart + 1;
        int argsReg = originalParamStart + 2;
        // Register at originalParamStart + 3 is spare

        // Instruction 1: const-string hookIdReg, "hookId"
        newInstructions.add(new ImmutableInstruction21c(
                Opcode.CONST_STRING,
                hookIdReg,
                new ImmutableStringReference(hookId)
        ));

        // Instruction 2: Get 'this' reference or null for static
        if (isStatic) {
            newInstructions.add(new ImmutableInstruction21s(
                    Opcode.CONST_16,
                    thisObjReg,
                    0
            ));
        } else {
            // 'this' is at p0, which is at newParamStart in the new layout
            newInstructions.add(new ImmutableInstruction12x(
                    Opcode.MOVE_OBJECT,
                    thisObjReg,
                    newParamStart
            ));
        }

        // Instruction 3: const/16 argsReg, 0x0 (null args for now)
        newInstructions.add(new ImmutableInstruction21s(
                Opcode.CONST_16,
                argsReg,
                0
        ));

        // Instruction 4: invoke-static HookDispatcher.onEnter(hookId, this, args)
        newInstructions.add(new ImmutableInstruction35c(
                Opcode.INVOKE_STATIC,
                3,
                hookIdReg, thisObjReg, argsReg, 0, 0,
                new ImmutableMethodReference(
                        DISPATCHER_TYPE,
                        ON_ENTER_NAME,
                        ON_ENTER_PARAMS,
                        ON_ENTER_RETURN
                )
        ));

        // Calculate the size of prepended instructions (in code units)
        int prependSize = 0;
        for (Instruction insn : newInstructions) {
            prependSize += insn.getCodeUnits();
        }

        // Now add the original instructions with shifted register numbers
        // Any register >= originalParamStart needs to be shifted by extraRegs
        for (Instruction insn : impl.getInstructions()) {
            Instruction shifted = shiftRegisters(insn, originalParamStart, extraRegs);
            newInstructions.add(shifted);
        }

        // Handle try blocks - need to adjust code addresses by prepended instruction size
        List<ImmutableTryBlock> newTryBlocks = new ArrayList<>();
        for (var tryBlock : impl.getTryBlocks()) {
            List<ImmutableExceptionHandler> newHandlers = new ArrayList<>();
            for (var handler : tryBlock.getExceptionHandlers()) {
                // Shift handler address by prepend size
                newHandlers.add(new ImmutableExceptionHandler(
                        handler.getExceptionType(),
                        handler.getHandlerCodeAddress() + prependSize
                ));
            }
            // Shift try block start address by prepend size
            newTryBlocks.add(new ImmutableTryBlock(
                    tryBlock.getStartCodeAddress() + prependSize,
                    tryBlock.getCodeUnitCount(),
                    newHandlers
            ));
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
                        Collections.emptyList()  // Clear debug items
                )
        );
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
        Log.w(TAG, "Unhandled instruction format: " + insn.getClass().getSimpleName());
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
