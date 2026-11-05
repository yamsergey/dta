package io.yamsergey.dta.present.network

import io.yamsergey.dta.protocol.network.EntryKind as WireKind
import io.yamsergey.dta.protocol.network.EntryHits
import io.yamsergey.dta.protocol.network.ParsedQuery
import io.yamsergey.dta.protocol.network.QueryLine
import io.yamsergey.dta.protocol.network.SearchHit
import io.yamsergey.dta.protocol.network.SearchPlace
import io.yamsergey.dta.protocol.network.BodyPart
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** A filter word of the line shown under it; × removes [start]..[end] from the line. */
data class LineChip(val label: String, val start: Int, val end: Int, val negated: Boolean)

/** A completion: replace the word at the caret ([from]..caret) with [text]. */
data class Suggestion(val text: String, val from: Int, val hint: String? = null)

/** What the traffic shown so far offers as values. */
data class Seen(val hosts: Set<String> = emptySet(), val statuses: Set<String> = emptySet(), val methods: Set<String> = emptySet())

/**
 * Feature 040: the Network query line as the plugin edits it — the text is the state; chips,
 * the kind switch and the request params all come from it, so line and chips never disagree.
 */
data class QueryLineModel(val text: String = "", private val nowMs: () -> Long = System::currentTimeMillis) {
    val parsed: ParsedQuery by lazy { QueryLine.parse(text, nowMs()) }

    val chips: List<LineChip> get() = parsed.tokens.filter { it.key != null }.map { LineChip(it.text, it.start, it.end, it.negated) }

    val hasSearch: Boolean get() = parsed.hasSearch && parsed.problems.isEmpty()

    /** The All / HTTP / WebSocket switch position, or null when the line's kinds are something else. */
    val kind: KindChoice?
        get() {
            val kinds = parsed.filter.kinds ?: return KindChoice.ALL
            return KindChoice.entries.firstOrNull { it != KindChoice.ALL && it.kinds.toSet() == kinds.map(::wire).toSet() }
        }

    fun withText(t: String) = copy(text = t)

    /** The line without the chip's word. */
    fun without(chip: LineChip): QueryLineModel = withText(tidy(text.removeRange(chip.start, chip.end)))

    /** The switch: the line's own kind word replaced (or removed for All). */
    fun withKind(choice: KindChoice): QueryLineModel {
        var t = text
        parsed.tokens.filter { it.key == "kind" && !it.negated }.sortedByDescending { it.start }.forEach { t = t.removeRange(it.start, it.end) }
        val word = when (choice) { KindChoice.ALL -> null; KindChoice.HTTP -> "http"; KindChoice.WEBSOCKET -> "ws" }
        return withText(tidy(listOfNotNull(word, t.trim().ifEmpty { null }).joinToString(" ")))
    }

    /** `q` for network.list / network.watch / network.export / network.search; empty when the line is. */
    fun params(): Map<String, JsonElement> = text.trim().takeIf { it.isNotEmpty() }?.let { mapOf("q" to JsonPrimitive(it)) } ?: emptyMap()

    /** Completions for the word ending at [caret]. */
    fun suggestions(caret: Int, seen: Seen = Seen()): List<Suggestion> {
        val at = caret.coerceIn(0, text.length)
        var from = at
        while (from > 0 && !text[from - 1].isWhitespace()) from--
        val word = text.substring(from, at)
        val negated = word.startsWith("-")
        val body = if (negated) word.drop(1) else word
        val n = if (negated) "-" else ""
        val colon = body.indexOf(':')
        if (colon < 0) {
            if (body.isEmpty() && !negated) return emptyList()
            val keys = QueryLine.KEYS.filter { it.startsWith(body.lowercase()) && it != body.lowercase() }.map { Suggestion("$n$it:", from) }
            val bare = if (negated) emptyList() else (listOf("http", "ws") + QueryLine.METHODS).filter { it.startsWith(body, ignoreCase = true) && it != body }
                .map { Suggestion(it, from, if (it in QueryLine.METHODS) "method" else "kind") }
            return keys + bare
        }
        val key = body.substring(0, colon).lowercase()
        val value = body.substring(colon + 1)
        val values = when (key) {
            "status" -> listOf("2xx", "3xx", "4xx", "5xx", "failed", "pending") + seen.statuses.sorted()
            "host" -> seen.hosts.sorted()
            "method" -> (QueryLine.METHODS + seen.methods).toSortedSet().toList()
            "kind" -> listOf("http", "ws", "ws-connection", "ws-message")
            "has" -> listOf("intervention")
            "run" -> listOf("current", "all")
            "since", "until" -> listOf("5m", "15m", "1h", "1d")
            else -> emptyList()
        }
        return values.distinct().filter { it.startsWith(value, ignoreCase = true) && it != value }.map { Suggestion("$n$key:$it", from) }
    }

