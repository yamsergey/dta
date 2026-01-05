/*
 * Hook Manager - manages registered hook targets
 */

#ifndef HOOK_MANAGER_H
#define HOOK_MANAGER_H

#include <string>
#include <vector>

/**
 * Represents a method hook target.
 */
struct HookTarget {
    std::string className;    // Internal class name (e.g., "okhttp3/OkHttpClient")
    std::string methodName;   // Method name
    std::string methodSig;    // Method signature (may be empty to match any)
    std::string hookId;       // Unique hook identifier
};

/**
 * Manages hook targets registered from Java side.
 */
class HookManager {
public:
    /**
     * Add a hook target.
     */
    static void addHookTarget(
            const char* className,
            const char* methodName,
            const char* methodSig,
            const char* hookId
    );

    /**
     * Remove a hook target.
     */
    static void removeHookTarget(const char* hookId);

    /**
     * Check if there are any hooks for a class.
     */
    static bool hasHooksForClass(const char* className);

    /**
     * Get all hook targets for a class.
     */
    static std::vector<HookTarget> getHooksForClass(const char* className);

    /**
     * Get hook ID for a specific method.
     * Returns empty string if no hook matches.
     */
    static std::string getHookId(
            const char* className,
            const char* methodName,
            const char* methodSig
    );

    /**
     * Clear all hook targets.
     */
    static void clear();
};

#endif // HOOK_MANAGER_H
