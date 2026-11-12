package io.yamsergey.dta.sidekick.util

import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Feature 062: a class's public no-argument method by name, looked up once per class.
 * `Class.getMethods()` copies every public method each time it is called; done per layout node
 * or per parameter during a capture on the main thread, that copying was most of the capture.
 */
object NoArgMethods {
    private val cache = ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, Any>>()
    private val NONE = Any()

    /** Times a class's methods were actually scanned (tests). */
    @Volatile var scans = 0
        private set

    fun of(type: Class<*>, name: String): Method? {
        val byName = cache.getOrPut(type) { ConcurrentHashMap() }
        val found = byName.getOrPut(name) {
            scans++
            type.methods.firstOrNull { it.name == name && it.parameterCount == 0 } ?: NONE
        }
        return found as? Method
    }

    /** [target]'s no-argument [name] called, or null when it has none. */
    fun call(target: Any, name: String): Any? = of(target.javaClass, name)?.invoke(target)
}
