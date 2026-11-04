package io.yamsergey.dta.sidekick.runtime

import android.content.Context
import io.yamsergey.dta.protocol.data.FunctionList
import io.yamsergey.dta.protocol.data.DiReport
import io.yamsergey.dta.protocol.data.InvokeResult
import io.yamsergey.dta.protocol.data.MemoryState
import io.yamsergey.dta.protocol.data.NavigationState
import io.yamsergey.dta.protocol.data.RuntimeActivities
import io.yamsergey.dta.protocol.data.ThreadList
import io.yamsergey.dta.protocol.data.ViewModelList
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import io.yamsergey.dta.protocol.rpc.MethodRegistry
import io.yamsergey.dta.protocol.rpc.RpcContext
import io.yamsergey.dta.sidekick.data.DataWrites
import io.yamsergey.dta.sidekick.data.LiveObjects
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Feature 028: the sidekick's `runtime_*` methods; reads on request, writes only when asked. */
internal class RuntimeMethods(context: Context) {
    private val runtime = Runtime(context)

    fun install(registry: MethodRegistry) = with(registry) {
        register("runtime_activities") { _ -> encode(RuntimeActivities.serializer(), runtime.activities()) }
        register("runtime_navigation") { _ -> encode(NavigationState.serializer(), runtime.navigation()) }
        register("runtime_navigate") { c -> write("navigation", runtime.navigate(c.str("route"), c.str("uri"), c.str("host")), "navigate") }
        register("runtime_back") { c -> write("navigation", runtime.back(c.str("host")), "back") }
        register("runtime_viewmodels") { c -> encode(ViewModelList.serializer(), runtime.viewModels(c.str("filter"))) }
        register("runtime_vm_set") { c -> write("viewmodel ${c.required("viewModel")}", runtime.setProperty(c.required("viewModel"), c.required("path"), c.json("value")), "vm.set") }
        register("runtime_saved_set") { c -> write("viewmodel ${c.required("viewModel")}", runtime.setSaved(c.required("viewModel"), c.required("key"), c.json("value")), "saved.set") }
        register("runtime_memory") { c ->
            val gc = c.bool("gc") ?: false
            val state = runtime.memory(gc)
            if (gc) DataWrites.record("memory", "garbage collection run", "gc", DataWrites.RUNTIME_KIND)
            encode(MemoryState.serializer(), state)
        }
        register("runtime_threads") { c -> encode(ThreadList.serializer(), runtime.threads(c.bool("stacks") ?: true, c.str("filter"))) }
        register("runtime_di") { c -> encode(DiReport.serializer(), runtime.di(c.str("filter"))) }
        register("runtime_functions") { _ -> encode(FunctionList.serializer(), runtime.functions()) }
        register("runtime_invoke") { c ->
            val id = c.required("id")
            @Suppress("UNCHECKED_CAST")
            val args = (c.params?.get("args") as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content }.orEmpty()
            val result = runCatching { runtime.invoke(id, args) }
            result.exceptionOrNull()?.let { if (it is InvalidParamsException) throw it }
            val event = DataWrites.record("function $id", if (result.isSuccess) "invoked" else "invoked, threw ${result.exceptionOrNull()!!.javaClass.simpleName}", "invoke", DataWrites.RUNTIME_KIND)
            encode(InvokeResult.serializer(), InvokeResult(id, result.getOrNull(), result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message ?: ""}".trimEnd(':', ' ') }, event))
        }
    }

    private fun write(target: String, change: String, op: String): JsonElement = buildJsonObject {
        put("store", target)
        put("change", change)
        put("access", "live")
        DataWrites.record(target, change, op, DataWrites.RUNTIME_KIND)?.let { put("timelineEvent", it) }
    }

    private fun <T> encode(s: KSerializer<T>, v: T): JsonElement = DtaJson.encodeToJsonElement(s, v)
    private fun RpcContext.str(name: String) = (params?.get(name) as? JsonPrimitive)?.content
    private fun RpcContext.bool(name: String) = (params?.get(name) as? JsonPrimitive)?.booleanOrNull
    private fun RpcContext.required(name: String) = str(name) ?: throw InvalidParamsException("missing $name")
    private fun RpcContext.json(name: String): JsonElement = try {
        Json.parseToJsonElement(required(name))
    } catch (e: kotlinx.serialization.SerializationException) {
        throw InvalidParamsException("$name is JSON: a string in quotes (\"text\"), a number, true/false, null or an object; got ${str(name)}")
    }

    companion object {
        fun capability(): CapabilityStatus =
            if (LiveObjects.available) CapabilityStatus(Runtime.NAME, CapabilityStatus.Status.AVAILABLE)
            else CapabilityStatus(Runtime.NAME, CapabilityStatus.Status.DEGRADED, "activities, memory and threads only: navigation hosts, ViewModels and their state cannot be found (${LiveObjects.reason})")
    }
}
