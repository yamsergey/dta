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

/** A completion: replace the word at the caret ([from]..caret) with [text]; [recent]: a filter the app had saved before (it goes into the line like any other). */
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

    /** What the detail marks: the search words (one typo away with `typos:on`) and the body filters (D-034). */
    val marks: SearchMarks get() = if (parsed.problems.isEmpty()) SearchMarks.of(parsed) else SearchMarks()

    /**
     * The line finds something to point at — search words, or a body filter (text or pattern): its
     * rows carry hits, the detail opens on them and marks them (071: a body filter is a search too).
     */
    val finds: Boolean get() = hasSearch || marks.bodies.isNotEmpty()

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
        /** The app's saved filters (chips): not offered again. */
        saved: List<String> = emptyList(),
    ): List<Suggestion> {
        val at = caret.coerceIn(0, text.length)
        val word = word(text, at)
        val from = at - word.length
        val held = Held.of(text, from, at, saved)
        val back = recent.filter { it.word.startsWith(word, ignoreCase = true) && it.word != word }
            .map { Suggestion(it.word, from, SavedChip.age(it.lastUsedMs, nowMs), recent = true) }.filter(held::allows)
        // Feature 051: an empty line (or Down) lists every key, so none has to be remembered.
        if (word.isEmpty()) return back + if (text.isBlank() || explicit) keys(kind).filter { held.allows(it.name) }.map { Suggestion("${it.name}:", from, it.hint) } else emptyList()
        // Feature 063: a value that is also a recent filter is offered once, as the recent one.
        val offered = back.map { it.text }.toSet()
        return back + typed(from, word, seen, kind).filterNot { it.text in offered || !held.allows(it) }
    }

    /**
     * What the line (without the word at the caret) and the saved filters already hold: their words
     * are not offered again, nor a key that takes one value and is already given in the line — the
     * line would refuse it as given twice. An exclusion of the same key is always fine.
     */
    private class Held(val words: Set<String>, private val once: Set<String>) {
        fun allows(key: String) = key !in once

        fun allows(s: Suggestion): Boolean {
            if (s.text in words) return false
            val t = s.text
            // The opposite of a word already there would undo it (or leave nothing).
            if (t.startsWith("-")) return t.drop(1) !in words
            if ("-$t" in words) return false
            val key = when {
                t in QueryLine.METHODS -> "method"
                t == "http" || t == "ws" -> "kind"
                else -> t.substringBefore(':', "").ifEmpty { return true }
            }
            return allows(key)
        }

        companion object {
            /** Keys a second positive value of which the line refuses (duration and size take a lower and an upper bound). */
            private val TWICE = setOf("duration", "size")

            fun of(text: String, from: Int, at: Int, saved: List<String>): Held {
                val rest = text.removeRange(from, at)
                val tokens = QueryLine.parse(rest).tokens
                val once = tokens.filter { it.key != null && !it.negated && it.key !in TWICE }.mapNotNull { it.key }.toSet()
                return Held(tokens.map { it.text }.toSet() + saved, once)
            }
        }
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
            // run:, since: and until: cannot be excluded (they say which part of the history).
            val keys = keys(kind).filter { it.name.startsWith(body.lowercase()) && it.name != body.lowercase() && !(negated && it.name in QueryLine.SESSION_KEYS) }
                .map { Suggestion("$n${it.name}:", from, it.hint) }
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
            "typos" -> listOf("on", "off")
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
data class HitNavigator(val entries: List<EntryHits> = emptyList(), val index: Int = -1, val more: Boolean = false) {
    /** The hits of the rows loaded (feature 069: not a count of every hit). */
    val total: Int get() = entries.size
    val current: EntryHits? get() = entries.getOrNull(index)

    /** "3 / 17", "– / 17" before the first move, "No matches"; "… loaded" when more rows have hits. */
    val label: String get() = when {
        entries.isEmpty() -> "No matches"
        index < 0 -> "– / $total" + loaded
        else -> "${index + 1} / $total" + loaded
    }

    private val loaded: String get() = if (more) " loaded" else ""

    fun next() = if (entries.isEmpty()) this else copy(index = (index + 1).mod(total))
    fun previous() = if (entries.isEmpty()) this else copy(index = if (index < 0) total - 1 else (index - 1).mod(total))

    /** By id, built once per navigator: the list asks for every cell it paints (070: tens of thousands of hits). */
    private val byId: Map<String, EntryHits> by lazy { this@HitNavigator.entries.associateByTo(HashMap(this@HitNavigator.entries.size * 2)) { it.id } }

    fun hitsOf(id: String): List<SearchHit>? = byId[id]?.hits

    /**
     * Feature 067: a live entry's hits (a subscription's decision), in place when known, else at the
     * newest end — first when [newestFirst] — keeping the position on its entry; null hits remove it.
     */
    fun live(id: String, timeMs: Long, hits: List<SearchHit>?, newestFirst: Boolean): HitNavigator {
        val at = entries.indexOfFirst { it.id == id }
        val was = current?.id
        val next = when {
            hits.isNullOrEmpty() -> if (at < 0) return this else entries.filterIndexed { i, _ -> i != at }
            at >= 0 -> entries.toMutableList().also { it[at] = EntryHits(id, timeMs, hits) }
            newestFirst -> listOf(EntryHits(id, timeMs, hits)) + entries
            else -> entries + EntryHits(id, timeMs, hits)
        }
        val index = was?.let { w -> next.indexOfFirst { it.id == w } } ?: -1
        return HitNavigator(next, index, more)
    }

    /** The same position by entry id after a refresh; [order] is the list's ids top to bottom, for the nearest. */
    /**
     * Feature 070: an older page's hits (its rows' own, as `network.list` gives them) join at the
     * oldest end — the bottom when newest first, the top when newest at the bottom; the current hit stays.
     */
    fun withOlder(older: List<EntryHits>, newestFirst: Boolean, more: Boolean): HitNavigator {
        val have = entries.mapTo(HashSet()) { it.id }
        val add = older.filter { it.id !in have }.sortedWith(compareByDescending<EntryHits> { it.timeMs }.thenByDescending { it.id })
        val next = if (newestFirst) entries + add else add.asReversed() + entries
        val was = current?.id
        return HitNavigator(next, was?.let { w -> next.indexOfFirst { it.id == w } } ?: -1, more)
    }

    fun refreshed(fresh: List<EntryHits>, order: List<String> = emptyList(), more: Boolean = this.more): HitNavigator {
        val was = current?.id ?: return HitNavigator(fresh, more = more)
        fresh.indexOfFirst { it.id == was }.takeIf { it >= 0 }?.let { return HitNavigator(fresh, it, more) }
        val pos = order.indexOf(was)
        if (pos < 0 || fresh.isEmpty()) return HitNavigator(fresh, more = more)
        val rank = order.withIndex().associate { it.value to it.index }
        val nearest = fresh.indices.minByOrNull { i -> rank[fresh[i].id]?.let { kotlin.math.abs(it - pos) } ?: Int.MAX_VALUE }!!
        return HitNavigator(fresh, nearest, more)
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
     * hits give): found again with the same match as the search (exact unless [typos]), in order.
     */
    fun spans(text: String, terms: List<String>, typos: Boolean = false): List<IntRange> {
        if (terms.isEmpty() || text.isEmpty()) return emptyList()
        val t = io.yamsergey.dta.protocol.network.Fuzzy.Text(text)
        return terms.flatMap { term -> t.find(term, typos).map { it.start until it.end } }.distinct().sortedBy { it.first }
    }
}

/**
 * D-034: what the detail marks in the texts it shows, found again in each text as shown — the
 * search words everywhere, the body filters (`body:` / `req:` / `resp:` text and patterns) only in
 * the bodies they apply to ([inBody]). Exclusions are not marked: they are not in a listed entry.
 */
data class SearchMarks(
    val words: List<String> = emptyList(),
    /** `typos:on`: the words are found one typo away too, as the search found them. */
    val typos: Boolean = false,
    val bodies: List<BodyMark> = emptyList(),
    /** This text is a body: of the request ([BodySide.REQUEST]), response, or a message ([BodySide.ANY]). */
    private val body: BodySide? = null,
) {
    enum class BodySide { ANY, REQUEST, RESPONSE }

    /** A body filter's text or pattern and the bodies it looks in. */
    data class BodyMark(val text: String, val pattern: Boolean, val side: BodySide)

    val isEmpty: Boolean get() = words.isEmpty() && bodies.isEmpty()

    /** The marks for a body of [side]: the words, and the body filters that look there. */
    fun inBody(side: BodySide): SearchMarks = copy(bodies = bodies.filter { it.side == BodySide.ANY || it.side == side }, body = side)

    /** Spans in [text] as shown, in order, overlapping ones merged. */
    fun spans(text: String): List<IntRange> {
        if (text.isEmpty() || isEmpty) return emptyList()
        val all = Highlights.spans(text, words, typos) + if (body == null) emptyList() else bodies.flatMap { m -> if (m.pattern) patternSpans(text, m.text) else textSpans(text, m.text) }
        return merged(all)
    }

    companion object {
        /** At most this many marks per body filter in one text. */
        const val MAX = 1000
        /** A pattern is marked for at most this long on the UI thread; past it, the Found in line says where. */
        const val PATTERN_MS = 200L

        fun of(p: ParsedQuery): SearchMarks {
            val f = p.filter
            val bodies = listOfNotNull(
                f.bodyContains?.let { BodyMark(it, false, BodySide.ANY) },
                f.requestContains?.let { BodyMark(it, false, BodySide.REQUEST) },
                f.responseContains?.let { BodyMark(it, false, BodySide.RESPONSE) },
                f.bodyPattern?.let { BodyMark(it, true, BodySide.ANY) },
                f.requestPattern?.let { BodyMark(it, true, BodySide.REQUEST) },
                f.responsePattern?.let { BodyMark(it, true, BodySide.RESPONSE) },
            )
            return SearchMarks(p.terms.map { it.text }, p.typos, bodies)
        }

        /**
         * `body:` text as the filter matches it (a substring, case ignored), found again in a body as
         * shown: a pretty-printed one adds white space around JSON's `{ } [ ] , :`, so there it may differ.
         */
        fun textSpans(text: String, needle: String): List<IntRange> {
            if (needle.isEmpty()) return emptyList()
            val out = ArrayList<IntRange>()
            var i = text.indexOf(needle, ignoreCase = true)
            while (i >= 0 && out.size < MAX) { out += i until i + needle.length; i = text.indexOf(needle, i + needle.length, ignoreCase = true) }
            if (out.isNotEmpty() || needle.none { it in JSON_PUNCTUATION || it.isWhitespace() }) return out
            val loose = buildString {
                needle.trim().forEachIndexed { k, c ->
                    val prev = if (k > 0) needle.trim()[k - 1] else null
                    if (c.isWhitespace()) { if (prev?.isWhitespace() != true) append("\\s*"); return@forEachIndexed }
                    if (prev != null && !prev.isWhitespace() && (prev in JSON_PUNCTUATION || c in JSON_PUNCTUATION)) append("\\s*")
                    append(java.util.regex.Pattern.quote(c.toString()))
                }
            }
            val m = java.util.regex.Pattern.compile(loose, java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE).matcher(text)
            while (out.size < MAX && m.find()) if (m.end() > m.start()) out += m.start() until m.end()
            return out
        }

        /** A body pattern's matches in a text as shown; none when it does not compile, those found so far when it takes too long here. */
        fun patternSpans(text: String, pattern: String): List<IntRange> {
            val p = (io.yamsergey.dta.protocol.network.BodyPattern.compile(pattern) as? io.yamsergey.dta.protocol.network.BodyPattern.Result.Ok)?.pattern ?: return emptyList()
            val out = ArrayList<IntRange>()
            val m = p.matcher(Deadline(text, System.nanoTime() + PATTERN_MS * 1_000_000))
            try {
                while (out.size < MAX && m.find()) if (m.end() > m.start()) out += m.start() until m.end()
            } catch (_: Deadline.Late) {
                // A runaway pattern on the UI thread: what was found is marked; the hits say the rest.
            }
            return out
        }

        /** Text whose reads end the match once [until] (nanoTime) has passed. */
        private class Deadline(private val text: CharSequence, private val until: Long) : CharSequence {
            class Late : RuntimeException(null, null, false, false)
            private var reads = 0
            override val length: Int get() = text.length
            override fun get(index: Int): Char {
                if (++reads and 1023 == 0 && System.nanoTime() > until) throw Late()
                return text[index]
            }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = Deadline(text.subSequence(startIndex, endIndex), until)
            override fun toString(): String = text.toString()
        }

        private const val JSON_PUNCTUATION = "{}[],:"

        private fun merged(spans: List<IntRange>): List<IntRange> {
            val sorted = spans.filter { !it.isEmpty() }.sortedBy { it.first }
            val out = ArrayList<IntRange>()
            for (r in sorted) {
                val last = out.lastOrNull()
                if (last != null && r.first <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, r.last) else out += r
            }
            return out
        }
    }
}
