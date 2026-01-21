/*
 * Class Transformer - handles DEX bytecode transformation
 */

#ifndef CLASS_TRANSFORMER_H
#define CLASS_TRANSFORMER_H

#include <jni.h>
#include "jvmti.h"

/**
 * Transforms class bytecode to inject hook callbacks.
 *
 * NOTE: Full DEX bytecode transformation is complex. This implementation
 * provides the infrastructure, but actual bytecode manipulation may require
 * integration with libraries like dexlib2 or similar tools.
 *
 * Current approach:
 * - For classes with registered hooks, mark them for transformation
 * - The transformation injects calls to HookDispatcher at method entry/exit
 * - Uses JVMTI's Allocate to create new bytecode buffer
 */
class ClassTransformer {
public:
    /**
     * Initialize the transformer.
     * Must be called during JNI_OnLoad.
     */
    static void init(JNIEnv* env);

    /**
     * Transform a class's bytecode to inject hooks.
     *
     * @param jvmti          JVMTI environment
     * @param env            JNI environment
     * @param className      Internal class name (e.g., "okhttp3/OkHttpClient")
     * @param classData      Original class bytecode
     * @param classDataLen   Length of original bytecode
     * @param newClassData   Output: transformed bytecode (allocated via JVMTI)
     * @param newClassDataLen Output: length of transformed bytecode
     * @return true if transformation was performed, false otherwise
     */
    static bool transform(
            jvmtiEnv* jvmti,
            JNIEnv* env,
            const char* className,
            const unsigned char* classData,
            jint classDataLen,
            unsigned char** newClassData,
            jint* newClassDataLen
    );

private:
    // Cache for HookDispatcher class and methods
    static jclass s_dispatcherClass;
    static jmethodID s_onEnterMethod;
    static jmethodID s_onExitMethod;
};

#endif // CLASS_TRANSFORMER_H
