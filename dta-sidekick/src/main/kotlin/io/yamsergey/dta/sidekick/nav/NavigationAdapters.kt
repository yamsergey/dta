package io.yamsergey.dta.sidekick.nav

import android.os.Bundle
import io.yamsergey.dta.protocol.timeline.SourceStatus
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.ui.SourceState
import io.yamsergey.dta.shim.EntryHandler
import io.yamsergey.dta.sidekick.hooks.Hooks
import java.lang.reflect.Method

/**
 * Navigation adapters (feature 016 US5, research R9, prototype findings b). AndroidX
 * Navigation: an ENTRY hook where the controller tells its listeners the destination changed
 * (2.8: `NavController#dispatchOnDestinationChanged`; 2.9+: `NavControllerImpl#dispatchOnDestinationChanged$…`,
 * whose suffix is the module name and differs between versions, so it is found by its prefix),
 * then only public getters. Navigation 3: ENTRY hooks on every `NavDisplay` overload that takes
 * the back stack as a list; the top key is read on each composition and reported when it changes.
 */
class NavigationAdapters(private val events: NavigationEvents) {
    private val notes = ArrayList<String>()
    private var navigation = false
    private var navigation3 = false

    fun install(loader: ClassLoader?) {
        navigation = installNavigation(loader)
        navigation3 = installNavigation3(loader)
    }

    fun status(): SourceStatus = when {
        !navigation && !navigation3 && notes.isEmpty() -> SourceStatus(Sources.NAV, SourceState.OFF, "not used by the app (no AndroidX Navigation, no Navigation 3)")
        !navigation && !navigation3 -> SourceStatus(Sources.NAV, SourceState.OFF, notes.joinToString("; "))
        notes.isNotEmpty() -> SourceStatus(Sources.NAV, SourceState.DEGRADED, notes.joinToString("; "), detail())
        else -> SourceStatus(Sources.NAV, SourceState.ON, detail = detail())
    }

    private fun detail() = listOfNotNull("AndroidX Navigation".takeIf { navigation }, "Navigation 3".takeIf { navigation3 }).joinToString(" and ")

    private fun installNavigation(loader: ClassLoader?): Boolean {
        val impl = load("androidx.navigation.internal.NavControllerImpl", loader)
        val controller = load("androidx.navigation.NavController", loader) ?: return false
        val cls = impl ?: controller
        val method = cls.declaredMethods.firstOrNull {
            it.name.startsWith("dispatchOnDestinationChanged") && it.parameterTypes.isEmpty() && it.returnType == Boolean::class.javaPrimitiveType
        }?.name
        if (method == null) {
            notes += "AndroidX Navigation is not read: ${cls.simpleName} of this version has no dispatchOnDestinationChanged for DTA to observe; a newer DTA may cover it"
            return false
        }
        // The back queue is current when listeners are told; the public back stack flow is not yet.
        val queue = cls.declaredMethods.firstOrNull { it.name.startsWith("getBackQueue") && it.parameterTypes.isEmpty() }?.apply { isAccessible = true }
        val result = Hooks.entry(cls, method, "()Z", EntryHandler { self, _ ->
            runCatching {
                val c = if (impl != null) call(self, "getNavController") else self
                val depth = queue?.let { q -> (q.invoke(self) as? Collection<*>)?.size }
                destination(c ?: return@runCatching, depth)
            }
        })
        return installed("AndroidX Navigation", result)
    }

    private fun destination(controller: Any, queueDepth: Int?) {
        val entry = call(controller, "getCurrentBackStackEntry") ?: return
        val destination = call(entry, "getDestination") ?: return
        val name = (call(destination, "getRoute") as? String) ?: (call(destination, "getDisplayName") as? String) ?: return
        val bundle = call(entry, "getArguments") as? Bundle
        val args = bundle?.keySet().orEmpty().filterNot { it.startsWith("android-support-nav") }.associateWith { key ->
            @Suppress("DEPRECATION")
            when (val v = bundle!!.get(key)) {
                null -> "null"
                is String, is Number, is Boolean, is Char -> v.toString()
                else -> v.javaClass.simpleName
            }
        }
        val depth = queueDepth ?: (call(call(controller, "getCurrentBackStack") ?: return, "getValue") as? List<*>)?.size
        events.destination(NavigationEvents.LIBRARY, name, args, depth)
    }

    private fun installNavigation3(loader: ClassLoader?): Boolean {
        val display = load("androidx.navigation3.ui.NavDisplayKt", loader) ?: return false
        val overloads = display.declaredMethods.filter { it.name == "NavDisplay" && it.parameterTypes.firstOrNull() == List::class.java }
        if (overloads.isEmpty()) {
            notes += "Navigation 3 is not read: this version has no NavDisplay(backStack: List) for DTA to observe; a newer DTA may cover it"
            return false
        }
        val results = overloads.map { m ->
            Hooks.entry(display, m.name, descriptor(m), EntryHandler { _, params -> runCatching { top(params[0] as? List<*>) } })
        }
        return results.any { it is Hooks.Install.Installed }.also { ok -> if (!ok) installed("Navigation 3", results.first()) }
    }

    private fun top(stack: List<*>?) {
        stack?.let { io.yamsergey.dta.sidekick.runtime.Nav3Stacks.seen(it) }
        val last = stack?.lastOrNull() ?: return
        val key = if (last.javaClass.name == "androidx.navigation3.runtime.NavEntry") call(last, "getContentKey") ?: last else last
        events.key(NavigationEvents.keyName(key, key.javaClass.simpleName.ifEmpty { key.javaClass.name }), stack.size)
    }

    private fun installed(what: String, result: Hooks.Install): Boolean = when (result) {
        is Hooks.Install.Installed -> true
        is Hooks.Install.ClassAbsent -> false
        else -> false.also { notes += "$what is not read: ${result.describe()}" }
    }

    private fun load(name: String, loader: ClassLoader?): Class<*>? = runCatching { Class.forName(name, false, loader) }.getOrNull()

    private fun call(target: Any?, name: String): Any? = target?.javaClass?.getMethod(name)?.invoke(target)

    private fun descriptor(m: Method): String =
        m.parameterTypes.joinToString("", "(", ")") { type(it) } + type(m.returnType)

    private fun type(c: Class<*>): String = when {
        c == Void.TYPE -> "V"
        c == Int::class.javaPrimitiveType -> "I"
        c == Long::class.javaPrimitiveType -> "J"
        c == Boolean::class.javaPrimitiveType -> "Z"
        c == Float::class.javaPrimitiveType -> "F"
        c == Double::class.javaPrimitiveType -> "D"
        c == Byte::class.javaPrimitiveType -> "B"
        c == Char::class.javaPrimitiveType -> "C"
        c == Short::class.javaPrimitiveType -> "S"
        c.isArray -> "[" + type(c.componentType!!)
        else -> "L" + c.name.replace('.', '/') + ";"
    }
}
