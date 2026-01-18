/*
 * Sidekick Logging Utilities
 *
 * Provides conditional logging macros that can be enabled/disabled at runtime.
 * Debug logging is disabled by default to avoid polluting logcat.
 */

#ifndef SIDEKICK_LOG_H
#define SIDEKICK_LOG_H

#include <android/log.h>

// Global debug flag - set via JNI from Java
extern bool g_sidekick_debug_enabled;

// Set debug logging enabled/disabled
void sidekick_set_debug_enabled(bool enabled);

// Check if debug logging is enabled
bool sidekick_is_debug_enabled();

// Conditional logging macros
// LOGE (error) is always enabled
// LOGI, LOGW, LOGD are only logged when debug is enabled

#define SIDEKICK_LOGE(tag, ...) __android_log_print(ANDROID_LOG_ERROR, tag, __VA_ARGS__)

#define SIDEKICK_LOGI(tag, ...) \
    do { \
        if (g_sidekick_debug_enabled) { \
            __android_log_print(ANDROID_LOG_INFO, tag, __VA_ARGS__); \
        } \
    } while(0)

#define SIDEKICK_LOGW(tag, ...) \
    do { \
        if (g_sidekick_debug_enabled) { \
            __android_log_print(ANDROID_LOG_WARN, tag, __VA_ARGS__); \
        } \
    } while(0)

#define SIDEKICK_LOGD(tag, ...) \
    do { \
        if (g_sidekick_debug_enabled) { \
            __android_log_print(ANDROID_LOG_DEBUG, tag, __VA_ARGS__); \
        } \
    } while(0)

#endif // SIDEKICK_LOG_H
