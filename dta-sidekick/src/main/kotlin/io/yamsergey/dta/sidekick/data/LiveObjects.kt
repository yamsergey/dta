package io.yamsergey.dta.sidekick.data

import io.yamsergey.dta.sidekick.hooks.HookAttach
import io.yamsergey.dta.sidekick.hooks.NativeAgent

/**
 * Feature 027 (research R1): the app's live objects of a class — open databases, preference and
 * DataStore instances — found on the heap by the JVMTI agent, as Android Studio's App Inspection
 * does. Null when the agent is not attached (the caller falls back to files and says so); empty
 * when the app does not ship the class.
 */
internal object LiveObjects {
    val available: Boolean get() = HookAttach.attached

    fun of(className: String, loader: ClassLoader?): List<Any>? {
        if (!HookAttach.attached) return null
        val type = try {
            Class.forName(className, false, loader)
        } catch (_: ClassNotFoundException) {
            return emptyList()
        } catch (_: LinkageError) {
            return emptyList()
        }
        return of(type)
    }

    fun of(type: Class<*>): List<Any>? {
        if (!HookAttach.attached) return null
        return NativeAgent.nativeInstances(type)?.filterNotNull()
    }

    /** Why live objects are not reachable, for access notes. */
    val reason: String get() = HookAttach.reason ?: "the DTA agent is not attached"
}
