/*
 * JVMTI Agent for ADT Sidekick
 *
 * This agent provides method hooking capabilities by intercepting class loading
 * and transforming bytecode to inject hook callbacks.
 */

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <mutex>

#include "jvmti.h"
#include "hook_manager.h"
#include "class_transformer.h"
#include "sidekick_log.h"

#define LOG_TAG "SidekickAgent"
#define LOGI(...) SIDEKICK_LOGI(LOG_TAG, __VA_ARGS__)
#define LOGW(...) SIDEKICK_LOGW(LOG_TAG, __VA_ARGS__)
#define LOGE(...) SIDEKICK_LOGE(LOG_TAG, __VA_ARGS__)
#define LOGD(...) SIDEKICK_LOGD(LOG_TAG, __VA_ARGS__)

// Global debug flag definition (declared extern in sidekick_log.h)
bool g_sidekick_debug_enabled = false;

void sidekick_set_debug_enabled(bool enabled) {
    g_sidekick_debug_enabled = enabled;
}

bool sidekick_is_debug_enabled() {
    return g_sidekick_debug_enabled;
}

// Global JVMTI environment
static jvmtiEnv* g_jvmti = nullptr;
static JavaVM* g_vm = nullptr;
static std::mutex g_mutex;

// Java class and method references for callbacks
static jclass g_agentClass = nullptr;
static jmethodID g_shouldTransformMethod = nullptr;
static jmethodID g_getHookIdMethod = nullptr;

/**
 * ClassFileLoadHook callback - called when a class is loaded or retransformed.
 */
static void JNICALL ClassFileLoadHook(
        jvmtiEnv* jvmti_env,
        JNIEnv* jni_env,
        jclass class_being_redefined,
        jobject loader,
        const char* name,
        jobject protection_domain,
        jint class_data_len,
        const unsigned char* class_data,
        jint* new_class_data_len,
        unsigned char** new_class_data) {

    if (name == nullptr) {
        return;
    }

    // Quick check: skip system classes for performance
    if (strncmp(name, "java/", 5) == 0 ||
        strncmp(name, "sun/", 4) == 0 ||
        strncmp(name, "com/android/", 12) == 0 ||
        strncmp(name, "android/", 8) == 0 ||
        strncmp(name, "dalvik/", 7) == 0 ||
        strncmp(name, "libcore/", 8) == 0 ||
        strncmp(name, "io/yamsergey/dta/sidekick/jvmti/", 32) == 0) {
        return;
    }

    // Check if we have hooks for this class
    if (!HookManager::hasHooksForClass(name)) {
        // Also check with Java side in case hooks were registered there
        if (g_agentClass != nullptr && g_shouldTransformMethod != nullptr) {
            jstring className = jni_env->NewStringUTF(name);
            jboolean shouldTransform = jni_env->CallStaticBooleanMethod(
                    g_agentClass, g_shouldTransformMethod, className);
            jni_env->DeleteLocalRef(className);

            if (!shouldTransform) {
                return;
            }
        } else {
            return;
        }
    }

    LOGD("Transforming class: %s", name);

    // Transform the class bytecode
    unsigned char* transformed_data = nullptr;
    jint transformed_len = 0;

    bool success = ClassTransformer::transform(
            jvmti_env,
            jni_env,
            name,
            class_data,
            class_data_len,
            &transformed_data,
            &transformed_len
    );

    if (success && transformed_data != nullptr) {
        *new_class_data = transformed_data;
        *new_class_data_len = transformed_len;
        LOGI("Successfully transformed class: %s (%d -> %d bytes)",
             name, class_data_len, transformed_len);
    }
}

/**
 * Initialize Java callback references.
 */
