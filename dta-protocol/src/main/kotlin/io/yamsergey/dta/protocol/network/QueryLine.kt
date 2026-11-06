package io.yamsergey.dta.protocol.network

import kotlinx.serialization.Serializable

/** A search word or a quoted phrase of the query line (feature 040). */
@Serializable
data class Term(val text: String, val phrase: Boolean = false)

/** Something wrong in the query line, at [start]..[end] (character offsets). */
@Serializable
data class QueryProblem(val start: Int, val end: Int, val message: String)

/** One word of the line as typed: a filter ([key] set) or a search term; for chips. */
@Serializable
data class QueryToken(val start: Int, val end: Int, val text: String, val key: String? = null, val negated: Boolean = false)

/** A parsed query line: filters narrow the list, [terms] are searched and mark hits. */
@Serializable
data class ParsedQuery(
    val filter: NetworkFilter = NetworkFilter(),
    val exclude: List<NetworkFilter> = emptyList(),
    val terms: List<Term> = emptyList(),
    val problems: List<QueryProblem> = emptyList(),
    val tokens: List<QueryToken> = emptyList(),
) {
    val hasSearch: Boolean get() = terms.isNotEmpty()
}

/**
 * Feature 040: network's one query language, the same in the plugin, the CLI and MCP.
 * `key:value` words are filters (`-key:value` excludes), bare HTTP methods in upper case and
 * `http` / `ws` are filters too, everything else is a search word or a "quoted phrase".
 */
object QueryLine {
    val KEYS = listOf("method", "status", "host", "url", "kind", "has", "body", "run", "since", "until")
    val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
    private val NOT_NEGATABLE = setOf("run", "since", "until", "has")
    private val STATUS = Regex("""[1-5]\d\d|[1-5]xx|failed|pending""")
    private val RELATIVE = Regex("""(\d+)([smhd])""")
    private val WS = listOf(EntryKind.WS_CONNECTION, EntryKind.WS_MESSAGE)

    private class Raw(val start: Int, val end: Int, val text: String, val quoted: Boolean, val key: String?, val value: String?, val negated: Boolean)

    fun parse(q: String, nowMs: Long = System.currentTimeMillis()): ParsedQuery {
        var filter = NetworkFilter()
        val exclude = ArrayList<NetworkFilter>()
        val terms = ArrayList<Term>()
        val problems = ArrayList<QueryProblem>()
        val tokens = ArrayList<QueryToken>()
        val seen = HashSet<String>()
        for (raw in split(q)) {
            fun problem(message: String) { problems += QueryProblem(raw.start, raw.end, message) }
            if (raw.key == null) {
                when {
                    raw.quoted -> terms += Term(raw.text, true)
                    raw.text in METHODS -> { filter = once(filter, "method", seen, ::problem) { it.copy(method = raw.text) }; tokens += QueryToken(raw.start, raw.end, raw.text, "method"); continue }
                    raw.text == "http" || raw.text == "ws" -> { filter = once(filter, "kind", seen, ::problem) { it.copy(kinds = kinds(raw.text)) }; tokens += QueryToken(raw.start, raw.end, raw.text, "kind"); continue }
                    raw.text.startsWith("-") && raw.text.length > 1 -> { problem("excluding words is not supported; to leave out bodies with a text use -body:${raw.text.drop(1)}"); continue }
                    else -> terms += Term(raw.text, false)
                }
                tokens += QueryToken(raw.start, raw.end, raw.text)
                continue
            }
            val key = raw.key
            val value = raw.value.orEmpty()
            if (key !in KEYS) {
                val near = KEYS.minByOrNull { distance(it, key) }!!
                problem("unknown filter '$key:'" + if (distance(near, key) <= 2) " — did you mean $near:?" else "; filters: ${KEYS.joinToString(" ") { "$it:" }}")
                continue
            }
            if (value.isEmpty()) { problem("$key: needs a value"); continue }
            if (raw.negated && key in NOT_NEGATABLE && key != "has") { problem("$key: cannot be negated"); continue }
            val one: NetworkFilter = when (key) {
                "method" -> NetworkFilter(method = value.uppercase())
                "status" -> if (STATUS.matches(value)) NetworkFilter(status = value) else { problem("status: takes a code (200), a class (4xx), failed or pending; was '$value'"); continue }
                "host" -> NetworkFilter(host = value)
                "url" -> NetworkFilter(url = value)
                "body" -> NetworkFilter(bodyContains = value)
                "kind" -> kinds(value)?.let { NetworkFilter(kinds = it) } ?: run { problem("kind: takes http, ws, ws-connection or ws-message; was '$value'"); continue }
                "has" -> if (value == "intervention") NetworkFilter(intervened = !raw.negated) else { problem("has: takes intervention; was '$value'"); continue }
                "run" -> if (value == "current") NetworkFilter(runs = RunScope.CURRENT) else if (value == "all") NetworkFilter(runs = RunScope.ALL) else { problem("run: takes current or all; was '$value'"); continue }
                "since", "until" -> time(value, nowMs)?.let { if (key == "since") NetworkFilter(sinceMs = it) else NetworkFilter(untilMs = it) }
                    ?: run { problem("$key: takes a time ago (5m, 2h, 1d) or epoch milliseconds; was '$value'"); continue }
                else -> error(key)
            }
            if (raw.negated && key != "has") exclude += one
            else filter = once(filter, key, seen, ::problem) { merge(it, one) }
            tokens += QueryToken(raw.start, raw.end, raw.text, key, raw.negated && key != "has" || (key == "has" && raw.negated))
        }
        return ParsedQuery(filter, exclude, terms, problems, tokens)
    }

