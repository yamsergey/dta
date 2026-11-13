package io.yamsergey.dta.present.network

import io.yamsergey.dta.protocol.network.EntryHits
import io.yamsergey.dta.protocol.network.EntryKind as Kind
import io.yamsergey.dta.protocol.network.ParsedQuery
import io.yamsergey.dta.protocol.network.QueryLine
import io.yamsergey.dta.protocol.network.RecentFilter
import io.yamsergey.dta.protocol.network.SearchHit
import io.yamsergey.dta.protocol.network.SearchPlace
import io.yamsergey.dta.protocol.network.BodyPart
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** A completion: replace the word at the caret ([from]..caret) with [text]; a [recent] one is saved again as it is. */
data class Suggestion(val text: String, val from: Int, val hint: String? = null, val recent: Boolean = false)

/** What the traffic shown so far offers as values. */
data class Seen(val hosts: Set<String> = emptySet(), val statuses: Set<String> = emptySet(), val methods: Set<String> = emptySet())

/**
 * Feature 040: the Network query line as the plugin edits it. Feature 043: what the person is
 * typing; Enter ([commit]) saves its filter words as chips (kept by the daemon) and leaves the rest.
 */
data class QueryLineModel(val text: String = "", private val nowMs: () -> Long = System::currentTimeMillis) {
    val parsed: ParsedQuery by lazy { QueryLine.parse(text, nowMs()) }

    val hasSearch: Boolean get() = parsed.hasSearch && parsed.problems.isEmpty()

    fun withText(t: String) = copy(text = t)

    /** Feature 043, Enter: the words to save and the line that stays. */
    fun commit(): QueryLine.Commit = QueryLine.commit(text, nowMs())

    /**
     * `q` for network.list / network.watch / network.export / network.search; empty when there is
     * nothing to ask. The kind switch joins it unless the line says a kind itself.
     */
    fun params(kind: KindChoice = KindChoice.ALL): Map<String, JsonElement> {
        val typedKind = parsed.tokens.any { it.key == "kind" && !it.negated }
        val word = when (kind) { KindChoice.ALL -> null; KindChoice.HTTP -> "http"; KindChoice.WEBSOCKET -> "ws" }.takeUnless { typedKind }
        return listOfNotNull(word, text.trim().ifEmpty { null }).joinToString(" ").takeIf { it.isNotEmpty() }?.let { mapOf("q" to JsonPrimitive(it)) } ?: emptyMap()
    }

    /** Completions for the word ending at [caret]; [recent] filters of the app first (feature 043). */
    fun suggestions(
        caret: Int,
        seen: Seen = Seen(),
        recent: List<RecentFilter> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
        /** Feature 051: the kind switch; its keys come first. */
        kind: KindChoice = KindChoice.ALL,
        /** Feature 051: asked for (Down): every key even mid-line. */
        explicit: Boolean = false,
    ): List<Suggestion> {
        val at = caret.coerceIn(0, text.length)
        val word = word(text, at)
        val from = at - word.length
        val back = recent.filter { it.word.startsWith(word, ignoreCase = true) && it.word != word }
            .map { Suggestion(it.word, from, SavedChip.age(it.lastUsedMs, nowMs), recent = true) }
        // Feature 051: an empty line (or Down) lists every key, so none has to be remembered.
        if (word.isEmpty()) return back + if (text.isBlank() || explicit) keys(kind).map { Suggestion("${it.name}:", from, it.hint) } else emptyList()
        // Feature 063: a value that is also a recent filter is offered once, as the recent one.
        val offered = back.map { it.text }.toSet()
        return back + typed(from, word, seen, kind).filterNot { it.text in offered }
    }

    /** Every key; the kind switch's own keys first, in the language's order otherwise. */
    private fun keys(kind: KindChoice): List<QueryLine.KeyInfo> {
        val wanted = when (kind) {
            KindChoice.ALL -> return QueryLine.KEY_INFO
            KindChoice.HTTP -> setOf(Kind.HTTP)
            KindChoice.WEBSOCKET -> setOf(Kind.WS_CONNECTION, Kind.WS_MESSAGE)
        }
        // Keys only for the chosen kind first, then the shared ones, then the rest.
        return QueryLine.KEY_INFO.sortedBy { k -> if (k.kinds.all { it in wanted }) 0 else if (k.kinds.any { it in wanted }) 1 else 2 }
    }

    private fun typed(from: Int, word: String, seen: Seen, kind: KindChoice): List<Suggestion> {
        val negated = word.startsWith("-")
        val body = if (negated) word.drop(1) else word
        val n = if (negated) "-" else ""
        val colon = body.indexOf(':')
        if (colon < 0) {
            if (body.isEmpty() && !negated) return emptyList()
            val keys = keys(kind).filter { it.name.startsWith(body.lowercase()) && it.name != body.lowercase() }.map { Suggestion("$n${it.name}:", from, it.hint) }
            val bare = if (negated) emptyList() else (listOf("http", "ws") + QueryLine.METHODS).filter { it.startsWith(body, ignoreCase = true) && it != body }
                .map { Suggestion(it, from, if (it in QueryLine.METHODS) "method" else "kind") }
            return keys + bare
        }
        val key = body.substring(0, colon).lowercase()
        val value = body.substring(colon + 1)
        // Feature 053: an empty body value offers the pattern form.
        if (key in QueryLine.PATTERN_KEYS) {
            return if (value.isEmpty()) listOf(Suggestion("$n$key:/", from, "a /regex/ pattern, case ignored: /\"id\":\\s*\\d+/ (end it with /)")) else emptyList()
        }
        val values = when (key) {
            "status" -> listOf("2xx", "3xx", "4xx", "5xx", "failed", "pending") + seen.statuses.sorted() + listOf("open", "closed", "connecting", "1000", "1006")
            "dir" -> listOf("sent", "received")
            "frame" -> listOf("text", "binary")
            "mime" -> listOf("json", "html", "xml", "protobuf", "text/plain", "image")
            "duration" -> listOf(">1s", ">500ms", "<100ms")
            "size" -> listOf(">100k", ">1m", "<1k")
            "host" -> seen.hosts.sorted()
            "method" -> (QueryLine.METHODS + seen.methods).toSortedSet().toList()
            "kind" -> listOf("http", "ws", "ws-connection", "ws-message")
            "source" -> QueryLine.SOURCES
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
        val insert = s.text + tail(s.text, text.substring(end))
        val t = text.substring(0, s.from) + insert + text.substring(end)
        return withText(t) to s.from + insert.length
    }

    companion object {
        /** Feature 063: the word ending at [caret] — what a suggestion replaces (the completion prefix). */
        fun word(text: String, caret: Int): String {
            val at = caret.coerceIn(0, text.length)
            var from = at
            while (from > 0 && !text[from - 1].isWhitespace()) from--
            return text.substring(from, at)
        }

        /** Feature 063: what follows an inserted [suggestion] before [after]: a space after a finished word, none after a key or a pattern start. */
        fun tail(suggestion: String, after: String): String {
            val done = !suggestion.endsWith(":") && !suggestion.endsWith(":/")
            return if (done && (after.isEmpty() || !after[0].isWhitespace())) " " else ""
        }

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
