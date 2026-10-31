package io.yamsergey.dta.present

import io.yamsergey.dta.protocol.ops.AppRule
import io.yamsergey.dta.protocol.ops.DeviceRule
import io.yamsergey.dta.protocol.ops.SelectionStatement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The statement language (design system statements.md), in the table's order: how a value was
 * obtained or why it is incomplete. [token] names the design token of the statement's text.
 */
enum class StatementKind(val word: String, val glyph: String, val ascii: String, val token: String) {
    FALLBACK("fallback", "▲", "!", "caveat"),
    DEGRADED("degraded", "▲", "!", "caveat"),
    REDUCED_DETAIL("reduced detail", "▲", "!", "caveat"),
    INCOMPLETE("incomplete", "◐", "~", "caveat"),
    EVICTED("evicted", "▲", "!", "caveat"),
    AUTO("auto", "◌", "?", "inferred"),
    INFERRED("inferred", "◌", "?", "inferred"),
    SCRIPTED("scripted", "✎", "*", "intervened"),
    REDACTED("redacted", "▒", "#", "ink-muted"),
    DECODED("decoded", "⇢", ">", "ink-muted"),
    BINARY("binary", "⧉", "b", "ink-muted"),
    FAILED("failed", "✕", "x", "status-server"),
    PENDING("pending", "…", "..", "ink-muted"),
    PROCESS_ENDED("process ended", "◼", "[]", "ink-muted"),
    NOT_CAPTURED("not captured", "○", "o", "ink-muted"),
}

/**
 * One statement about a result. [word] is the mark's word, [detail] the one-line text with the
 * specifics, [sentence] the plain sentence for banners and tooltips (cause and next step), [code]
 * the protocol's code verbatim when there is one, [subject] what it is about when not the row
 * itself (a library, a device).
 */
data class Statement(
    val kind: StatementKind,
    val word: String = kind.word,
    val detail: String = word,
    val code: String? = null,
    val subject: String? = null,
    private val explanation: String? = null,
) {
    val sentence: String get() = explanation ?: detail.replaceFirstChar { it.uppercase() } + "."
}

/** Marks of a row: at most three in table order, the rest counted (statements.md rules). */
data class Marks(val shown: List<Statement>, val more: Int) {
    val moreLabel: String? get() = if (more > 0) "+$more" else null
}

/**
 * Statements derived from operation results — the same JSON `dta … --json` prints, so the CLI,
 * the plugin and agents can never disagree on them. Every list comes back in table order.
 */
object Statements {

    const val MAX_MARKS = 3

    fun marks(statements: List<Statement>): Marks {
        val ordered = statements.sortedBy { it.kind.ordinal }
        return Marks(ordered.take(MAX_MARKS), (ordered.size - MAX_MARKS).coerceAtLeast(0))
    }

    /** A network entry (HTTP exchange, WebSocket connection or message) as listed. */
    fun forEntry(entry: JsonObject): List<Statement> = buildList {
        val bodies = listOfNotNull(entry.obj("requestBody"), entry.obj("responseBody"), entry.obj("payload"))
        bodies.firstOrNull { it.str("completeness") != "complete" }?.let { add(incomplete(it)) }
        entry.obj("intervention")?.let { i ->
            val changed = i.arr("changed").joinToString(",") { it.content }.ifEmpty { "nothing" }
            add(Statement(StatementKind.SCRIPTED, detail = "script ${i.str("scriptId")} changed $changed" + (i.long("delayMs")?.let { ", delayed $it ms" } ?: "") +
                (i.str("description")?.let { " — $it" } ?: "")))
        }
        if (bodies.any { it.bool("binary") }) add(Statement(StatementKind.BINARY))
        when (entry.str("state")) {
            "failed" -> add(Statement(StatementKind.FAILED, detail = entry.str("failure") ?: "failed"))
            "pending" -> add(Statement(StatementKind.PENDING))
            "process-ended" -> add(Statement(StatementKind.PROCESS_ENDED, code = "process-ended", explanation = "The app process ended before this entry finished."))
        }
    }.ordered()

    /** A `network.entry` result: the entry's statements plus redaction and degradation. */
    fun forEntryDetail(detail: JsonObject): List<Statement> =
        (forEntry(detail.obj("entry") ?: JsonObject(emptyMap())) + redaction(detail["redaction"]) + markers(detail["degraded"])).ordered()

