package io.yamsergey.dta.studio.agents

import io.yamsergey.dta.client.DaemonClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Feature 072 (FR-003, FR-015): the setting "mask secrets in what agents read and in exports" — the daemon's
 * `agents.redaction`, which only the plugin's own connection may change. Nothing is kept here: the daemon holds it.
 */
class AgentRedactionController(
    private val client: () -> DaemonClient?,
    private val scope: CoroutineScope,
    /** How long a call waits for the connection to be there (the page may open before the daemon connects). */
    private val waitMs: Long = 10_000,
) {
    /** [on] null until the daemon has said; [error] the last thing that went wrong, cleared by the next success. */
    data class State(val on: Boolean? = null, val error: String? = null)

    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable

    fun refresh() = run { c -> c.call("agents.redaction") }

    fun set(on: Boolean) = run { c -> c.call("agents.redaction.set", JsonObject(mapOf("redaction" to JsonPrimitive(on)))) }

    private fun run(call: suspend (DaemonClient) -> io.yamsergey.dta.protocol.ops.Selected) {
        scope.launch {
            var c = client()
            val until = System.currentTimeMillis() + waitMs
            while (c == null && System.currentTimeMillis() < until) { kotlinx.coroutines.delay(200); c = client() }
            if (c == null) { mutable.value = mutable.value.copy(error = "No DTA daemon connected: the setting is read and changed there."); return@launch }
            mutable.value = runCatching { call(c).result.jsonObject["redaction"]?.jsonPrimitive?.booleanOrNull }.fold(
                { State(on = it ?: mutable.value.on) },
                { mutable.value.copy(error = it.message ?: it.javaClass.simpleName) },
            )
        }
    }
}
