package io.yamsergey.dta.protocol.data

import io.yamsergey.dta.protocol.rpc.DegradationMarker
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Feature 028: what the running process holds — activities, navigation, ViewModels, memory, threads, Hilt, functions. */

@Serializable
data class FragmentInfo(
    val className: String,
    val tag: String? = null,
    val id: Int = 0,
    val state: String,
    val visible: Boolean = false,
    val arguments: Map<String, String> = emptyMap(),
    val children: List<FragmentInfo> = emptyList(),
)

@Serializable
data class ActivityInfo(
    val id: String,
    val className: String,
    /** created, started, resumed, paused, stopped, destroyed. */
    val state: String,
    val task: Int,
    val focused: Boolean = false,
    val intent: String? = null,
    val fragments: List<FragmentInfo> = emptyList(),
    val fragmentsNote: String? = null,
)

@Serializable
data class RuntimeActivities(val pid: Int, val process: String, val activities: List<ActivityInfo>)

@Serializable
data class NavEntryInfo(
    val id: String? = null,
    val route: String? = null,
    val destinationId: Int? = null,
    val label: String? = null,
    val arguments: Map<String, String> = emptyMap(),
    val lifecycle: String? = null,
    /** Navigation 3: the key, rendered. */
    val key: JsonElement? = null,
)

@Serializable
data class NavDestinationInfo(
    val route: String? = null,
    val id: Int = 0,
    val label: String? = null,
    /** composable, fragment, activity, dialog, navigation (a nested graph), … (the navigator's name). */
    val navigator: String,
    val arguments: List<String> = emptyList(),
    val deepLinks: List<String> = emptyList(),
    val start: Boolean = false,
    val children: List<NavDestinationInfo> = emptyList(),
)

@Serializable
data class NavHostInfo(
    val id: String,
    /** navigation (a NavController) or navigation3 (a NavDisplay back stack). */
    val kind: String,
    val backStack: List<NavEntryInfo>,
    val graph: NavDestinationInfo? = null,
)

@Serializable
data class NavigationState(val hosts: List<NavHostInfo>, val note: String? = null, val completeness: List<DegradationMarker> = emptyList())

@Serializable
data class PropertyInfo(
    /** The path to give runtime.vm.set (`state`, `state.coupon`). */
    val path: String,
    /** stateflow, livedata, state, field, savedstate. */
    val kind: String,
    val type: String,
    val value: JsonElement,
    val editable: Boolean,
    val note: String? = null,
)

@Serializable
data class ViewModelInfo(
    val id: String,
    val className: String,
    val key: String? = null,
    val owner: String? = null,
    val properties: List<PropertyInfo> = emptyList(),
    val savedState: List<PropertyInfo>? = null,
)

@Serializable
data class ViewModelList(val viewModels: List<ViewModelInfo>, val completeness: List<DegradationMarker> = emptyList())

@Serializable
data class MemoryState(
    val javaUsedBytes: Long,
    val javaMaxBytes: Long,
    val javaFreeBytes: Long,
    val nativeAllocatedBytes: Long,
    val nativeHeapBytes: Long,
    val pssKb: Int? = null,
    val gcCount: Long? = null,
    val gcTimeMs: Long? = null,
    val gcRun: Boolean = false,
)

@Serializable
data class ThreadInfo(
    val id: Long,
    val name: String,
    val state: String,
    val daemon: Boolean,
    val priority: Int,
    val group: String? = null,
    val stack: List<String> = emptyList(),
)

@Serializable
data class ThreadList(val threads: List<ThreadInfo>)

@Serializable
data class HiltBinding(
    val component: String,
    val name: String,
    val declaredType: String,
    val runtimeType: String? = null,
    val created: Boolean = false,
)

@Serializable
data class HiltBindings(val present: Boolean, val bindings: List<HiltBinding>, val note: String? = null)

@Serializable
data class AppFunctionInfo(
    val id: String,
    /** dta-state or appfunctions. */
    val source: String,
    val description: String = "",
    val invocable: Boolean,
    val note: String? = null,
)

@Serializable
data class FunctionList(val functions: List<AppFunctionInfo>, val note: String? = null)

@Serializable
data class InvokeResult(val id: String, val result: JsonElement? = null, val error: String? = null, val timelineEvent: String? = null)
