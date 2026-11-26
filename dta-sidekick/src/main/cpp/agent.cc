// JVMTI agent for the DTA sidekick: attaches to the running app (Debug.attachJvmtiAgent),
// puts the hook shim on the bootstrap class path and installs entry/exit hooks by
// retransforming already-loaded classes through slicer (see transform.h).
//
// Constitution I: nothing here may take the app down. Every JVMTI error is returned to
// Kotlin as a reason string, slicer failures arrive as exceptions caught in Transform(),
// and a failed transform leaves the class bytes untouched.

#include <android/api-level.h>
#include <android/log.h>
#include <jni.h>

#include <cstring>
#include <ctime>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "include/jvmti.h"
#include "transform.h"

#define LOG_TAG "DtaAgent"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

jvmtiEnv* g_jvmti = nullptr;
std::mutex g_attach_mutex;

// class descriptor ("Ljava/net/URL;") -> every hook on that class. ART hands the
// original (untransformed) image on each retransform, so all targets of a class
// are re-applied every time.
std::mutex g_targets_mutex;
std::unordered_map<std::string, std::vector<dta::HookTarget>> g_targets;

// Set by the load hook while this thread runs RetransformClasses for nativeHook.
thread_local std::string* t_transform_error = nullptr;

std::string JvmtiErrorName(jvmtiError error) {
  char* name = nullptr;
  if (g_jvmti != nullptr && g_jvmti->GetErrorName(error, &name) == JVMTI_ERROR_NONE &&
      name != nullptr) {
    std::string result(name);
    g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
    return result;
  }
  return "JVMTI error " + std::to_string(error);
}

void JNICALL OnClassFileLoad(jvmtiEnv* jvmti, JNIEnv*, jclass, jobject, const char* name,
                             jobject, jint class_data_len, const unsigned char* class_data,
                             jint* new_class_data_len, unsigned char** new_class_data) {
  if (name == nullptr) return;
  std::string descriptor = "L" + std::string(name) + ";";
  std::vector<dta::HookTarget> targets;
  {
    std::lock_guard<std::mutex> lock(g_targets_mutex);
    auto it = g_targets.find(descriptor);
    if (it == g_targets.end()) return;
    targets = it->second;
  }

  unsigned char* image = nullptr;
  size_t image_len = 0;
  std::string error = dta::Transform(
      descriptor, targets, class_data, static_cast<size_t>(class_data_len),
      [jvmti](size_t size) -> void* {
        unsigned char* mem = nullptr;
        return jvmti->Allocate(static_cast<jlong>(size), &mem) == JVMTI_ERROR_NONE ? mem
                                                                                  : nullptr;
      },
      [jvmti](void* ptr) { jvmti->Deallocate(static_cast<unsigned char*>(ptr)); },
      &image, &image_len);

  if (!error.empty()) {
    LOGW("transform of %s skipped: %s", name, error.c_str());
    if (t_transform_error != nullptr) *t_transform_error = error;
    return;
  }
  *new_class_data = image;
  *new_class_data_len = static_cast<jint>(image_len);
}

std::string Attach(JavaVM* vm) {
  std::lock_guard<std::mutex> lock(g_attach_mutex);
  if (g_jvmti != nullptr) return "";

  jvmtiEnv* jvmti = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2) != JNI_OK) {
    return "GetEnv(JVMTI_VERSION_1_2) failed";
  }

  jvmtiCapabilities potential;
  memset(&potential, 0, sizeof(potential));
  if (jvmti->GetPotentialCapabilities(&potential) != JVMTI_ERROR_NONE) {
    return "GetPotentialCapabilities failed";
  }
  if (!potential.can_retransform_classes) return "runtime cannot retransform classes";

  jvmtiCapabilities caps;
  memset(&caps, 0, sizeof(caps));
  caps.can_retransform_classes = 1;
  caps.can_retransform_any_class = potential.can_retransform_any_class;
  // Feature 027: finding the app's live objects (open databases, preference stores).
  caps.can_tag_objects = potential.can_tag_objects;
  jvmtiError error = jvmti->AddCapabilities(&caps);
  if (error != JVMTI_ERROR_NONE) return "AddCapabilities: " + std::to_string(error);

  jvmtiEventCallbacks callbacks;
  memset(&callbacks, 0, sizeof(callbacks));
  callbacks.ClassFileLoadHook = OnClassFileLoad;
  error = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
  if (error != JVMTI_ERROR_NONE) return "SetEventCallbacks: " + std::to_string(error);

  // Kept enabled (as Android Studio does on P+) so a retransform by another agent
  // re-applies our hooks instead of silently dropping them.
  error = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK,
                                          nullptr);
  if (error != JVMTI_ERROR_NONE) return "enable ClassFileLoadHook: " + std::to_string(error);

  g_jvmti = jvmti;
  return "";
}

