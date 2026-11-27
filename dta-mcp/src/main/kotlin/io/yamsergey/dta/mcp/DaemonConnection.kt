package io.yamsergey.dta.mcp

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.client.DaemonGoneException
import io.yamsergey.dta.client.DaemonLauncher
import io.yamsergey.dta.client.DaemonStartException
import io.yamsergey.dta.client.VersionConflictException
import io.yamsergey.dta.protocol.ops.Selected
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** One daemon connection shared by all tool calls, opened lazily and re-established when it goes away. */
class DaemonConnection(private val connect: suspend () -> DaemonLauncher.Connected, private val log: (String) -> Unit) {
    private val mutex = Mutex()
    private var client: DaemonClient? = null

    /** Set when the daemon cannot be used by this version at all. */
    @Volatile
    var fatal: String? = null
        private set

    suspend fun call(operation: String, params: JsonObject): Selected = client().call(operation, params)

    /** Feature 073: what this connection tells the daemon about itself, once per connection (the latest of each method). */
    private val announced = LinkedHashMap<String, JsonObject>()

    /**
     * Feature 073: tells the daemon [method] now (connecting when needed) and again after every reconnect.
     * Best effort: a daemon that does not know it, or is gone, is logged and the call goes on.
     */
    suspend fun announce(method: String, params: JsonObject) {
        synchronized(announced) { announced[method] = params }
        try {
            client().request(method, params)
        } catch (e: Exception) {
            log("could not tell the daemon $method: ${e.message}")
        }
    }

    /**
     * Feature 071: `network.search` as a streamed search gathered into its page ([FindCollector]):
     * [progress] hears how many were found after each batch; cancelling the caller stops the search.
     */
    suspend fun find(params: JsonObject, progress: (Int) -> Unit): Selected {
        val limit = (params["limit"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: io.yamsergey.dta.protocol.network.NetworkQuery.DEFAULT_LIMIT
        val collector = io.yamsergey.dta.protocol.network.FindCollector()
        var selection: io.yamsergey.dta.protocol.ops.SelectionStatement? = null
        client().find(JsonObject(params + ("limit" to kotlinx.serialization.json.JsonPrimitive(limit))), window = limit).collect { e ->
            val started = e["selection"] as? JsonObject
            if (started != null) {
                selection = io.yamsergey.dta.protocol.rpc.DtaJson.decodeFromJsonElement(Selected.serializer(), started).selection
                return@collect
            }
            val event = io.yamsergey.dta.protocol.rpc.DtaJson.decodeFromJsonElement(io.yamsergey.dta.protocol.network.FoundEvent.serializer(), e)
            collector.take(event)
            if (!event.items.isNullOrEmpty()) progress(collector.found)
        }
        collector.final?.takeIf { it.state == io.yamsergey.dta.protocol.network.FindState.FAILED }?.let {
            throw io.yamsergey.dta.client.DaemonCallException(io.yamsergey.dta.protocol.rpc.ErrorCodes.INTERNAL, it.error ?: "the search failed")
        }
        return Selected(selection, collector.json())
    }

    private suspend fun client(): DaemonClient {
        val current = mutex.withLock {
            client?.takeIf { it.goneReason == null } ?: try {
                connect().also { c -> c.statements.forEach(log) }.client.also { client = it; tell(it) }
            } catch (e: VersionConflictException) {
                fatal = e.message
                throw DaemonGoneException(e.message!!)
            } catch (e: DaemonStartException) {
                throw DaemonGoneException(e.message!!)
            }
        }
        return current
    }

    /** Feature 073: after a (re)connect, what the daemon should know of this connection. */
    private suspend fun tell(connected: DaemonClient) {
        val all = synchronized(announced) { announced.toMap() }
        all.forEach { (method, params) -> runCatching { connected.request(method, params) } }
    }

    suspend fun close() = mutex.withLock { client?.let { runCatching { it.close() } }; client = null }
}
