package io.yamsergey.dta.sidekick.runtime

import android.app.Activity
import android.content.Context
import io.yamsergey.dta.protocol.data.ActivityInfo
import io.yamsergey.dta.protocol.data.AppFunctionInfo
import io.yamsergey.dta.protocol.data.FragmentInfo
import io.yamsergey.dta.protocol.data.FunctionList
import io.yamsergey.dta.protocol.data.HiltBinding
import io.yamsergey.dta.protocol.data.HiltBindings
import io.yamsergey.dta.protocol.data.MemoryState
import io.yamsergey.dta.protocol.data.NavDestinationInfo
import io.yamsergey.dta.protocol.data.NavEntryInfo
import io.yamsergey.dta.protocol.data.NavHostInfo
import io.yamsergey.dta.protocol.data.NavigationState
import io.yamsergey.dta.protocol.data.PropertyInfo
import io.yamsergey.dta.protocol.data.RuntimeActivities
import io.yamsergey.dta.protocol.data.ThreadInfo
import io.yamsergey.dta.protocol.data.ThreadList
import io.yamsergey.dta.protocol.data.ViewModelInfo
import io.yamsergey.dta.protocol.data.ViewModelList
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import io.yamsergey.dta.sidekick.data.LiveObjects
import io.yamsergey.dta.sidekick.data.Reflect
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Feature 028: what the running process holds. Read on request, written only by explicit calls;
 * the app's objects are found as live objects (agent) or from ActivityThread.
 */
internal class Runtime(private val context: Context) {
    private val loader get() = context.classLoader

    private fun id(o: Any) = Integer.toHexString(System.identityHashCode(o))

    // ---- activities ----

    fun activities(): RuntimeActivities = Main.call {
        RuntimeActivities(android.os.Process.myPid(), android.app.Application.getProcessName(), activityRecords().map { (a, paused, stopped) ->
            val state = lifecycleState(a)
                ?: when { a.isDestroyed -> "destroyed"; stopped -> "stopped"; paused -> "paused"; else -> "resumed" }
            val (fragments, note) = fragments(a)
            ActivityInfo(id(a), a.javaClass.name, state, a.taskId, a.hasWindowFocus(), a.intent?.let { describe(it) }, fragments, note)
        })
    }

    /** An androidx LifecycleOwner's state by reflection (the sidekick does not link androidx.lifecycle). */
    private fun lifecycleState(owner: Any): String? = runCatching {
        (Reflect.call(Reflect.call(owner, "getLifecycle")!!, "getCurrentState") as Enum<*>).name.lowercase()
    }.getOrNull()

    private fun describe(i: android.content.Intent) = listOfNotNull(i.action, i.data?.toString(), i.component?.shortClassName).joinToString(" ")

    @Suppress("UNCHECKED_CAST")
    fun activityRecords(): List<Triple<Activity, Boolean, Boolean>> {
        val at = Class.forName("android.app.ActivityThread")
        val current = at.getMethod("currentActivityThread").invoke(null)
        val map = at.getDeclaredField("mActivities").apply { isAccessible = true }.get(current) as Map<Any, Any>
        return map.values.mapNotNull { r ->
            val a = runCatching { r.javaClass.getDeclaredField("activity").apply { isAccessible = true }.get(r) as Activity? }.getOrNull() ?: return@mapNotNull null
            val paused = runCatching { r.javaClass.getDeclaredField("paused").apply { isAccessible = true }.getBoolean(r) }.getOrDefault(false)
            val stopped = runCatching { r.javaClass.getDeclaredField("stopped").apply { isAccessible = true }.getBoolean(r) }.getOrDefault(false)
            Triple(a, paused, stopped)
        }
    }

    private fun fragments(a: Activity): Pair<List<FragmentInfo>, String?> {
        if (!Values.isA(a.javaClass, "androidx.fragment.app.FragmentActivity")) return emptyList<FragmentInfo>() to null
        return runCatching { manager(Reflect.call(a, "getSupportFragmentManager")!!) to null }
            .getOrElse { emptyList<FragmentInfo>() to "fragments not read: ${it.javaClass.simpleName} ${it.message ?: ""}" }
    }