std::string ToString(JNIEnv* env, jstring value) {
  if (value == nullptr) return "";
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (chars == nullptr) return "";
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

}  // namespace

// Why the last attach failed (empty when it did not): read back as the raw detail of the
// "hooks are off" reason (feature 019), so it is not only in the device log.
std::mutex g_attach_error_mutex;
std::string g_attach_error;

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char*, void*) {
  std::string error = Attach(vm);
  {
    std::lock_guard<std::mutex> lock(g_attach_error_mutex);
    g_attach_error = error;
  }
  if (!error.empty()) {
    LOGW("attach failed: %s", error.c_str());
    return JNI_ERR;
  }
  return JNI_OK;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_yamsergey_dta_sidekick_hooks_NativeAgent_nativeAttachError(JNIEnv* env, jclass) {
  std::lock_guard<std::mutex> lock(g_attach_error_mutex);
  return g_attach_error.empty() ? nullptr : env->NewStringUTF(g_attach_error.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_yamsergey_dta_sidekick_hooks_NativeAgent_nativeIsAttached(JNIEnv*, jclass) {
  return g_jvmti != nullptr ? JNI_TRUE : JNI_FALSE;
}

// Returns null on success, otherwise the reason.
extern "C" JNIEXPORT jstring JNICALL
Java_io_yamsergey_dta_sidekick_hooks_NativeAgent_nativeAddToBootstrap(JNIEnv* env, jclass,
                                                                      jstring jar_path) {
  if (g_jvmti == nullptr) return env->NewStringUTF("agent not attached");
  std::string path = ToString(env, jar_path);
  jvmtiError error = g_jvmti->AddToBootstrapClassLoaderSearch(path.c_str());
  if (error != JVMTI_ERROR_NONE) {
    return env->NewStringUTF(("AddToBootstrapClassLoaderSearch: " + JvmtiErrorName(error)).c_str());
  }
  return nullptr;
}

// Whether [target] declares [method][signature] in a form a hook can instrument (feature
// 019): 0 present, 1 absent, 2 abstract, 3 native, 4 the class's methods could not be read.
// Asked before any retransform, so an absent method never reaches ART (which would try to
// redefine the class from its whole original dex and fail with ILLEGAL_ARGUMENT).
extern "C" JNIEXPORT jint JNICALL
Java_io_yamsergey_dta_sidekick_hooks_NativeAgent_nativeMethodState(JNIEnv* env, jclass,
                                                                   jclass target, jstring method,
                                                                   jstring signature) {
  if (g_jvmti == nullptr) return 4;
  std::string want_name = ToString(env, method);
  std::string want_sig = ToString(env, signature);
  jint count = 0;
  jmethodID* methods = nullptr;
  if (g_jvmti->GetClassMethods(target, &count, &methods) != JVMTI_ERROR_NONE) return 4;
  jint state = 1;
  for (jint i = 0; i < count && state == 1; i++) {
    char* name = nullptr;
    char* sig = nullptr;
    if (g_jvmti->GetMethodName(methods[i], &name, &sig, nullptr) != JVMTI_ERROR_NONE) continue;
    if (name != nullptr && sig != nullptr && want_name == name && want_sig == sig) {
      jint modifiers = 0;
      g_jvmti->GetMethodModifiers(methods[i], &modifiers);
      state = (modifiers & 0x0400) != 0 ? 2 : (modifiers & 0x0100) != 0 ? 3 : 0;
    }
    if (name != nullptr) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
    if (sig != nullptr) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(sig));
  }
  g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(methods));
  return state;
}

// Registers one hook on an already-loaded class and retransforms it. Returns null on
// success, otherwise "<our reason>\x1f<runtime detail>" (either part may be empty). On
// failure the hook is unregistered again so the class keeps (or regains) its previous,
// working form.
extern "C" JNIEXPORT jstring JNICALL
Java_io_yamsergey_dta_sidekick_hooks_NativeAgent_nativeHook(JNIEnv* env, jclass,
                                                            jclass target, jstring method,
                                                            jstring signature, jint kind) {
  if (g_jvmti == nullptr) return env->NewStringUTF("agent not attached");

  char* class_sig = nullptr;
  jvmtiError error = g_jvmti->GetClassSignature(target, &class_sig, nullptr);
  if (error != JVMTI_ERROR_NONE || class_sig == nullptr) {
    return env->NewStringUTF(("GetClassSignature: " + JvmtiErrorName(error)).c_str());
  }
  std::string descriptor(class_sig);
  g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(class_sig));

  if (kind < 0 || kind > 2) return env->NewStringUTF("unknown hook kind");
  dta::HookTarget hook{ToString(env, method), ToString(env, signature), static_cast<dta::HookKind>(kind)};
  {
    std::lock_guard<std::mutex> lock(g_targets_mutex);
    g_targets[descriptor].push_back(hook);
  }

  std::string transform_error;
  t_transform_error = &transform_error;
  error = g_jvmti->RetransformClasses(1, &target);
  t_transform_error = nullptr;

  // Our transform's reason comes first: when it fails, ART's own error (if any) is only its
  // consequence. ART's code follows after a unit separator as the raw detail (feature 019).
  std::string reason;
  if (!transform_error.empty()) {
    reason = transform_error;
    if (error != JVMTI_ERROR_NONE) reason += "\x1f" "RetransformClasses: " + JvmtiErrorName(error);
  } else if (error != JVMTI_ERROR_NONE) {
    reason = "\x1f" "RetransformClasses: " + JvmtiErrorName(error);
  }
  if (reason.empty()) return nullptr;

  {
    std::lock_guard<std::mutex> lock(g_targets_mutex);
    auto& list = g_targets[descriptor];
    if (!list.empty()) list.pop_back();
    if (list.empty()) g_targets.erase(descriptor);
  }
  if (!transform_error.empty()) {
    // The failed transform kept the original bytes; re-apply the class's remaining
    // hooks so they stay installed.
    g_jvmti->RetransformClasses(1, &target);
  }
  return env->NewStringUTF(reason.c_str());
}

