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
    /** Feature 051: a filter key as suggestions show it: what it matches and which kinds of entries it applies to. */
    data class KeyInfo(val name: String, val hint: String, val kinds: Set<EntryKind>)

    private val ALL = setOf(EntryKind.HTTP, EntryKind.WS_CONNECTION, EntryKind.WS_MESSAGE)
    private val HTTP_ONLY = setOf(EntryKind.HTTP)
    private val MESSAGES = setOf(EntryKind.WS_MESSAGE)

    val KEY_INFO = listOf(
        KeyInfo("body", "text in a body: request, response or message (case ignored); /regex/ for a pattern", ALL),
        KeyInfo("url", "text in the URL (a message: its socket's)", ALL),
        KeyInfo("host", "the URL's host; *.example.com takes subdomains", ALL),
        KeyInfo("status", "HTTP 200, 4xx, failed, pending; socket open, closed, connecting, a close code", setOf(EntryKind.HTTP, EntryKind.WS_CONNECTION)),
        KeyInfo("method", "HTTP method", HTTP_ONLY),
        KeyInfo("header", "a header by name, or name=text in its value (HTTP, socket handshake)", setOf(EntryKind.HTTP, EntryKind.WS_CONNECTION)),
        KeyInfo("req", "text or /regex/ in the HTTP request body", HTTP_ONLY),
        KeyInfo("resp", "text or /regex/ in the HTTP response body", HTTP_ONLY),
        KeyInfo("mime", "text in a body's content type: json, html, protobuf", setOf(EntryKind.HTTP, EntryKind.WS_MESSAGE)),
        KeyInfo("duration", "HTTP call time: >1s, <200ms", HTTP_ONLY),
        KeyInfo("size", "body bytes: >100k, <=1k", setOf(EntryKind.HTTP, EntryKind.WS_MESSAGE)),
        KeyInfo("dir", "socket messages sent or received", MESSAGES),
        KeyInfo("frame", "socket messages: text or binary", MESSAGES),
        KeyInfo("type", "text in a socket message's type, as its format reads it", MESSAGES),
        KeyInfo("topic", "text in a socket message's topic or channel", MESSAGES),
        KeyInfo("conn", "one socket connection and its messages, by id", setOf(EntryKind.WS_CONNECTION, EntryKind.WS_MESSAGE)),
        KeyInfo("kind", "http, ws, ws-connection, ws-message", ALL),
        KeyInfo("has", "intervention: entries the network script changed", ALL),
        KeyInfo("run", "current or all app runs (this session only)", ALL),
        KeyInfo("since", "newer than: 5m, 2h, 1d (this session only)", ALL),
        KeyInfo("until", "older than: 5m, 2h, 1d (this session only)", ALL),
    )
    val KEYS = KEY_INFO.map { it.name }
    val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
    private val NOT_NEGATABLE = setOf("run", "since", "until", "has")
    private val STATUS = Regex("""[1-5]\d\d|[1-5]xx|failed|pending|open|closed|connecting|[1-4]\d\d\d""")
    private val BOUND = Regex("""(>=|<=|>|<)(\d+)([a-z]*)""")
    private val RELATIVE = Regex("""(\d+)([smhd])""")
    private val WS = listOf(EntryKind.WS_CONNECTION, EntryKind.WS_MESSAGE)

    /** [pattern]: feature 053, the text between the slashes of `body:/…/` (also req, resp). */
    private class Raw(val start: Int, val end: Int, val text: String, val quoted: Boolean, val key: String?, val value: String?, val negated: Boolean, val pattern: String? = null)

    /** Feature 053: keys whose `/…/` value is a pattern. */
    val PATTERN_KEYS = setOf("body", "req", "resp")

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
            raw.pattern?.let { text ->
                when (val c = BodyPattern.compile(text)) {
                    is BodyPattern.Result.Bad -> { problem("$key: ${c.reason}"); continue }
                    is BodyPattern.Result.Ok -> Unit
                }
            }
            if (value.isEmpty()) { problem("$key: needs a value"); continue }
            if (raw.negated && key in NOT_NEGATABLE && key != "has") { problem("$key: cannot be negated"); continue }
            val one: NetworkFilter = when (key) {
                "method" -> NetworkFilter(method = value.uppercase())
                "status" -> if (STATUS.matches(value)) NetworkFilter(status = value) else { problem("status: takes a code (200), a class (4xx), failed, pending, or for sockets open, closed, connecting, a close code (1006); was '$value'"); continue }
                "dir" -> when (value) { "sent" -> NetworkFilter(direction = Direction.OUT); "received" -> NetworkFilter(direction = Direction.IN); else -> { problem("dir: takes sent or received; was '$value'"); continue } }
                "frame" -> when (value) { "text" -> NetworkFilter(frame = MessageOpcode.TEXT); "binary" -> NetworkFilter(frame = MessageOpcode.BINARY); else -> { problem("frame: takes text or binary; was '$value'"); continue } }
                "type" -> NetworkFilter(envelopeType = value)
                "topic" -> NetworkFilter(topic = value)
                "conn" -> NetworkFilter(connection = value)
                "mime" -> NetworkFilter(mime = value)
                "req" -> raw.pattern?.let { NetworkFilter(requestPattern = it) } ?: NetworkFilter(requestContains = value)
                "resp" -> raw.pattern?.let { NetworkFilter(responsePattern = it) } ?: NetworkFilter(responseContains = value)
                "header" -> {
                    val name = value.substringBefore('=').trim()
                    if (name.isEmpty()) { problem("header: takes a name, or name=text; was '$value'"); continue }
                    NetworkFilter(headerName = name.lowercase(), headerValue = value.substringAfter('=', "").takeIf { '=' in value })
                }
                "duration", "size" -> bound(key, value)?.let { (lower, n) ->
                    if (key == "duration") { if (lower) NetworkFilter(minDurationMs = n) else NetworkFilter(maxDurationMs = n) }
                    else { if (lower) NetworkFilter(minSize = n) else NetworkFilter(maxSize = n) }
                } ?: run {
                    problem(if (key == "duration") "duration: takes >, >=, < or <= and a time (200ms, 1s, 2m); was '$value'" else "size: takes >, >=, < or <= and bytes (500, 10k, 2m); was '$value'")
                    continue
                }
                "host" -> NetworkFilter(host = value)
                "url" -> NetworkFilter(url = value)
                "body" -> raw.pattern?.let { NetworkFilter(bodyPattern = it) } ?: NetworkFilter(bodyContains = value)
                "kind" -> kinds(value)?.let { NetworkFilter(kinds = it) } ?: run { problem("kind: takes http, ws, ws-connection or ws-message; was '$value'"); continue }
                "has" -> if (value == "intervention") NetworkFilter(intervened = !raw.negated) else { problem("has: takes intervention; was '$value'"); continue }
                "run" -> if (value == "current") NetworkFilter(runs = RunScope.CURRENT) else if (value == "all") NetworkFilter(runs = RunScope.ALL) else { problem("run: takes current or all; was '$value'"); continue }
                "since", "until" -> time(value, nowMs)?.let { if (key == "since") NetworkFilter(sinceMs = it) else NetworkFilter(untilMs = it) }
                    ?: run { problem("$key: takes a time ago (5m, 2h, 1d) or epoch milliseconds; was '$value'"); continue }
                else -> error(key)
            }
            val onceKey = when (key) {
                "duration" -> if (one.minDurationMs != null) "duration>" else "duration<"
                "size" -> if (one.minSize != null) "size>" else "size<"
                else -> key
            }
            if (raw.negated && key != "has") exclude += one
            else filter = once(filter, onceKey, seen, ::problem) { it.overlay(one) }
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
            f.bodyContains?.let { add("${n}body:${literal(it)}") }
            f.requestContains?.let { add("${n}req:${literal(it)}") }
            f.responseContains?.let { add("${n}resp:${literal(it)}") }
            f.bodyPattern?.let { add("${n}body:/$it/") }
            f.requestPattern?.let { add("${n}req:/$it/") }
            f.responsePattern?.let { add("${n}resp:/$it/") }
            f.headerName?.let { add("${n}header:" + quote(it + (f.headerValue?.let { v -> "=$v" } ?: ""))) }
            f.mime?.let { add("${n}mime:${quote(it)}") }
            f.minDurationMs?.let { add("${n}duration:>=${duration(it)}") }
            f.maxDurationMs?.let { add("${n}duration:<=${duration(it)}") }
            f.minSize?.let { add("${n}size:>=$it") }
            f.maxSize?.let { add("${n}size:<=$it") }
            f.direction?.let { add("${n}dir:${if (it == Direction.OUT) "sent" else "received"}") }
            f.frame?.let { add("${n}frame:${if (it == MessageOpcode.TEXT) "text" else "binary"}") }
            f.envelopeType?.let { add("${n}type:${quote(it)}") }
            f.topic?.let { add("${n}topic:${quote(it)}") }
            f.connection?.let { add("${n}conn:${quote(it)}") }
            f.runs?.let { add("run:${if (it == RunScope.CURRENT) "current" else "all"}") }
            f.sinceMs?.let { add("since:$it") }
            f.untilMs?.let { add("until:$it") }
        }
    }

    private fun kindName(k: EntryKind) = when (k) {
        EntryKind.HTTP -> "http"; EntryKind.WS_CONNECTION -> "ws-connection"; EntryKind.WS_MESSAGE -> "ws-message"; EntryKind.LINK -> "link"
    }

    /** Feature 053: body text that would read as a pattern is quoted. */
    private fun literal(v: String) = if (v.length >= 2 && v.startsWith('/') && v.endsWith('/') && !v.any { it.isWhitespace() || it == '"' }) "\"$v\"" else quote(v)

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
        if (!seen.add(key)) { val k = key.trimEnd('>', '<'); problem("$k: given twice; one value per filter (use -$k: to leave some out)"); return filter }
        return set(filter)
    }

    /** `>1s` → (lower bound, 1001 ms); `<=10k` → (upper bound, 10240); inclusive bounds. */
    private fun bound(key: String, v: String): Pair<Boolean, Long>? {
        val m = BOUND.matchEntire(v) ?: return null
        val (op, digits, unit) = m.destructured
        val n = digits.toLongOrNull() ?: return null
        val scale = if (key == "duration") when (unit) { "ms" -> 1L; "s" -> 1_000L; "m" -> 60_000L; else -> return null }
            else when (unit) { "" -> 1L; "k" -> 1024L; "m" -> 1024L * 1024; else -> return null }
        val x = n * scale
        return when (op) { ">" -> true to x + 1; ">=" -> true to x; "<" -> false to x - 1; else -> false to x }
    }

    private fun duration(ms: Long): String = when {
        ms % 60_000 == 0L && ms > 0 -> "${ms / 60_000}m"
        ms % 1_000 == 0L && ms > 0 -> "${ms / 1_000}s"
        else -> "${ms}ms"
    }

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
            slashed(q, start)?.let { (end, key, pattern) ->
                val word = q.substring(start, end)
                out += Raw(start, end, word, false, key, "/$pattern/", word.startsWith("-"), pattern)
                i = end
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

    /**
     * Feature 053: `body:/…/` (req, resp too; `-` allowed) at [start]: runs to the first unescaped
     * `/` that ends a word, spaces included. Null when the word is not one (no such slash: literal text).
     */
    private fun slashed(q: String, start: Int): Triple<Int, String, String>? {
        val keyStart = if (q[start] == '-') start + 1 else start
        val colon = q.indexOf(':', keyStart).takeIf { it > keyStart } ?: return null
        val key = q.substring(keyStart, colon).lowercase()
        if (key !in PATTERN_KEYS || q.substring(keyStart, colon).any { !it.isLetter() }) return null
        val open = colon + 1
        if (open >= q.length || q[open] != '/') return null
        var j = open + 1
        while (j < q.length) {
            if (q[j] == '\\') { j += 2; continue }
            if (q[j] == '/' && (j + 1 == q.length || q[j + 1].isWhitespace())) return Triple(j + 1, key, q.substring(open + 1, j))
            j++
        }
        return null
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
