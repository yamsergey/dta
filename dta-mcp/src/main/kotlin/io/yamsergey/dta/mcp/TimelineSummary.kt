package io.yamsergey.dta.mcp

import io.yamsergey.dta.present.timeline.TimelineEventText
import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.timeline.TimelinePage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * The line at the end of every tool answer saying what the app did since the session's previous
 * call, from every timeline source (features 015/016). Cursors are per session and app; the
 * first call only places the cursor. Reading the timeline can fail for many reasons (no app,
 * app not connected): then there is simply no line, never an error in the tool's answer.
 */
internal class TimelineSummary(private val call: suspend (operation: String, params: JsonObject) -> Selected) {
    private val cursors = ConcurrentHashMap<String, String>()

    /** The line for [session] after a call that concerned [selection] (device, app), or null. */
    suspend fun after(session: String?, selection: Pair<String?, String?>?): String? = try {
        val params = buildJsonObject {
            selection?.first?.let { put("device", it) }
            selection?.second?.let { put("app", it) }
        }
        val key = key(session, selection)
        val cursor = cursors[key]
        val result = call(OPERATION, JsonObject(params + ("since" to JsonPrimitive(cursor ?: END)) + ("limit" to JsonPrimitive(LIMIT))))
        val page = DtaJson.decodeFromJsonElement(TimelinePage.serializer(), result.result)
        val app = result.selection?.app ?: selection?.second
        cursors[key(session, selection?.first to app)] = page.next
        if (cursor != null) cursors[key] = page.next
        if (cursor == null) null else TimelineEventText.summary(page.events)?.let { "$it timeline_events since=${page.next.takeIf { !page.more } ?: cursor} for details." }
    } catch (_: Exception) {
        null
    }

    /** An explicit timeline read moves the session's cursor too, so the summary does not repeat it. */
    fun read(arguments: JsonObject, result: JsonElement) {
        val page = runCatching { DtaJson.decodeFromJsonElement(TimelinePage.serializer(), result) }.getOrNull() ?: return
        val app = (arguments["app"] as? JsonPrimitive)?.content
        cursors.keys.filter { it.endsWith("|${app ?: ""}") || app == null }.forEach { cursors[it] = page.next }
    }

    private fun key(session: String?, selection: Pair<String?, String?>?) = "${session ?: ""}|${selection?.second ?: ""}"

    companion object {
        const val OPERATION = "timeline.events"
        private const val END = "end"
        private const val LIMIT = 50
    }
}
