/*
 * Class Transformer implementation
 *
 * This implementation calls the Java-side DexTransformer which uses dexlib2
 * to perform actual DEX bytecode manipulation.
 */

#include "class_transformer.h"
#include "hook_manager.h"
#include "sidekick_log.h"
#include <cstring>
#include <vector>

#define LOG_TAG "ClassTransformer"
#define LOGI(...) SIDEKICK_LOGI(LOG_TAG, __VA_ARGS__)
#define LOGD(...) SIDEKICK_LOGD(LOG_TAG, __VA_ARGS__)
#define LOGW(...) SIDEKICK_LOGW(LOG_TAG, __VA_ARGS__)
#define LOGE(...) SIDEKICK_LOGE(LOG_TAG, __VA_ARGS__)

// Static member initialization
jclass ClassTransformer::s_dispatcherClass = nullptr;
jmethodID ClassTransformer::s_onEnterMethod = nullptr;
jmethodID ClassTransformer::s_onExitMethod = nullptr;

// DexTransformer class and method
static jclass s_transformerClass = nullptr;
static jmethodID s_transformMethod = nullptr;

void ClassTransformer::init(JNIEnv* env) {
    // Cache HookDispatcher class and methods
    jclass dispatcherClass = env->FindClass(
            "io/yamsergey/dta/sidekick/jvmti/HookDispatcher");

    if (dispatcherClass == nullptr) {
        LOGW("HookDispatcher class not found - hooks will not be available");
        env->ExceptionClear();
        return;
    }

    s_dispatcherClass = (jclass) env->NewGlobalRef(dispatcherClass);
    env->DeleteLocalRef(dispatcherClass);

    s_onEnterMethod = env->GetStaticMethodID(
            s_dispatcherClass,
            "onEnter",
            "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)V"
    );

    s_onExitMethod = env->GetStaticMethodID(
            s_dispatcherClass,
            "onExit",
            "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
    );

    if (s_onEnterMethod == nullptr || s_onExitMethod == nullptr) {
        LOGE("Failed to find HookDispatcher methods");
        env->ExceptionClear();
    }

    // Cache DexTransformer class and transform method
    jclass transformerClass = env->FindClass(
            "io/yamsergey/dta/sidekick/jvmti/DexTransformer");

    if (transformerClass == nullptr) {
        LOGW("DexTransformer class not found - bytecode transformation disabled");
        env->ExceptionClear();
        return;
    }

    s_transformerClass = (jclass) env->NewGlobalRef(transformerClass);
    env->DeleteLocalRef(transformerClass);

    s_transformMethod = env->GetStaticMethodID(
            s_transformerClass,
            "transform",
            "(Ljava/lang/String;[BI)[B"
    );

    if (s_transformMethod == nullptr) {
        LOGE("Failed to find DexTransformer.transform method");
        env->ExceptionClear();
    } else {
        LOGI("ClassTransformer initialized with dexlib2 support");
    }
}

bool ClassTransformer::transform(
        jvmtiEnv* jvmti,
        JNIEnv* env,
        const char* className,
        const unsigned char* classData,
        jint classDataLen,
        unsigned char** newClassData,
        jint* newClassDataLen) {

    // Check if we have the Java transformer available
    if (s_transformerClass == nullptr || s_transformMethod == nullptr) {
        LOGD("Java transformer not available for: %s", className);
        return false;
    }

    // Check if we have hooks for this class (quick native check)
    if (!HookManager::hasHooksForClass(className)) {
        return false;
    }

    LOGD("Calling Java transformer for: %s", className);

    // Create Java string for class name
    jstring classNameStr = env->NewStringUTF(className);
    if (classNameStr == nullptr) {
        LOGE("Failed to create class name string");
        return false;
    }

    // Create Java byte array for class data
    jbyteArray classDataArray = env->NewByteArray(classDataLen);
    if (classDataArray == nullptr) {
        env->DeleteLocalRef(classNameStr);
        LOGE("Failed to create class data array");
        return false;
    }

    env->SetByteArrayRegion(classDataArray, 0, classDataLen,
                            reinterpret_cast<const jbyte*>(classData));

    // Call Java transformer
    jbyteArray result = (jbyteArray) env->CallStaticObjectMethod(
            s_transformerClass,
            s_transformMethod,
            classNameStr,
            classDataArray,
            classDataLen
    );

    // Clean up local refs
    env->DeleteLocalRef(classNameStr);
    env->DeleteLocalRef(classDataArray);

    // Check for exceptions
    if (env->ExceptionCheck()) {
        LOGE("Exception in Java transformer for: %s", className);
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }

    // Check if transformation produced a result
    if (result == nullptr) {
        LOGD("No transformation for: %s", className);
        return false;
    }

    // Get the transformed bytes
    jint resultLen = env->GetArrayLength(result);
    if (resultLen <= 0) {
        env->DeleteLocalRef(result);
        return false;
    }

    // Allocate memory via JVMTI for the transformed class data
    jvmtiError error = jvmti->Allocate(resultLen, newClassData);
    if (error != JVMTI_ERROR_NONE) {
        LOGE("Failed to allocate memory for transformed class: %d", error);
        env->DeleteLocalRef(result);
        return false;
    }

    // Copy the transformed bytes
    env->GetByteArrayRegion(result, 0, resultLen,
                            reinterpret_cast<jbyte*>(*newClassData));
    *newClassDataLen = resultLen;

    env->DeleteLocalRef(result);

    LOGI("Successfully transformed class: %s (%d -> %d bytes)",
         className, classDataLen, resultLen);

    return true;
}