    private fun manager(fm: Any): List<FragmentInfo> = (Reflect.call(fm, "getFragments") as List<*>).filterNotNull().map { f ->
        val args = (Reflect.call(f, "getArguments") as? android.os.Bundle)?.let { b -> b.keySet().associateWith { k -> @Suppress("DEPRECATION") b.get(k).toString().take(200) } }.orEmpty()
        FragmentInfo(
            f.javaClass.name, Reflect.call(f, "getTag") as String?, Reflect.call(f, "getId") as Int,
            lifecycleState(f) ?: "unknown",
            Reflect.call(f, "isVisible") as Boolean, args,
            runCatching { manager(Reflect.call(f, "getChildFragmentManager")!!) }.getOrDefault(emptyList()),
        )
    }

    // ---- navigation ----

    private fun controllers(): List<Any>? = LiveObjects.of("androidx.navigation.NavController", loader)

    fun navigation(): NavigationState {
        val controllers = controllers()
        val hosts = mutableListOf<NavHostInfo>()
        val failures = mutableListOf<DegradationMarker>()
        controllers?.forEach { c ->
            runCatching { hosts += Main.call { controller(c) } }
                .onFailure { failures += DegradationMarker(NAME, "a NavController (${c.javaClass.simpleName}) was not read: ${it.javaClass.simpleName} ${it.message ?: ""}".trim()) }
        }
        // The app's own stack (keys) over the NavEntry list a NavDisplay overload derives from it.
        val stacks = Nav3Stacks.all().let { all -> all.filterNot { s -> s.any { it?.javaClass?.name == "androidx.navigation3.runtime.NavEntry" } && all.size > 1 } }
        stacks.forEach { stack ->
            hosts += NavHostInfo(id(stack), "navigation3", stack.toList().map { key -> NavEntryInfo(route = keyName(key), key = Values.render(contentKey(key), maxDepth = 3)) })
        }
        val completeness = failures + if (controllers == null) listOf(DegradationMarker(NAME, "NavControllers cannot be found (${LiveObjects.reason}); only Navigation 3 stacks seen by the timeline are listed")) else emptyList()
        val note = if (hosts.isEmpty()) "no navigation host: the app has no NavController alive and no Navigation 3 NavDisplay was shown" else null
        return NavigationState(hosts, note, completeness)
    }

    /** A Navigation 3 stack item: the app's key (a NavEntry gives its key). */
    private fun contentKey(k: Any?): Any? = if (k != null && k.javaClass.name == "androidx.navigation3.runtime.NavEntry") runCatching { Reflect.call(k, "getKey") }.getOrNull() ?: k else k

    private fun keyName(k: Any?): String {
        val key = contentKey(k) ?: return "null"
        if (key is CharSequence || key is Number || key is Enum<*>) return key.toString().take(120)
        val simple = key.javaClass.simpleName.ifEmpty { key.javaClass.name }
        return if (Values.instanceFields(key.javaClass).isEmpty()) simple else runCatching { key.toString() }.getOrDefault(simple).take(120)
    }

    private fun controller(c: Any): NavHostInfo {
        val stack = (runCatching { Reflect.call(Reflect.call(c, "getCurrentBackStack")!!, "getValue") as List<*> }.getOrNull()
            ?: runCatching { (Reflect.call(c, "getBackQueue") as Iterable<*>).toList() }.getOrDefault(emptyList()))
        val entries = stack.filterNotNull().map { e ->
            val d = Reflect.call(e, "getDestination")
            NavEntryInfo(
                Reflect.call(e, "getId") as String?, d?.let { route(it) }, d?.let { Reflect.call(it, "getId") as Int },
                d?.let { Reflect.call(it, "getLabel")?.toString() },
                (Reflect.call(e, "getArguments") as? android.os.Bundle)?.let { b -> b.keySet().filterNot { it.startsWith("android-support-nav") }.associateWith { k -> @Suppress("DEPRECATION") b.get(k).toString() } }.orEmpty(),
                lifecycleState(e),
            )
        }
        val graph = runCatching { Reflect.call(c, "getGraph") }.getOrNull()?.let { destination(it, null) }
        return NavHostInfo(id(c), "navigation", entries, graph)
    }