    /** Filters that only make sense for the session they were typed in: never saved (feature 043). */
    val SESSION_KEYS = setOf("run", "since", "until")

    /** Feature 043: [words] the line's savable filter words in order, [rest] the line without them. */
    @Serializable
    data class Commit(val words: List<String>, val rest: String)

    /**
     * Feature 043 (Enter): the filter words that can be saved — valid, not session-only — taken
     * out of the line; search words, words with problems and session-only filters stay.
     */
    fun commit(q: String, nowMs: Long = System.currentTimeMillis()): Commit {
        val p = parse(q, nowMs)
        val bad = p.problems.map { it.start until it.end }
        val take = p.tokens.filter { t -> t.key != null && t.key !in SESSION_KEYS && bad.none { r -> t.start in r || (t.end - 1) in r } }
        var rest = q
        take.sortedByDescending { it.start }.forEach { rest = rest.removeRange(it.start, it.end) }
        return Commit(take.map { it.text }, rest.trim().replace(Regex("\\s{2,}"), " "))
    }

    /** One word that [commit] would save. */
    fun savable(word: String): Boolean = commit(word).let { it.words == listOf(word.trim()) && it.rest.isEmpty() }

    /** The line for a parsed query, in canonical order: filters, exclusions, search words. */
    fun render(p: ParsedQuery): String = render(p.filter, p.exclude, p.terms)

    fun render(filter: NetworkFilter, exclude: List<NetworkFilter>, terms: List<Term>): String =
        (words(filter, false) + exclude.flatMap { words(it, true) } + terms.map { if (it.phrase) "\"${it.text}\"" else it.text }).joinToString(" ")

