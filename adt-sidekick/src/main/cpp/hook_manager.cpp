/*
 * Hook Manager implementation
 */

#include "hook_manager.h"
#include <android/log.h>
#include <mutex>
#include <unordered_map>
#include <unordered_set>

#define LOG_TAG "HookManager"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Storage for hook targets
static std::mutex g_hookMutex;
static std::vector<HookTarget> g_hookTargets;
static std::unordered_set<std::string> g_hookedClasses;
static std::unordered_map<std::string, std::string> g_hookIdMap;  // class#method#sig -> hookId

void HookManager::addHookTarget(
        const char* className,
        const char* methodName,
        const char* methodSig,
        const char* hookId) {

    std::lock_guard<std::mutex> lock(g_hookMutex);

    HookTarget target;
    target.className = className ? className : "";
    target.methodName = methodName ? methodName : "";
    target.methodSig = methodSig ? methodSig : "";
    target.hookId = hookId ? hookId : "";

    g_hookTargets.push_back(target);
    g_hookedClasses.insert(target.className);

    // Build lookup key
    std::string key = target.className + "#" + target.methodName;
    if (!target.methodSig.empty()) {
        key += "#" + target.methodSig;
    }
    g_hookIdMap[key] = target.hookId;

    LOGD("Added hook target: %s.%s%s -> %s",
         className, methodName, methodSig ? methodSig : "", hookId);
}

void HookManager::removeHookTarget(const char* hookId) {
    std::lock_guard<std::mutex> lock(g_hookMutex);

    std::string id = hookId ? hookId : "";

    auto it = g_hookTargets.begin();
    while (it != g_hookTargets.end()) {
        if (it->hookId == id) {
            // Remove from lookup map
            std::string key = it->className + "#" + it->methodName;
            if (!it->methodSig.empty()) {
                key += "#" + it->methodSig;
            }
            g_hookIdMap.erase(key);

            it = g_hookTargets.erase(it);
        } else {
            ++it;
        }
    }

    // Rebuild hooked classes set
    g_hookedClasses.clear();
    for (const auto& target : g_hookTargets) {
        g_hookedClasses.insert(target.className);
    }
}

bool HookManager::hasHooksForClass(const char* className) {
    std::lock_guard<std::mutex> lock(g_hookMutex);
    return g_hookedClasses.find(className) != g_hookedClasses.end();
}

std::vector<HookTarget> HookManager::getHooksForClass(const char* className) {
    std::lock_guard<std::mutex> lock(g_hookMutex);

    std::vector<HookTarget> result;
    for (const auto& target : g_hookTargets) {
        if (target.className == className) {
            result.push_back(target);
        }
    }
    return result;
}

std::string HookManager::getHookId(
        const char* className,
        const char* methodName,
        const char* methodSig) {

    std::lock_guard<std::mutex> lock(g_hookMutex);

    // Try exact match first
    std::string key = std::string(className) + "#" + methodName;
    if (methodSig && methodSig[0] != '\0') {
        std::string keyWithSig = key + "#" + methodSig;
        auto it = g_hookIdMap.find(keyWithSig);
        if (it != g_hookIdMap.end()) {
            return it->second;
        }
    }

    // Try without signature (matches any)
    auto it = g_hookIdMap.find(key);
    if (it != g_hookIdMap.end()) {
        return it->second;
    }

    // Search through all targets for partial matches
    for (const auto& target : g_hookTargets) {
        if (target.className == className && target.methodName == methodName) {
            if (target.methodSig.empty()) {
                // Matches any signature
                return target.hookId;
            }
            if (methodSig && target.methodSig == methodSig) {
                return target.hookId;
            }
        }
    }

    return "";
}

void HookManager::clear() {
    std::lock_guard<std::mutex> lock(g_hookMutex);
    g_hookTargets.clear();
    g_hookedClasses.clear();
    g_hookIdMap.clear();
}