    private fun route(d: Any) = runCatching { Reflect.call(d, "getRoute") as String? }.getOrNull()

    private fun destination(d: Any, startOfParent: Any?): NavDestinationInfo {
        val isGraph = Values.isA(d.javaClass, "androidx.navigation.NavGraph")
        val children = if (isGraph) (d as Iterable<*>).filterNotNull().let { list ->
            val start = runCatching { Reflect.call(d, "getStartDestinationId") }.getOrNull()
            list.map { destination(it, start) }
        } else emptyList()
        @Suppress("UNCHECKED_CAST")
        val declared = runCatching { (Reflect.call(d, "getArguments") as Map<String, *>).keys.toList() }.getOrDefault(emptyList())
        // Route placeholders are arguments too, declared or not ("product/{id}").
        val args = (declared + Regex("\\{([^\\}?]+)\\??\\}").findAll(route(d) ?: "").map { it.groupValues[1] }).distinct()
        // Navigation 2.9 keeps a destination's deep links in its `impl`; older versions on the destination.
        val links = listOf(d, runCatching { Reflect.field(d, "impl") }.getOrNull()).filterNotNull().firstNotNullOfOrNull { o ->
            (Reflect.field(o, "deepLinks") as? List<*>)?.mapNotNull { l -> l?.let { runCatching { Reflect.call(it, "getUriPattern") as String? }.getOrNull() } }
        }.orEmpty()
        val idValue = Reflect.call(d, "getId") as Int
        return NavDestinationInfo(
            route(d), idValue, Reflect.call(d, "getLabel")?.toString(), Reflect.call(d, "getNavigatorName") as String,
            args, links, startOfParent == idValue, children,
        )
    }

    private fun host(hostId: String?): Any {
        val hosts = (controllers() ?: throw IllegalStateException("NavControllers cannot be found: ${LiveObjects.reason}")).toMutableList<Any>().apply { addAll(Nav3Stacks.all()) }
        return when {
            hostId != null -> hosts.firstOrNull { id(it) == hostId } ?: throw InvalidParamsException("no navigation host $hostId (runtime navigation lists them)")
            hosts.size == 1 -> hosts.single()
            hosts.isEmpty() -> throw InvalidParamsException("the app has no navigation host alive")
            else -> throw InvalidParamsException("the app has ${hosts.size} navigation hosts: give host (${hosts.joinToString { id(it) }})")
        }
    }

    fun navigate(route: String?, uri: String?, hostId: String?): String {
        val c = host(hostId)
        if (c is List<*>) throw InvalidParamsException("a Navigation 3 back stack holds the app's own keys: DTA can go back on it, not create a key")
        Main.call {
            if (uri != null) Reflect.invoke(c.javaClass.methods.first { it.name == "navigate" && it.parameterTypes.contentEquals(arrayOf(android.net.Uri::class.java)) }, c, android.net.Uri.parse(uri))
            else Reflect.invoke(c.javaClass.methods.first { it.name == "navigate" && it.parameterTypes.contentEquals(arrayOf(String::class.java)) }, c, route ?: throw InvalidParamsException("give a route or uri"))
        }
        return "navigated to ${uri ?: route}"
    }

    fun back(hostId: String?): String {
        val c = host(hostId)
        if (c is MutableList<*>) {
            return Main.call {
                if (c.size <= 1) throw InvalidParamsException("the Navigation 3 back stack has one key left: nothing to go back to")
                val removed = try { c.removeAt(c.lastIndex) } catch (_: UnsupportedOperationException) {
                    throw InvalidParamsException("this Navigation 3 back stack is a read-only list: the app owns it")
                }
                "removed ${keyName(removed)}"
            }
        }
        val popped = Main.call { Reflect.call(c, "popBackStack") as Boolean }
        return if (popped) "went back" else "nothing to go back to (the controller is at its start)"
    }

    // ---- ViewModels ----

    private fun viewModelObjects(): List<Any>? = LiveObjects.of("androidx.lifecycle.ViewModel", loader)