    private fun words(f: NetworkFilter, negated: Boolean): List<String> {
        val n = if (negated) "-" else ""
        return buildList {
            f.method?.let { add(if (!negated && it in METHODS) it else "${n}method:$it") }
            f.status?.let { add("${n}status:$it") }
            f.host?.let { add("${n}host:${quote(it)}") }
            f.url?.let { add("${n}url:${quote(it)}") }
            f.kinds?.let { k ->
                val name = when (k.toSet()) { WS.toSet() -> "ws"; setOf(EntryKind.HTTP) -> "http"; else -> null }
                if (name != null) add(if (negated) "-kind:$name" else name) else k.forEach { add("${n}kind:${kindName(it)}") }
            }
            f.intervened?.let { add(if (it) "has:intervention" else "-has:intervention") }
            f.bodyContains?.let { add("${n}body:${quote(it)}") }
            f.runs?.let { add("run:${if (it == RunScope.CURRENT) "current" else "all"}") }
            f.sinceMs?.let { add("since:$it") }
            f.untilMs?.let { add("until:$it") }
        }
    }

    private fun kindName(k: EntryKind) = when (k) {
        EntryKind.HTTP -> "http"; EntryKind.WS_CONNECTION -> "ws-connection"; EntryKind.WS_MESSAGE -> "ws-message"; EntryKind.LINK -> "link"
    }

    private fun quote(v: String) = if (v.any { it.isWhitespace() || it == '"' }) "\"${v.replace("\"", "")}\"" else v

    private fun kinds(v: String): List<EntryKind>? = when (v) {
        "http" -> listOf(EntryKind.HTTP)
        "ws" -> WS
        "ws-connection" -> listOf(EntryKind.WS_CONNECTION)
        "ws-message" -> listOf(EntryKind.WS_MESSAGE)
        else -> null
    }

    private fun time(v: String, now: Long): Long? {
        RELATIVE.matchEntire(v)?.let { m ->
            val n = m.groupValues[1].toLong()
            val unit = when (m.groupValues[2]) { "s" -> 1_000L; "m" -> 60_000L; "h" -> 3_600_000L; else -> 86_400_000L }
            return now - n * unit
        }
        return v.toLongOrNull()
    }

    private inline fun once(filter: NetworkFilter, key: String, seen: MutableSet<String>, problem: (String) -> Unit, set: (NetworkFilter) -> NetworkFilter): NetworkFilter {
        if (!seen.add(key)) { problem("$key: given twice; one value per filter (use -$key: to leave some out)"); return filter }
        return set(filter)
    }

    private fun merge(a: NetworkFilter, b: NetworkFilter) = a.copy(
        method = b.method ?: a.method, status = b.status ?: a.status, host = b.host ?: a.host, url = b.url ?: a.url,
        kinds = b.kinds ?: a.kinds, intervened = b.intervened ?: a.intervened, bodyContains = b.bodyContains ?: a.bodyContains,
        runs = b.runs ?: a.runs, sinceMs = b.sinceMs ?: a.sinceMs, untilMs = b.untilMs ?: a.untilMs,
    )

    /** Words split on spaces; quotes group (a quote left open runs to the end); `key:"a b"` keeps its value whole. */
    private fun split(q: String): List<Raw> {
        val out = ArrayList<Raw>()
        var i = 0
        while (i < q.length) {
            if (q[i].isWhitespace()) { i++; continue }
            val start = i
            if (q[i] == '"') {
                val close = q.indexOf('"', i + 1).let { if (it < 0) q.length else it }
                val text = q.substring(i + 1, close)
                i = (close + 1).coerceAtMost(q.length)
                if (text.isNotBlank()) out += Raw(start, i, text, true, null, null, false)
                continue
            }
            var inQuote = false
            while (i < q.length && (inQuote || !q[i].isWhitespace())) { if (q[i] == '"') inQuote = !inQuote; i++ }
            val word = q.substring(start, i)
            val negated = word.startsWith("-")
            val body = if (negated) word.drop(1) else word
            val colon = body.indexOf(':')
            if (colon > 0 && body.substring(0, colon).all { it.isLetter() }) {
                out += Raw(start, i, word, false, body.substring(0, colon).lowercase(), body.substring(colon + 1).removeSurrounding("\""), negated)
            } else out += Raw(start, i, word, false, null, null, false)
        }
        return out
    }

    /** Levenshtein distance (short strings: keys and words). */
    fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}
