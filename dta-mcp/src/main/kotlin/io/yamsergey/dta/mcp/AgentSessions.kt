package io.yamsergey.dta.mcp

import io.yamsergey.dta.protocol.ops.ClientName
import io.yamsergey.dta.protocol.ops.ClientSession
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Feature 073 A2: names an MCP client from its `initialize` message. */
object ClientNaming {
    const val UNNAMED = "unnamed client"

    /** [line] is one JSON-RPC message; the client's name when it is `initialize`, else null. A missing clientInfo is [UNNAMED]. */
    fun fromInitialize(line: String): ClientName? {
        val o = runCatching { DtaJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        if ((o["method"] as? JsonPrimitive)?.content != "initialize") return null
        val info = (o["params"] as? JsonObject)?.get("clientInfo") as? JsonObject
        val name = (info?.get("name") as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return ClientName(UNNAMED)
        return ClientName(name, (info["version"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() })
    }
}

/**
 * Feature 073 A2: the agent sessions of the plugin's HTTP server. A session starts with its `initialize`, ends with
 * its DELETE, and goes quiet (not listed, remembered) after [idleMs] without a request; any request brings it back.
 */
class SessionRegistry(private val idleMs: Long = 30 * 60_000L, private val clock: () -> Long = System::currentTimeMillis) {
    private class Record(val name: ClientName, val since: Long, var lastSeen: Long)

    private val records = LinkedHashMap<String, Record>()

    @Synchronized
    fun start(id: String, name: ClientName) {
        val now = clock()
        records[id] = Record(name, now, now)
    }

    /** True when [id] was a session that is now ended. */
    @Synchronized
    fun end(id: String): Boolean = records.remove(id) != null

    /** A request of [id]; true when it is a known session. */
    @Synchronized
    fun touch(id: String): Boolean = records[id]?.also { it.lastSeen = clock() } != null

    /** The listed sessions, oldest first. */
    @Synchronized
    fun snapshot(): List<ClientSession> {
        val now = clock()
        return records.values.filter { now - it.lastSeen < idleMs }.sortedBy { it.since }.map { ClientSession(it.name.name, it.name.version, it.since) }
    }
}
