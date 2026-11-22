package io.yamsergey.dta.mcp

import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Feature 071 (FR-008, research R10): an agent's `notifications/cancelled` stops the tool call it names.
 * The MCP SDK (2.0.1) has no server hook for it, so the transport shows us each line the client sends
 * ([observe]): `tools/call` requests are noted by JSON-RPC id, and a running call is matched to its id
 * by name and arguments when it starts ([claim]). A cancel then cancels that call's job.
 */
class McpCancels(
    /** The transport has sessions (HTTP): ids are per session. */
    val bySession: Boolean = false,
) {
    private class Noted(val id: String, val session: String?, val name: String, val arguments: JsonElement)

    private val noted = ArrayList<Noted>()
    private val running = HashMap<String, Job>()
    private val cancelledEarly = HashSet<String>()

    /** One JSON-RPC message from the client of [session] (the HTTP transport's session; null over stdio). */
    fun observe(line: String, session: String? = null) {
        val o = runCatching { DtaJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        val method = (o["method"] as? JsonPrimitive)?.content ?: return
        when (method) {
            "tools/call" -> {
                val id = "$session/" + (o["id"]?.toString() ?: return)
                val params = o["params"] as? JsonObject ?: return
                val name = (params["name"] as? JsonPrimitive)?.content ?: return
                synchronized(this) { noted += Noted(id, session, name, params["arguments"] ?: JsonObject(emptyMap())) }
            }
            "notifications/cancelled" -> {
                val id = "$session/" + ((o["params"] as? JsonObject)?.get("requestId")?.toString() ?: return)
                val job = synchronized(this) { running.remove(id) ?: run { if (noted.any { it.id == id }) cancelledEarly += id; null } }
                job?.cancel()
            }
        }
    }

    /** The call [name] with [arguments] runs as [job]; the returned function is called when it ends. */
    fun claim(name: String, arguments: JsonObject, job: Job, session: String? = null): () -> Unit {
        val id = synchronized(this) {
            val at = noted.indexOfFirst { it.session == session && it.name == name && it.arguments == arguments }
            if (at < 0) return {}
            noted.removeAt(at).id.also { id -> if (cancelledEarly.remove(id)) job.cancel() else running[id] = job }
        }
        return { synchronized(this) { running.remove(id) } }
    }
}

/** Feature 071: bytes on their way to the SDK, shown to [cancels] line by line. */
class ObservedInput(input: java.io.InputStream, private val cancels: McpCancels) : java.io.FilterInputStream(input) {
    private val line = java.io.ByteArrayOutputStream()

    private fun seen(b: ByteArray, off: Int, n: Int) {
        for (i in off until off + n) {
            val c = b[i]
            if (c == '\n'.code.toByte()) { cancels.observe(line.toString(Charsets.UTF_8)); line.reset() } else line.write(c.toInt())
        }
    }

    override fun read(): Int = super.read().also { if (it >= 0) seen(byteArrayOf(it.toByte()), 0, 1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) seen(b, off, it) }
}
