package io.yamsergey.dta.present.network

import io.yamsergey.dta.present.Marks
import io.yamsergey.dta.present.Statement
import io.yamsergey.dta.present.StatementKind
import io.yamsergey.dta.present.Statements
import io.yamsergey.dta.present.Units
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What a row is, with its glyph and kind token (information.md: kind glyph). */
enum class EntryKind(val glyph: String, val ascii: String, val token: String, val label: String, val description: String) {
    HTTP("⇄", "<>", "kind-http", "HTTP", "HTTP exchange"),
    WS_CONNECTION("⇌", "ws", "kind-ws", "WS", "WebSocket connection"),
    WS_OUT("↑", "^", "kind-ws", "WS ↑", "WebSocket message sent"),
    WS_IN("↓", "v", "kind-ws", "WS ↓", "WebSocket message received"),
}

/** The outcome column: a status code in its colour (4xx, 5xx; success is not coloured), or a statement. */
data class Outcome(val text: String, val token: String? = null, val statement: Statement? = null)

/** One row of the network list (information.md "Network list", left to right). */
data class EntryRow(
    val id: String,
    val time: String,
    val kind: EntryKind,
    val verb: String,
    /** Muted host; null for messages. */
    val host: String?,
    /** Path and query, or a message's payload preview. */
    val path: String,
    val outcome: Outcome,
    val timing: String,
    val run: String,
    val marks: Marks,
)

object EntryRows {
    private val OUTCOMES = setOf(StatementKind.FAILED, StatementKind.PENDING, StatementKind.PROCESS_ENDED)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun of(entry: JsonObject, zone: ZoneId = ZoneId.systemDefault()): EntryRow {
        val statements = Statements.forEntry(entry)
        val ending = statements.firstOrNull { it.kind in OUTCOMES }
        val type = entry.str("type")
        val kind = when (type) {
            "http" -> EntryKind.HTTP
            "ws-connection" -> EntryKind.WS_CONNECTION
            else -> if (entry.str("direction") == "out") EntryKind.WS_OUT else EntryKind.WS_IN
        }
        val url = entry.str("url")
        val host = url?.substringAfter("://", "")?.substringBefore('/')?.takeIf { it.isNotEmpty() }
        val path = when {
            kind == EntryKind.WS_OUT || kind == EntryKind.WS_IN ->
                entry.obj("payload")?.str("preview")?.lineSequence()?.firstOrNull()?.take(200) ?: "conn ${entry.str("connectionId")}"
            host != null -> url.substringAfter("://").removePrefix(host).ifEmpty { "/" }
            else -> url ?: ""
        }
        val bodies = listOfNotNull(entry.obj("requestBody"), entry.obj("responseBody"), entry.obj("payload"))
        val timing = listOfNotNull(entry.long("durationMs")?.let(Units::millis), bodies.lastOrNull()?.long("storedBytes")?.let(Units::bytes)).joinToString(" · ")
        return EntryRow(
            id = entry.str("id") ?: "",
            time = entry.long("timeMs")?.let { TIME.withZone(zone).format(Instant.ofEpochMilli(it)) } ?: "",
            kind = kind,
            verb = when (type) {
                "http" -> entry.str("method") ?: "?"
                "ws-connection" -> entry.str("state") ?: "?"
                else -> entry.str("opcode") ?: "?"
            },
            host = if (kind == EntryKind.WS_OUT || kind == EntryKind.WS_IN) null else host,
            path = path,
            outcome = outcome(entry, type, ending),
            timing = timing,
            run = entry.str("runId") ?: "",
            marks = Statements.marks(statements.filter { it.kind !in OUTCOMES }),
        )
    }

    private fun outcome(entry: JsonObject, type: String?, ending: Statement?): Outcome {
        if (ending != null) return Outcome(ending.word, statement = ending)
        return when (type) {
            "http" -> entry.long("status")?.let { s ->
                Outcome("$s", when (s / 100) { 4L -> "status-client"; 5L -> "status-server"; else -> null })
            } ?: Outcome("")
            "ws-connection" -> Outcome(entry.long("closeCode")?.let { "closed $it" } ?: "")
            else -> Outcome("")
        }
    }
}