    /** A `network.list` result as a whole: degradation, runs not searched, eviction. */
    fun forNetworkList(page: JsonObject): List<Statement> = buildList {
        addAll(markers(page["degraded"]))
        (page["archived"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { marks ->
            val epochs = marks.values.mapNotNull { (it as? JsonObject)?.str("epoch") }.distinct().size
            add(Statement(StatementKind.NOT_CAPTURED, word = "archived", detail = "archived history: ${marks.size} entries shown from $epochs epochs",
                explanation = "These entries are from history the device no longer has; this computer kept it. Live traffic is not shown while you look at the archive."))
        }
        val coverage = page.obj("coverage")
        coverage?.arr("notSearched")?.takeIf { it.isNotEmpty() }?.let { runs ->
            add(Statement(StatementKind.DEGRADED, detail = "not searched: ${runs.joinToString { it.content }}"))
        }
        coverage?.long("evicted")?.takeIf { it > 0 }?.let {
            add(Statement(StatementKind.EVICTED, detail = "$it older entries evicted", code = "network-history-evicted",
                explanation = "$it older entries were removed by the history size limit."))
        }
        // The host's own coverage (feature 011): what it has not read or copied, and the archive.
        coverage?.str("archivingStopped")?.let {
            add(Statement(StatementKind.DEGRADED, detail = it, explanation = "$it. The live history still works; free space on this computer, or delete archived history (dta archive delete)."))
        }
        coverage?.long("bodiesNotArchived")?.takeIf { it > 0 }?.let {
            add(Statement(StatementKind.DEGRADED, detail = "$it bodies not archived", explanation = "$it bodies left the device before this computer could copy them; their entries are kept without them."))
        }
        coverage?.long("hostLagRecords")?.takeIf { it > 0 }?.let {
            add(Statement(StatementKind.PENDING, detail = "$it entries not read from the device yet", explanation = "This computer is still reading $it entries from the device; ask again for the rest."))
        }
        coverage?.long("bodiesPending")?.takeIf { it > 0 }?.let {
            add(Statement(StatementKind.PENDING, detail = "$it bodies not copied to this computer yet", explanation = "$it bodies are still on the device only; they are copied in the background."))
        }
        coverage?.long("archivedEpochs")?.takeIf { it > 0 }?.let {
            add(Statement(StatementKind.NOT_CAPTURED, word = "archived", detail = "$it archived epochs not shown (archive list; --archive all)",
                explanation = "This computer keeps $it earlier histories of the app (cleared, reinstalled, older builds). List them with dta archive list; query them with --archive."))
        }
    }.ordered()

    /** An entry from the archive: why its history was archived, and the app version then. */
    fun forArchived(mark: JsonObject): Statement {
        val reason = mark.str("reason")?.replace('-', ' ') ?: "archived"
        val version = mark.str("version")?.let { ", version $it" } ?: ""
        return Statement(StatementKind.NOT_CAPTURED, word = "archived", detail = "archived: $reason$version",
            explanation = "This entry is from history the device no longer has ($reason$version); this computer kept it.")
    }

    /** A `network.body` result: decoding, completeness, binary, redaction, notes. */
    fun forBody(result: JsonObject): List<Statement> = buildList {
        val info = result.obj("body") ?: JsonObject(emptyMap())
        if (info.str("completeness").let { it != null && it != "complete" }) add(incomplete(info))
        val decoding = result.obj("decoding")
        when (decoding?.str("outcome")) {
            "decoded" -> add(Statement(StatementKind.DECODED, detail = "decoded as ${decoding.str("format")}" +
                (decoding.str("type")?.let { " $it" } ?: "") + (decoding.str("schema")?.let { " (schema $it)" } ?: "") +
                // Feature 013: whose schema it was; an archived body is decoded with today's.
                (decoding.str("origin")?.let { " · schemas from $it" } ?: "")))
            "decoded-with-warnings" -> add(Statement(StatementKind.DEGRADED,
                detail = "decoded as ${decoding.str("type")} with warnings: ${decoding.arr("warnings").joinToString("; ") { it.content }}"))
            "not-decoded" -> add(Statement(StatementKind.DEGRADED, detail = "not decoded: ${decoding.str("reason")}"))
        }
        if (info.bool("binary") && decoding?.str("outcome") != "decoded") add(Statement(StatementKind.BINARY))
        result.str("note")?.let { add(Statement(StatementKind.DEGRADED, detail = it)) }
        addAll(redaction(result["redaction"]))
        addAll(markers(result["degraded"]))
    }.ordered()

    /** A `layout.tree` result's capture: fallback, foreground mismatch, reduced detail, degradation. */
    fun forCapture(layout: JsonObject): List<Statement> = buildList {
        layout.str("fallbackReason")?.let {
            add(Statement(StatementKind.FALLBACK, detail = "fallback: $it",
                explanation = "Layout from the system fallback: $it. Build it with DTA to see Compose names and source lines."))
        }
        layout.str("foregroundMismatch")?.let { add(Statement(StatementKind.DEGRADED, detail = it)) }
        addAll(markers(layout["degraded"]))
        layout.obj("detail")?.let { d ->
            if (d.str("requested") != d.str("delivered")) {
                add(Statement(StatementKind.REDUCED_DETAIL, detail = "detail ${d.str("requested")} reduced to ${d.str("delivered")}",
                    explanation = "Detail ${d.str("requested")} was requested; ${d.str("delivered")} was delivered."))
            }
        }
    }.ordered()

    /** A `capability.report` result: every capability or library that is not fully available. */
    fun forCapability(report: JsonObject): List<Statement> = buildList {
        fun gap(name: String, status: String, reason: String?) {
            if (status == "available") return
            if (name == "grpc") {
                add(Statement(StatementKind.NOT_CAPTURED, detail = listOfNotNull("$name not captured", reason).joinToString(": "), subject = name))
            } else {
                val code = Degradations.codeIn(reason)
                add(Statement(StatementKind.DEGRADED, word = status, detail = listOfNotNull("$name $status", reason).joinToString(": "), subject = name,
                    code = code, explanation = Degradations.explain(code)))
            }
        }
        report.arr("capabilities").forEach { c ->
            val cap = c.asObj()
            gap(cap.str("name") ?: "?", cap.str("status") ?: "?", cap.str("reason"))
            cap.arr("libraries").forEach { l -> l.asObj().let { gap(it.str("name") ?: "?", it.str("status") ?: "?", it.str("reason")) } }
        }
    }.ordered()

    /** A `network.cleared` event (any client cleared the app's history); null for other events. */
    fun forCleared(data: JsonObject): Statement? {
        val cleared = data.obj("cleared") ?: return null
        val n = cleared.long("clearedEntries") ?: 0
        val text = "History cleared by ${data.str("by") ?: "a client"}: $n ${if (n == 1L) "entry" else "entries"} removed."
        return Statement(StatementKind.EVICTED, word = "cleared", detail = text, explanation = text)
    }

    /** Degradation markers (`[{capability, reason}]`): the reason is the protocol code, explained by [Degradations]. */
    fun markers(element: JsonElement?): List<Statement> = (element as? JsonArray).orEmpty().map { m ->
        val marker = m.asObj()
        Statement(StatementKind.DEGRADED, detail = "${marker.str("capability")}: ${marker.str("reason")}", code = marker.str("reason"),
            explanation = Degradations.explain(marker.str("reason")))
    }

    private fun redaction(element: JsonElement?): List<Statement> {
        val r = element as? JsonObject ?: return emptyList()
        if (!r.bool("applied", default = true)) return listOf(Statement(StatementKind.DEGRADED, detail = "redaction requested but overridden"))
        val masked = r.arr("masked").joinToString(", ") { it.content }.ifEmpty { "nothing to mask" }
        val withheld = r.arr("withheld").takeIf { it.isNotEmpty() }?.let { "; withheld: " + it.joinToString(", ") { w -> w.content } } ?: ""
        return listOf(Statement(StatementKind.REDACTED, detail = "redacted: $masked$withheld"))
    }

    private fun incomplete(body: JsonObject): Statement {
        val completeness = body.str("completeness")!!
        return Statement(StatementKind.INCOMPLETE, word = completeness, detail = "$completeness: ${body.str("completenessReason")}")
    }

    private fun List<Statement>.ordered() = sortedBy { it.kind.ordinal }
}

private val JsonElement.content: String get() = (this as? JsonPrimitive)?.content ?: toString()
private fun JsonElement.asObj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
private fun JsonObject.arr(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
private fun JsonObject.bool(name: String, default: Boolean = false): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull ?: default