    /** [s] applied: the new model and the caret after it (a space follows a finished word). */
    fun apply(s: Suggestion, caret: Int): Pair<QueryLineModel, Int> {
        val end = caret.coerceIn(s.from, text.length)
        val done = !s.text.endsWith(":")
        val insert = s.text + if (done && (end == text.length || !text[end].isWhitespace())) " " else ""
        val t = text.substring(0, s.from) + insert + text.substring(end)
        return withText(t) to s.from + insert.length
    }

    private fun wire(k: WireKind) = when (k) {
        WireKind.HTTP -> "http"; WireKind.WS_CONNECTION -> "ws-connection"; WireKind.WS_MESSAGE -> "ws-message"; WireKind.LINK -> "link"
    }

    private fun tidy(t: String) = t.trim().replace(Regex("\\s{2,}"), " ")

    companion object {
        fun seen(rows: List<EntryRow>): Seen = Seen(
            hosts = rows.mapNotNull { it.host?.takeIf { h -> h.isNotEmpty() }?.substringBefore(':') }.toSet(),
            statuses = rows.mapNotNull { it.outcome.text.substringBefore(' ').takeIf { s -> s.length == 3 && s.all(Char::isDigit) } }.toSet(),
            methods = rows.filter { it.kind == EntryKind.HTTP }.map { it.verb }.toSet(),
        )
    }
}

/**
 * Feature 040: stepping through search hits in the order the list shows them. The current hit
 * stays on its entry when the hits are refreshed (new traffic), else moves to the nearest.
 */
data class HitNavigator(val entries: List<EntryHits> = emptyList(), val index: Int = -1) {
    val total: Int get() = entries.size
    val current: EntryHits? get() = entries.getOrNull(index)

    /** "3 / 17", "– / 17" before the first move, "No matches". */
    val label: String get() = when {
        entries.isEmpty() -> "No matches"
        index < 0 -> "– / $total"
        else -> "${index + 1} / $total"
    }

    fun next() = if (entries.isEmpty()) this else copy(index = (index + 1).mod(total))
    fun previous() = if (entries.isEmpty()) this else copy(index = if (index < 0) total - 1 else (index - 1).mod(total))

    fun hitsOf(id: String): List<SearchHit>? = entries.firstOrNull { it.id == id }?.hits

    /** The same position by entry id after a refresh; [order] is the list's ids top to bottom, for the nearest. */
    fun refreshed(fresh: List<EntryHits>, order: List<String> = emptyList()): HitNavigator {
        val was = current?.id ?: return HitNavigator(fresh)
        fresh.indexOfFirst { it.id == was }.takeIf { it >= 0 }?.let { return HitNavigator(fresh, it) }
        val pos = order.indexOf(was)
        if (pos < 0 || fresh.isEmpty()) return HitNavigator(fresh)
        val rank = order.withIndex().associate { it.value to it.index }
        val nearest = fresh.indices.minByOrNull { i -> rank[fresh[i].id]?.let { kotlin.math.abs(it - pos) } ?: Int.MAX_VALUE }!!
        return HitNavigator(fresh, nearest)
    }

    /** The detail tab the entry's first hit is in. */
    fun tabOf(hits: List<SearchHit>): HitTab = when (hits.firstOrNull()?.place) {
        SearchPlace.REQUEST_BODY -> HitTab.REQUEST
        SearchPlace.RESPONSE_BODY -> HitTab.RESPONSE
        SearchPlace.PAYLOAD, SearchPlace.ENVELOPE -> HitTab.MESSAGE
        else -> HitTab.HEADERS
    }
}

enum class HitTab { HEADERS, REQUEST, RESPONSE, MESSAGE }

/** Feature 040: the spans to highlight in one view of an entry. */
object Highlights {
    /** In a body's text ([part]: request, response, message, or their app-side ones). */
    fun body(hits: List<SearchHit>, part: BodyPart): List<IntRange> =
        hits.filter { it.part == part && !it.binary }.map { it.start until it.end }.distinct().sortedBy { it.first }

    /** In a header table: the headers (by name) whose name or value holds a hit, request or response. */
    fun headers(hits: List<SearchHit>, response: Boolean): Set<String> =
        hits.filter { it.place == if (response) SearchPlace.RESPONSE_HEADER else SearchPlace.REQUEST_HEADER }.mapNotNull { it.header?.lowercase() }.toSet()

    fun url(hits: List<SearchHit>): List<IntRange> = hits.filter { it.place == SearchPlace.URL }.map { it.start until it.end }

    /**
     * Where the search words are in a text as shown (pretty-printed bodies move the offsets the
     * hits give): found again with the same fuzzy match, in order.
     */
    fun spans(text: String, terms: List<String>): List<IntRange> {
        if (terms.isEmpty() || text.isEmpty()) return emptyList()
        val t = io.yamsergey.dta.protocol.network.Fuzzy.Text(text)
        return terms.flatMap { term -> t.find(term).map { it.start until it.end } }.distinct().sortedBy { it.first }
    }
}