    fun viewModels(filter: String?): ViewModelList {
        val vms = viewModelObjects() ?: return ViewModelList(emptyList(), listOf(DegradationMarker(NAME, "ViewModels cannot be found: ${LiveObjects.reason}")))
        val (keys, owners) = owners()
        return ViewModelList(vms.filter { filter == null || it.javaClass.name.contains(filter, ignoreCase = true) }.map { vm ->
            val (props, saved) = properties(vm)
            ViewModelInfo(id(vm), vm.javaClass.name, keys[vm], owners[vm], props, saved)
        })
    }

    /** Each ViewModel's key in its store and who owns the store (activity / back-stack entry / fragment). */
    private fun owners(): Pair<Map<Any, String>, Map<Any, String>> {
        val keys = java.util.IdentityHashMap<Any, String>()
        val owners = java.util.IdentityHashMap<Any, String>()
        val storeOwner = java.util.IdentityHashMap<Any, String>()
        runCatching { activityRecords().forEach { (a, _, _) -> runCatching { storeOwner[Reflect.call(a, "getViewModelStore")!!] = "activity ${a.javaClass.simpleName}" } } }
        LiveObjects.of("androidx.navigation.NavBackStackEntry", loader).orEmpty().forEach { e ->
            runCatching {
                val store = Reflect.call(e, "getViewModelStore")!!
                storeOwner[store] = "back stack entry ${Reflect.call(e, "getDestination")?.let { route(it) } ?: ""}".trim()
            }
        }
        LiveObjects.of("androidx.lifecycle.ViewModelStore", loader).orEmpty().forEach { store ->
            val map = Reflect.fields(store.javaClass).firstOrNull { Map::class.java.isAssignableFrom(it.type) }?.get(store) as? Map<*, *> ?: return@forEach
            map.forEach { (k, vm) -> if (vm != null) { keys[vm] = k.toString(); storeOwner[store]?.let { owners[vm] = it } } }
        }
        return keys to owners
    }

    private class Prop(val name: String, val field: Field, val value: Any?)