// Several hooks on one class in one RetransformClasses (each retransform of a large class costs
// tens to hundreds of ms on the calling thread; a second one on the same class costs more than
// the first). Same contract as nativeHook: null on success, otherwise the reason, and on failure
// none of these hooks stays registered.
extern "C" JNIEXPORT jstring JNICALL
Java_io_yamsergey_dta_sidekick_hooks_NativeAgent_nativeHookMany(JNIEnv* env, jclass, jclass target,
                                                                jobjectArray methods,
                                                                jobjectArray signatures,
                                                                jintArray kinds) {
  if (g_jvmti == nullptr) return env->NewStringUTF("agent not attached");
  char* class_sig = nullptr;
  jvmtiError error = g_jvmti->GetClassSignature(target, &class_sig, nullptr);
  if (error != JVMTI_ERROR_NONE || class_sig == nullptr) {
    return env->NewStringUTF(("GetClassSignature: " + JvmtiErrorName(error)).c_str());
  }
  std::string descriptor(class_sig);
  g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(class_sig));

  jsize count = env->GetArrayLength(methods);
  if (count != env->GetArrayLength(signatures) || count != env->GetArrayLength(kinds)) {
    return env->NewStringUTF("mismatched hook lists");
  }
  std::vector<dta::HookTarget> hooks;
  jint* kind_values = env->GetIntArrayElements(kinds, nullptr);
  for (jsize i = 0; i < count; i++) {
    jint kind = kind_values[i];
    if (kind < 0 || kind > 2) {
      env->ReleaseIntArrayElements(kinds, kind_values, JNI_ABORT);
      return env->NewStringUTF("unknown hook kind");
    }
    auto m = static_cast<jstring>(env->GetObjectArrayElement(methods, i));
    auto s = static_cast<jstring>(env->GetObjectArrayElement(signatures, i));
    hooks.push_back(dta::HookTarget{ToString(env, m), ToString(env, s), static_cast<dta::HookKind>(kind)});
    env->DeleteLocalRef(m);
    env->DeleteLocalRef(s);
  }
  env->ReleaseIntArrayElements(kinds, kind_values, JNI_ABORT);
  if (hooks.empty()) return nullptr;
  {
    std::lock_guard<std::mutex> lock(g_targets_mutex);
    auto& list = g_targets[descriptor];
    list.insert(list.end(), hooks.begin(), hooks.end());
  }

  std::string transform_error;
  t_transform_error = &transform_error;
  error = g_jvmti->RetransformClasses(1, &target);
  t_transform_error = nullptr;

  std::string reason;
  if (!transform_error.empty()) {
    reason = transform_error;
    if (error != JVMTI_ERROR_NONE) reason += "\x1f" "RetransformClasses: " + JvmtiErrorName(error);
  } else if (error != JVMTI_ERROR_NONE) {
    reason = "\x1f" "RetransformClasses: " + JvmtiErrorName(error);
  }
  if (reason.empty()) return nullptr;

  {
    std::lock_guard<std::mutex> lock(g_targets_mutex);
    auto& list = g_targets[descriptor];
    for (size_t i = 0; i < hooks.size() && !list.empty(); i++) list.pop_back();
    if (list.empty()) g_targets.erase(descriptor);
  }
  if (!transform_error.empty()) g_jvmti->RetransformClasses(1, &target);
  return env->NewStringUTF(reason.c_str());
}

