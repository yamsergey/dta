package io.yamsergey.dta.sidekick.network.script

import io.yamsergey.dta.protocol.network.ScriptClearResult
import io.yamsergey.dta.protocol.network.ScriptLogPage
import io.yamsergey.dta.protocol.network.ScriptStatus
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import io.yamsergey.dta.protocol.rpc.MethodRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * `network_script_set`, `_clear`, `_status`, `_logs` (contract §1–§4). Installing is an
 * explicit client action (constitution v1.4.0); the script is persisted so it applies
 * from the next process start until cleared.
 *
 * @param unavailable why intervention cannot apply right now (hooks not attached), or null
 */
class NetworkScriptMethods(
    private val runtime: ScriptRuntime,
    private val store: ScriptStore,
    private val unavailable: () -> String?,
) {
    fun install(registry: MethodRegistry) {
        registry.register("network_script_set") { ctx -> DtaJson.encodeToJsonElement(ScriptStatus.serializer(), set(ctx.params)) }
        registry.register("network_script_clear") { DtaJson.encodeToJsonElement(ScriptClearResult.serializer(), clear()) }
        registry.register("network_script_status") { DtaJson.encodeToJsonElement(ScriptStatus.serializer(), status()) }
        registry.register("network_script_logs") { ctx -> DtaJson.encodeToJsonElement(ScriptLogPage.serializer(), logs(ctx.params)) }
    }

    fun set(params: JsonObject?): ScriptStatus {
        val source = (params?.get("source") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw InvalidParamsException("network_script_set requires a string 'source'")
        if (source.length > MAX_SOURCE_CHARS) throw InvalidParamsException("script is ${source.length} characters; the limit is $MAX_SOURCE_CHARS")
        unavailable()?.let { throw IllegalStateException("network intervention unavailable: $it") }
        val status = try {
            runtime.install(source)
        } catch (e: ScriptCompileException) {
            throw InvalidParamsException("script does not compile: ${e.message}")
        }
        val persisted = store.save(source, status.installedAtMs ?: 0)
        runtime.markPersisted(persisted)
        return runtime.status(withSource = false)
    }

    fun clear(): ScriptClearResult {
        store.clear()
        return ScriptClearResult(cleared = runtime.clear())
    }

    fun status(): ScriptStatus = runtime.status(withSource = true)

    fun logs(params: JsonObject?): ScriptLogPage =
        runtime.logs((params?.get("since") as? JsonPrimitive)?.longOrNull ?: 0)

    companion object {
        const val MAX_SOURCE_CHARS = 1_000_000
    }
}