static bool initJavaCallbacks(JNIEnv* env) {
    jclass agentClass = env->FindClass("io/yamsergey/dta/sidekick/jvmti/JvmtiAgent");
    if (agentClass == nullptr) {
        LOGE("Failed to find JvmtiAgent class");
        env->ExceptionClear();
        return false;
    }

    g_agentClass = (jclass) env->NewGlobalRef(agentClass);
    env->DeleteLocalRef(agentClass);

    g_shouldTransformMethod = env->GetStaticMethodID(
            g_agentClass, "shouldTransformClass", "(Ljava/lang/String;)Z");
    if (g_shouldTransformMethod == nullptr) {
        LOGE("Failed to find shouldTransformClass method");
        env->ExceptionClear();
        return false;
    }

    g_getHookIdMethod = env->GetStaticMethodID(
            g_agentClass, "getHookId",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (g_getHookIdMethod == nullptr) {
        LOGE("Failed to find getHookId method");
        env->ExceptionClear();
        return false;
    }

    LOGI("Java callbacks initialized");
    return true;
}

/**
 * Agent entry point - called when the agent is attached to the VM.
 */
extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(
        JavaVM* vm,
        char* options,
        void* reserved) {

    LOGI("Agent_OnAttach called");

    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_jvmti != nullptr) {
        LOGI("Agent already attached");
        return JNI_OK;
    }

    g_vm = vm;

    // Get JVMTI environment - try multiple versions
    jint result = vm->GetEnv(reinterpret_cast<void**>(&g_jvmti), JVMTI_VERSION_1_2);
    LOGI("GetEnv(JVMTI_VERSION_1_2) result: %d, g_jvmti=%p", result, g_jvmti);
    if (result != JNI_OK) {
        LOGE("Failed to get JVMTI environment: %d", result);
        return result;
    }

    // Get potential capabilities first
    jvmtiCapabilities potentialCaps;
    memset(&potentialCaps, 0, sizeof(potentialCaps));

    jvmtiError error = g_jvmti->GetPotentialCapabilities(&potentialCaps);
    if (error != JVMTI_ERROR_NONE) {
        LOGE("Failed to get potential capabilities: %d", error);
        return JNI_ERR;
    }

    // Log raw bytes for debugging (full 16 bytes to cover all capabilities)
    unsigned char* capsBytes = (unsigned char*)&potentialCaps;
    LOGI("Capabilities size: %zu bytes", sizeof(potentialCaps));
    LOGI("Capabilities raw (16 bytes): %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x",
         capsBytes[0], capsBytes[1], capsBytes[2], capsBytes[3],
         capsBytes[4], capsBytes[5], capsBytes[6], capsBytes[7],
         capsBytes[8], capsBytes[9], capsBytes[10], capsBytes[11],
         capsBytes[12], capsBytes[13], capsBytes[14], capsBytes[15]);

    LOGI("Potential capabilities: retransform=%d, retransform_any=%d, all_class_hook=%d",
         potentialCaps.can_retransform_classes,
         potentialCaps.can_retransform_any_class,
         potentialCaps.can_generate_all_class_hook_events);
    LOGI("Other capabilities: can_tag_objects=%d, can_redefine_classes=%d, can_suspend=%d",
         potentialCaps.can_tag_objects,
         potentialCaps.can_redefine_classes,
         potentialCaps.can_suspend);

    // Request only available capabilities
    jvmtiCapabilities caps;
    memset(&caps, 0, sizeof(caps));
    if (potentialCaps.can_retransform_classes) {
        caps.can_retransform_classes = 1;
    }
    if (potentialCaps.can_retransform_any_class) {
        caps.can_retransform_any_class = 1;
    }
    if (potentialCaps.can_generate_all_class_hook_events) {
        caps.can_generate_all_class_hook_events = 1;
    }

    error = g_jvmti->AddCapabilities(&caps);
    if (error != JVMTI_ERROR_NONE) {
        LOGE("Failed to add capabilities: %d", error);
        return JNI_ERR;
    }

    LOGI("Capabilities added successfully");

    // Set up event callbacks
    jvmtiEventCallbacks callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.ClassFileLoadHook = ClassFileLoadHook;

    error = g_jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
    if (error != JVMTI_ERROR_NONE) {
        LOGE("Failed to set event callbacks: %d", error);
        return JNI_ERR;
    }

    // Enable ClassFileLoadHook event
    error = g_jvmti->SetEventNotificationMode(
            JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, (jthread)NULL);
    if (error != JVMTI_ERROR_NONE) {
        LOGE("Failed to enable ClassFileLoadHook: %d", error);
        return JNI_ERR;
    }

    LOGI("JVMTI agent attached successfully");
    return JNI_OK;
}