// Feature 064: takes one hook out of [target] by retransforming it with the class's other
// hooks (ART hands the original image, so with none left the class gets its original code).
// Returns null on success, otherwise the reason; on failure the hook is registered again, as
// the class still carries it.
extern "C" JNIEXPORT jstring JNICALL
Java_io_yamsergey_dta_sidekick_hooks_NativeAgent_nativeUnhook(JNIEnv* env, jclass,
                                                              jclass target, jstring method,
                                                              jstring signature, jint kind) {
  if (g_jvmti == nullptr) return env->NewStringUTF("agent not attached");
  char* class_sig = nullptr;
  jvmtiError error = g_jvmti->GetClassSignature(target, &class_sig, nullptr);
  if (error != JVMTI_ERROR_NONE || class_sig == nullptr) {
    return env->NewStringUTF(("GetClassSignature: " + JvmtiErrorName(error)).c_str());
  }
  std::string descriptor(class_sig);
  g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(class_sig));
  dta::HookTarget hook{ToString(env, method), ToString(env, signature), static_cast<dta::HookKind>(kind)};
  bool found = false;
  {
    std::lock_guard<std::mutex> lock(g_targets_mutex);
    auto it = g_targets.find(descriptor);
    if (it != g_targets.end()) {
      auto& list = it->second;
      for (auto t = list.begin(); t != list.end(); ++t) {
        if (t->method == hook.method && t->signature == hook.signature && t->kind == hook.kind) {
          list.erase(t);
          found = true;
          break;
        }
      }
      if (list.empty()) g_targets.erase(it);
    }
  }
  if (!found) return nullptr;

  std::string transform_error;
  t_transform_error = &transform_error;
  error = g_jvmti->RetransformClasses(1, &target);
  t_transform_error = nullptr;
  if (error == JVMTI_ERROR_NONE && transform_error.empty()) return nullptr;
  {
    std::lock_guard<std::mutex> lock(g_targets_mutex);
    g_targets[descriptor].push_back(hook);
  }
  std::string reason = !transform_error.empty() ? transform_error : "";
  if (error != JVMTI_ERROR_NONE) reason += "\x1f" "RetransformClasses: " + JvmtiErrorName(error);
  return env->NewStringUTF(reason.c_str());
}