    /** The ViewModel's own properties, deduplicated (the public `uiState` over its `_uiState`). */
    private fun props(vm: Any): List<Prop> {
        val fields = generateSequence(vm.javaClass as Class<*>) { it.superclass }
            .takeWhile { !it.name.startsWith("androidx.lifecycle.") && it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .onEach { runCatching { it.isAccessible = true } }.toList()
        val seen = java.util.IdentityHashMap<Any, Prop>()
        val out = mutableListOf<Prop>()
        for (f in fields) {
            val name = f.name.removeSuffix("\$delegate").let { if (it.contains('$')) return@let null else it } ?: continue
            val value = runCatching { f.get(vm) }.getOrNull()
            val prop = Prop(name, f, value)
            val existing = value?.takeIf { it !is Number && it !is String && it !is Boolean }?.let { seen[it] }
            if (existing != null) {
                if (existing.name.startsWith("_") && !name.startsWith("_")) { out[out.indexOf(existing)] = prop; seen[value] = prop }
                continue
            }
            value?.let { seen[it] = prop }
            out += prop
        }
        return out
    }

    private fun properties(vm: Any): Pair<List<PropertyInfo>, List<PropertyInfo>?> {
        var saved: List<PropertyInfo>? = null
        val list = props(vm).mapNotNull { p ->
            val v = p.value
            if (v != null && Values.isA(v.javaClass, "androidx.lifecycle.SavedStateHandle")) { saved = savedState(v); return@mapNotNull null }
            val holder = v?.let { Values.holder(it) }
            if (holder != null) {
                val (kind, inner) = holder
                val editable = Values.isA(v.javaClass, MUTABLE[kind]!!)
                PropertyInfo(p.name, kind, inner?.javaClass?.simpleName ?: "null", Values.render(inner), editable, if (!editable) "read-only ${kind}" else null)
            } else {
                val simple = v == null || v is Number || v is String || v is Boolean || v is Enum<*> || (Values.copyMethod(v.javaClass) != null)
                PropertyInfo(p.name, "field", p.field.type.simpleName, Values.render(v), !Modifier.isFinal(p.field.modifiers) && simple)
            }
        }
        return list to saved
    }

    private fun savedState(handle: Any): List<PropertyInfo> = runCatching {
        (Reflect.call(handle, "keys") as Set<*>).map { k -> PropertyInfo(k.toString(), "savedstate", Reflect.call(handle, "get", k)?.javaClass?.simpleName ?: "null", Values.render(Reflect.call(handle, "get", k)), true) }
    }.getOrDefault(emptyList())

    private fun viewModel(vmId: String): Any = (viewModelObjects() ?: throw IllegalStateException("ViewModels cannot be found: ${LiveObjects.reason}"))
        .firstOrNull { id(it) == vmId } ?: throw InvalidParamsException("no ViewModel $vmId alive (runtime viewmodels lists them)")

    fun setProperty(vmId: String, path: String, value: JsonElement): String {
        val vm = viewModel(vmId)
        val segments = path.split('.')
        val prop = props(vm).firstOrNull { it.name == segments[0] } ?: throw InvalidParamsException("${vm.javaClass.simpleName} has no property ${segments[0]} (it has ${props(vm).joinToString { it.name }})")
        val v = prop.value
        val holder = v?.let { Values.holder(it) }
        fun next(current: Any?, declared: Class<*>): Any? = try {
            if (segments.size == 1) Values.convert(value, current, declared) else Values.with(current ?: throw IllegalArgumentException("${segments[0]} is null: set it whole"), segments.drop(1), value)
        } catch (e: IllegalArgumentException) {
            throw InvalidParamsException("${path}: ${e.message}")
        }
        if (holder != null) {
            val (kind, inner) = holder
            if (!Values.isA(v.javaClass, MUTABLE[kind]!!)) throw InvalidParamsException("${segments[0]} is a read-only $kind: DTA changes only Mutable ones")
            val updated = next(inner, inner?.javaClass ?: String::class.java)
            when (kind) {
                "stateflow" -> Reflect.invoke(v.javaClass.methods.first { it.name == "setValue" && it.parameterTypes.size == 1 }, v, updated)
                else -> Main.call { Reflect.invoke(v.javaClass.methods.first { it.name == "setValue" && it.parameterTypes.size == 1 }, v, updated) }
            }
        } else {
            if (Modifier.isFinal(prop.field.modifiers)) throw InvalidParamsException("${segments[0]} is a val: DTA does not change final fields")
            val updated = next(v, prop.field.type)
            Main.call { prop.field.set(vm, updated) }
        }
        return "${vm.javaClass.simpleName}.$path = $value"
    }

    fun setSaved(vmId: String, key: String, value: JsonElement): String {
        val vm = viewModel(vmId)
        val handle = props(vm).firstOrNull { it.value != null && Values.isA(it.value.javaClass, "androidx.lifecycle.SavedStateHandle") }?.value
            ?: throw InvalidParamsException("${vm.javaClass.simpleName} keeps no SavedStateHandle in a field")
        val current = Reflect.call(handle, "get", key)
        val converted = try {
            if (current != null) Values.convert(value, current, current.javaClass) else natural(value)
        } catch (e: IllegalArgumentException) { throw InvalidParamsException("$key: ${e.message}") }
        Main.call { Reflect.call(handle, "set", key, converted) }
        return "${vm.javaClass.simpleName} saved state $key = $value"
    }

    private fun natural(v: JsonElement): Any? = when {
        v is JsonNull -> null
        v is JsonPrimitive && v.isString -> v.content
        v is JsonPrimitive && v.content == "true" || v is JsonPrimitive && v.content == "false" -> v.content.toBoolean()
        v is JsonPrimitive -> v.content.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: v.content.toDouble()
        else -> throw InvalidParamsException("a new saved-state key takes a string, number, boolean or null")
    }

    // ---- memory, threads ----

    fun memory(gc: Boolean): MemoryState {
        if (gc) { java.lang.Runtime.getRuntime().gc(); System.runFinalization() }
        val r = java.lang.Runtime.getRuntime()
        val mi = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
        return MemoryState(
            r.totalMemory() - r.freeMemory(), r.maxMemory(), r.freeMemory(),
            android.os.Debug.getNativeHeapAllocatedSize(), android.os.Debug.getNativeHeapSize(), mi.totalPss,
            android.os.Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull(), android.os.Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull(), gc,
        )
    }

    fun threads(stacks: Boolean, filter: String?): ThreadList = ThreadList(
        Thread.getAllStackTraces().entries.filter { filter == null || it.key.name.contains(filter, ignoreCase = true) }.sortedBy { it.key.name }.map { (t, st) ->
            ThreadInfo(t.id, t.name, t.state.name.lowercase(), t.isDaemon, t.priority, t.threadGroup?.name, if (stacks) st.map { it.toString() } else emptyList())
        },
    )

    // ---- Hilt ----

    fun hilt(filter: String?): HiltBindings {
        val components = mutableListOf<Pair<String, Any>>()
        val app = context.applicationContext
        fun componentOf(holder: Any): Any? = runCatching {
            val manager = Reflect.call(holder, "componentManager") ?: return@runCatching null
            Reflect.call(manager, "generatedComponent")
        }.getOrNull()
        componentOf(app)?.let { components += "singleton" to it }
        runCatching { activityRecords().forEach { (a, _, _) -> componentOf(a)?.let { components += "activity ${a.javaClass.simpleName}" to it } } }
        if (components.isEmpty()) return HiltBindings(false, emptyList(), "the app does not use Hilt (its Application has no Hilt component manager)")
        val bindings = components.flatMap { (scope, c) ->
            Reflect.fields(c.javaClass).mapNotNull { f ->
                val v = runCatching { f.get(c) }.getOrNull()
                if (v != null && v.javaClass.name.contains("CImpl")) return@mapNotNull null
                val declared = f.genericType.toString().let { g -> Regex("<(.+)>").find(g)?.groupValues?.get(1) ?: f.type.name }
                val instance = when {
                    v == null -> null
                    v.javaClass.name == "dagger.internal.DoubleCheck" -> Reflect.field(v, "instance")?.takeIf { !it.javaClass.name.startsWith("dagger.internal") }
                    v.javaClass.name.contains("Provider") || v.javaClass.name.startsWith("dagger.") -> null
                    else -> v
                }
                HiltBinding("$scope (${c.javaClass.simpleName})", f.name, declared, instance?.javaClass?.name, instance != null)
            }
        }.filter { filter == null || it.declaredType.contains(filter, ignoreCase = true) || (it.runtimeType?.contains(filter, ignoreCase = true) ?: false) }
        return HiltBindings(true, bindings)
    }

    // ---- functions ----

    private fun dtaFunctions(): Map<String, Any> = runCatching {
        @Suppress("UNCHECKED_CAST")
        Class.forName("io.yamsergey.dta.state.DtaStateBridge", true, loader).getField("functions").get(null) as Map<String, Any>
    }.getOrDefault(emptyMap())

    fun functions(): FunctionList {
        val own = dtaFunctions().values.map { f -> AppFunctionInfo(Reflect.field(f, "name") as String, "dta-state", Reflect.field(f, "description") as String, true) }
        val declared = appFunctions()
        val note = when {
            own.isEmpty() && declared.isEmpty() -> "the app registers no functions: add DtaState.function(\"name\") { … } (dta-state) for actions DTA can run"
            else -> null
        }
        return FunctionList(own + declared, note)
    }

    private fun appFunctions(): List<AppFunctionInfo> = listOf("app_functions_v2.xml", "app_functions.xml").firstNotNullOfOrNull { asset ->
        runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }.getOrNull()
    }?.let { xml ->
        Regex("<id>([^<]+)</id>").findAll(xml).map { it.groupValues[1] }.distinct().map { id ->
            AppFunctionInfo(id, "appfunctions", "", false, "an AndroidX AppFunction: listed from the APK; DTA does not invoke AppFunctions yet")
        }.toList()
    }.orEmpty()

    fun invoke(id: String, args: Map<String, String>): JsonElement {
        val f = dtaFunctions()[id] ?: throw InvalidParamsException("no function $id registered with dta-state (runtime functions lists them)")
        val m = f.javaClass.methods.first { it.name == "invoke" && it.parameterTypes.size == 1 }
        return Values.render(Reflect.invoke(m, f, args))
    }

    companion object {
        const val NAME = "runtime"
        private val MUTABLE = mapOf(
            "stateflow" to "kotlinx.coroutines.flow.MutableStateFlow",
            "livedata" to "androidx.lifecycle.MutableLiveData",
            "state" to "androidx.compose.runtime.MutableState",
        )
    }
}