/**
 * Called when the library is loaded.
 */
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnLoad called");

    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("Failed to get JNI environment");
        return JNI_ERR;
    }

    g_vm = vm;

    // Initialize the class transformer
    ClassTransformer::init(env);

    return JNI_VERSION_1_6;
}

// =============================================================================
// JNI Methods called from Java
// =============================================================================

extern "C" JNIEXPORT void JNICALL
Java_io_yamsergey_dta_sidekick_jvmti_JvmtiAgent_nativeRegisterTransformerHook(
        JNIEnv* env,
        jclass clazz) {

    LOGI("nativeRegisterTransformerHook called");

    if (env == nullptr) {
        LOGE("JNIEnv is null");
        return;
    }

    // Check if JVMTI was properly initialized
    if (g_jvmti == nullptr) {
        LOGW("JVMTI not initialized - agent may not have been attached properly");
        // Still try to initialize Java callbacks for future use
    }

    // Initialize Java callbacks
    if (!initJavaCallbacks(env)) {
        LOGW("Failed to initialize Java callbacks - hooks may not work");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_yamsergey_dta_sidekick_jvmti_JvmtiAgent_nativeAddHookTarget(
        JNIEnv* env,
        jclass clazz,
        jstring className,
        jstring methodName,
        jstring methodSig,
        jstring hookId) {

    const char* classNameStr = env->GetStringUTFChars(className, nullptr);
    const char* methodNameStr = env->GetStringUTFChars(methodName, nullptr);
    const char* methodSigStr = methodSig ? env->GetStringUTFChars(methodSig, nullptr) : nullptr;
    const char* hookIdStr = env->GetStringUTFChars(hookId, nullptr);

    HookManager::addHookTarget(classNameStr, methodNameStr, methodSigStr, hookIdStr);

    env->ReleaseStringUTFChars(className, classNameStr);
    env->ReleaseStringUTFChars(methodName, methodNameStr);
    if (methodSigStr) env->ReleaseStringUTFChars(methodSig, methodSigStr);
    env->ReleaseStringUTFChars(hookId, hookIdStr);
}

extern "C" JNIEXPORT void JNICALL
Java_io_yamsergey_dta_sidekick_jvmti_JvmtiAgent_nativeRetransformClasses(
        JNIEnv* env,
        jclass clazz,
        jobjectArray classes) {

    if (g_jvmti == nullptr) {
        LOGE("JVMTI not initialized");
        return;
    }

    jint count = env->GetArrayLength(classes);
    if (count == 0) {
        return;
    }

    auto* classArray = new jclass[count];
    for (jint i = 0; i < count; i++) {
        classArray[i] = (jclass) env->GetObjectArrayElement(classes, i);
    }

    jvmtiError error = g_jvmti->RetransformClasses(count, classArray);
    if (error != JVMTI_ERROR_NONE) {
        LOGE("RetransformClasses failed: %d", error);
    } else {
        LOGI("Retransformed %d classes", count);
    }

    delete[] classArray;
}

// Getter for the global JVMTI environment
jvmtiEnv* getJvmtiEnv() {
    return g_jvmti;
}

// Getter for the global JavaVM
JavaVM* getJavaVM() {
    return g_vm;
}

extern "C" JNIEXPORT void JNICALL
Java_io_yamsergey_dta_sidekick_jvmti_JvmtiAgent_nativeSetDebugEnabled(
        JNIEnv* env,
        jclass clazz,
        jboolean enabled) {
    sidekick_set_debug_enabled(enabled);
}