// Feature 027 (research R1): the live instances of [target] and its subclasses (interfaces
// included), as Android Studio's App Inspection findInstances does: a fresh tag per call on
// every instance, read back with GetObjectsWithTags, then cleared so tags do not pile up.
// Returns null when the runtime refuses (the reason is logged).
namespace {
std::mutex g_instances_mutex;
jlong g_next_tag = 1;

jint JNICALL TagInstance(jlong, jlong, jlong* tag_ptr, jint, void* user_data) {
  *tag_ptr = *reinterpret_cast<jlong*>(user_data);
  return 0;
}

jvmtiIterationControl JNICALL TagInstanceQ(jlong, jlong, jlong* tag_ptr, void* user_data) {
  *tag_ptr = *reinterpret_cast<jlong*>(user_data);
  return JVMTI_ITERATION_CONTINUE;
}
}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_yamsergey_dta_sidekick_hooks_NativeAgent_nativeInstances(JNIEnv* env, jclass,
                                                                 jclass target) {
  if (g_jvmti == nullptr) return nullptr;
  std::lock_guard<std::mutex> lock(g_instances_mutex);
  jlong tag = g_next_tag++;
  jvmtiError error = JVMTI_ERROR_NONE;
  jboolean is_interface = JNI_FALSE;
  g_jvmti->IsInterface(target, &is_interface);
  if (android_get_device_api_level() >= 29 && !is_interface) {
    error = g_jvmti->IterateOverInstancesOfClass(target, JVMTI_HEAP_OBJECT_EITHER,
                                                TagInstanceQ, &tag);
  } else {
    // Below Q (and for interfaces): IterateThroughHeap filters by exact class, so every loaded
    // class assignable to the target is walked.
    jint count = 0;
    jclass* classes = nullptr;
    error = g_jvmti->GetLoadedClasses(&count, &classes);
    if (error == JVMTI_ERROR_NONE) {
      jvmtiHeapCallbacks callbacks;
      memset(&callbacks, 0, sizeof(callbacks));
      callbacks.heap_iteration_callback = TagInstance;
      for (jint i = 0; i < count && error == JVMTI_ERROR_NONE; i++) {
        jboolean iface = JNI_FALSE;
        g_jvmti->IsInterface(classes[i], &iface);
        if (!iface && env->IsAssignableFrom(classes[i], target)) {
          error = g_jvmti->IterateThroughHeap(0, classes[i], &callbacks, &tag);
        }
      }
      g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
    }
  }
  if (error != JVMTI_ERROR_NONE) {
    LOGW("instances not found: %s", JvmtiErrorName(error).c_str());
    return nullptr;
  }
  jint count = 0;
  jobject* objects = nullptr;
  error = g_jvmti->GetObjectsWithTags(1, &tag, &count, &objects, nullptr);
  if (error != JVMTI_ERROR_NONE) {
    LOGW("tagged instances not read: %s", JvmtiErrorName(error).c_str());
    return nullptr;
  }
  jobjectArray result = env->NewObjectArray(count, env->FindClass("java/lang/Object"), nullptr);
  for (jint i = 0; i < count; i++) {
    g_jvmti->SetTag(objects[i], 0);
    if (result != nullptr) env->SetObjectArrayElement(result, i, objects[i]);
    env->DeleteLocalRef(objects[i]);
  }
  g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(objects));
  return result;
}

// Feature 074: heap snapshot by class. Tags every loaded class with its index (+1), walks the whole
// heap once (IterateThroughHeap, no class filter) summing count and shallow size per class tag, and
// aborts itself past [max_pause_ms] (a stopped walk is never returned as a snapshot). The app's
// threads are suspended for the walk, so this is the pause the answer states.
//
// Result: Object[6] = { Class[] classes (with instances), long[] counts, long[] bytes,
//                       long[] {pauseMicros, objects, untagged, stopped(0/1)}, String error, int[] unused }
namespace {
struct HeapSums {
  std::vector<jlong> count;
  std::vector<jlong> bytes;
  jlong objects = 0;
  jlong untagged = 0;
  bool stopped = false;
  double deadline_ms = 0;
};

double NowMs() {
  timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

jint JNICALL HistogramCallback(jlong class_tag, jlong size, jlong*, jint, void* user_data) {
  auto* sums = static_cast<HeapSums*>(user_data);
  sums->objects++;
  if (class_tag > 0 && static_cast<size_t>(class_tag) < sums->count.size()) {
    sums->count[class_tag]++;
    sums->bytes[class_tag] += size;
  } else {
    sums->untagged++;
  }
  // The clock is read every 4096 objects, not on every one.
  if ((sums->objects & 4095) == 0 && NowMs() > sums->deadline_ms) {
    sums->stopped = true;
    return JVMTI_VISIT_ABORT;
  }
  return 0;
}
}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_yamsergey_dta_sidekick_hooks_NativeAgent_nativeHeapHistogram(JNIEnv* env, jclass,
                                                                     jlong max_pause_ms) {
  jclass object_class = env->FindClass("java/lang/Object");
  jobjectArray result = env->NewObjectArray(5, object_class, nullptr);
  env->DeleteLocalRef(object_class);
  if (result == nullptr) return nullptr;
  auto fail = [&](const std::string& why) {
    jstring s = env->NewStringUTF(why.c_str());
    env->SetObjectArrayElement(result, 4, s);
    env->DeleteLocalRef(s);
    return result;
  };
  if (g_jvmti == nullptr) return fail("agent not attached");

  // Shares the tag space with nativeInstances: no instance search runs during a walk.
  std::lock_guard<std::mutex> lock(g_instances_mutex);
  jint class_count = 0;
  jclass* classes = nullptr;
  jvmtiError error = g_jvmti->GetLoadedClasses(&class_count, &classes);
  if (error != JVMTI_ERROR_NONE) return fail("GetLoadedClasses: " + JvmtiErrorName(error));
  for (jint i = 0; i < class_count; i++) g_jvmti->SetTag(classes[i], i + 1);

  HeapSums sums;
  sums.count.assign(class_count + 1, 0);
  sums.bytes.assign(class_count + 1, 0);
  jvmtiHeapCallbacks callbacks;
  memset(&callbacks, 0, sizeof(callbacks));
  callbacks.heap_iteration_callback = HistogramCallback;
  double start = NowMs();
  sums.deadline_ms = start + static_cast<double>(max_pause_ms);
  error = g_jvmti->IterateThroughHeap(0, nullptr, &callbacks, &sums);
  double elapsed = NowMs() - start;

  // Clear the class tags again so none stay on the app's objects.
  for (jint i = 0; i < class_count; i++) g_jvmti->SetTag(classes[i], 0);

  std::string failure;
  if (error != JVMTI_ERROR_NONE) failure = "IterateThroughHeap: " + JvmtiErrorName(error);

  jlong stats[4] = {static_cast<jlong>(elapsed * 1000), sums.objects, sums.untagged, sums.stopped ? 1 : 0};
  jlongArray stats_array = env->NewLongArray(4);
  env->SetLongArrayRegion(stats_array, 0, 4, stats);
  env->SetObjectArrayElement(result, 3, stats_array);
  env->DeleteLocalRef(stats_array);

  if (failure.empty() && !sums.stopped) {
    jint used = 0;
    for (jint i = 1; i <= class_count; i++) if (sums.count[i] > 0) used++;
    jclass class_class = env->FindClass("java/lang/Class");
    jobjectArray used_classes = env->NewObjectArray(used, class_class, nullptr);
    env->DeleteLocalRef(class_class);
    jlongArray counts = env->NewLongArray(used);
    jlongArray bytes = env->NewLongArray(used);
    if (used_classes != nullptr && counts != nullptr && bytes != nullptr) {
      jint k = 0;
      for (jint i = 1; i <= class_count; i++) {
        if (sums.count[i] == 0) continue;
        env->SetObjectArrayElement(used_classes, k, classes[i - 1]);
        env->SetLongArrayRegion(counts, k, 1, &sums.count[i]);
        env->SetLongArrayRegion(bytes, k, 1, &sums.bytes[i]);
        k++;
      }
      env->SetObjectArrayElement(result, 0, used_classes);
      env->SetObjectArrayElement(result, 1, counts);
      env->SetObjectArrayElement(result, 2, bytes);
    } else {
      failure = "out of memory assembling the histogram";
    }
    if (used_classes != nullptr) env->DeleteLocalRef(used_classes);
    if (counts != nullptr) env->DeleteLocalRef(counts);
    if (bytes != nullptr) env->DeleteLocalRef(bytes);
  }

  // GetLoadedClasses hands out one local reference per class: release them all.
  for (jint i = 0; i < class_count; i++) env->DeleteLocalRef(classes[i]);
  g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
  if (!failure.empty()) return fail(failure);
  return result;
}
