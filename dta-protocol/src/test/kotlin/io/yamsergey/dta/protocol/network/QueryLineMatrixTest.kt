package io.yamsergey.dta.protocol.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The query line over a mixed history with several hosts, every filter word both ways: a word and its
 * exclusion split the history (`-key:v` keeps exactly what `key:v` leaves out), the line as written,
 * saved filters merged in, and the line rendered back reads the same.
 */
class QueryLineMatrixTest {
    private fun body(part: BodyPart, text: String?, type: String? = null, binary: Boolean = false, bytes: Long = 10) =
        BodyInfo(part, storedBytes = bytes, contentType = type, binary = binary, completeness = Completeness.COMPLETE, preview = text)

    private val run = "r2-1"
    private val get = HttpEntry("e1", run, 100, "okhttp3", method = "GET", url = "https://api.example.test/v1/users?id=7", state = HttpState.COMPLETE, status = 200, durationMs = 120,
        requestHeaders = listOf(Header("Authorization", "Bearer abc")), responseHeaders = listOf(Header("Content-Type", "application/json")),
        responseBody = body(BodyPart.RESPONSE, """{"name":"Ada","id":7}""", "application/json", bytes = 2048))
    private val post = HttpEntry("e2", run, 200, "okhttp3", method = "POST", url = "https://api.example.test/v1/orders", state = HttpState.COMPLETE, status = 404, durationMs = 1500,
        requestHeaders = listOf(Header("Content-Type", "text/plain")), requestBody = body(BodyPart.REQUEST, "order=42", "text/plain"))
    private val cdn = HttpEntry("e3", run, 300, "okhttp3", method = "GET", url = "https://cdn.example.test/img/a.png", state = HttpState.FAILED, failure = "timeout")
    private val sub = HttpEntry("e4", run, 400, "okhttp3", method = "PUT", url = "https://eu.cdn.example.test:8443/up", state = HttpState.PENDING)
    private val web = HttpEntry("e5", run, 450, NetworkLibraries.WEBVIEW, method = "GET", url = "https://web.example.test/page", state = HttpState.COMPLETE, status = 503, durationMs = 40,
        intervention = InterventionRecord("s1"))
    private val socket = WsConnectionEntry("e6", run, 500, "okhttp3-ws", url = "wss://push.example.test/socket", state = WsState.CLOSED, closeCode = 1006,
        handshakeHeaders = listOf(Header("Sec-WebSocket-Protocol", "v2")))
    private val sent = WsMessageEntry("e7", run, 600, "okhttp3-ws", connectionId = socket.id, direction = Direction.OUT, opcode = MessageOpcode.TEXT,
        payload = body(BodyPart.MESSAGE, """{"type":"subscribe","topic":"prices"}"""), url = socket.url, envelope = Envelope("json", kind = "subscribe", topic = "prices"))
    private val received = WsMessageEntry("e8", run, 700, "okhttp3-ws", connectionId = socket.id, direction = Direction.IN, opcode = MessageOpcode.BINARY,
        payload = body(BodyPart.MESSAGE, null, binary = true, bytes = 300_000), url = socket.url)
    private val older = HttpEntry("e9", "r1-1", 50, "okhttp3", method = "DELETE", url = "http://localhost:8080/session", state = HttpState.PROCESS_ENDED)

    private val all = listOf(older, get, post, cdn, sub, web, socket, sent, received)
    private val ids = all.map { it.id }

    private fun run(line: String, now: Long = 1_000): List<String> {
        val (filter, terms) = NetworkQuery(q = line).resolved(now)
        assertTrue(terms.isEmpty(), "only filters here: $line")
        val eval = NetworkFilterEval(filter ?: NetworkFilter(), run, connectionUrl = { id -> (all.single { it.id == id } as WsConnectionEntry).url }) { e, part ->
            NetworkHistoryView.bodyOf(e, part)?.preview?.let { NetworkFilterEval.BodyBytes(it.toByteArray(), true) }
        }
        return all.filter { eval.matches(it) != null }.map { it.id }
    }

    /** Each word with what it keeps. */
    private val words = mapOf(
        "host:api.example.test" to listOf("e1", "e2"),
        "host:*.example.test" to listOf("e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8"),
        "host:*.cdn.example.test" to listOf("e4"),
        "host:push.example.test" to listOf("e6", "e7", "e8"),
        "host:localhost" to listOf("e9"),
        "url:/v1/" to listOf("e1", "e2"),
        "url:socket" to listOf("e6", "e7", "e8"),
        "method:get" to listOf("e1", "e3", "e5"),
        "POST" to listOf("e2"),
        "status:200" to listOf("e1"),
        "status:4xx" to listOf("e2"),
        "status:5xx" to listOf("e5"),
        "status:failed" to listOf("e3"),
        "status:pending" to listOf("e4"),
        "status:closed" to listOf("e6"),
        "status:1006" to listOf("e6"),
        "header:authorization" to listOf("e1"),
        "header:content-type=json" to listOf("e1"),
        "header:sec-websocket-protocol" to listOf("e6"),
        "body:ada" to listOf("e1"),
        "body:/\"id\":\\s*7/" to listOf("e1"),
        "req:order" to listOf("e2"),
        "resp:ada" to listOf("e1"),
        "mime:json" to listOf("e1"),
        "duration:>1s" to listOf("e2"),
        "duration:<200ms" to listOf("e1", "e5"),
        "size:>100k" to listOf("e8"),
        "size:<1k" to listOf("e9", "e2", "e3", "e4", "e5", "e7"), // no body is 0 bytes
        "dir:sent" to listOf("e7"),
        "dir:received" to listOf("e8"),
        "frame:text" to listOf("e7"),
        "frame:binary" to listOf("e8"),
        "type:subscribe" to listOf("e7"),
        "topic:prices" to listOf("e7"),
        "conn:e6" to listOf("e6", "e7", "e8"),
        "kind:http" to listOf("e9", "e1", "e2", "e3", "e4", "e5"),
        "ws" to listOf("e6", "e7", "e8"),
        "kind:ws-message" to listOf("e7", "e8"),
        "source:webview" to listOf("e5"),
        "source:app" to listOf("e9", "e1", "e2", "e3", "e4", "e6", "e7", "e8"),
    )

    @Test
    fun `each word keeps its entries, and its exclusion keeps exactly the rest`() {
        for ((word, kept) in words) {
            assertEquals(kept, run(word), word)
            val negated = if (':' in word) "-$word" else "-" + QueryLine.parse(word).tokens.single().key + ":" + word.lowercase()
            assertEquals(ids - kept.toSet(), run(negated), negated)
        }
    }

    @Test
    fun `has, run, since and until`() {
        assertEquals(listOf("e5"), run("has:intervention"))
        assertEquals(ids - "e5", run("-has:intervention"))
        assertEquals(ids - "e9", run("run:current"))
        assertEquals(ids, run("run:all"))
        assertEquals(listOf("e6", "e7", "e8"), run("since:500"))
        assertEquals(listOf("e8"), run("since:1s", now = 1_700), "a time ago")
        assertEquals(listOf("e9", "e1"), run("until:100"))
    }

    @Test
    fun `words together narrow, exclusions add up, and order does not matter`() {
        assertEquals(listOf("e1"), run("host:api.example.test GET"))
        assertEquals(listOf("e1"), run("GET host:api.example.test"))
        assertEquals(listOf("e9", "e5"), run("http -host:api.example.test -host:*.cdn.example.test -host:cdn.example.test"))
        assertEquals(listOf("e3", "e4", "e5"), run("host:*.example.test -host:api.example.test -kind:ws"))
        assertEquals(emptyList(), run("host:api.example.test -host:api.example.test"))
        assertEquals(listOf("e1", "e2"), run("http -status:failed -status:pending -source:webview -host:localhost"))
        assertEquals(listOf("e7"), run("ws -frame:binary -kind:ws-connection"))
    }

    @Test
    fun `saved filters merge with the line - exclusions always apply, a key typed in the line replaces the saved one`() {
        fun merged(saved: List<String>, typed: String?) = run(SavedFilters.merge(saved, typed).q ?: "")
        assertEquals(ids - listOf("e1", "e2"), merged(listOf("-host:api.example.test"), null))
        assertEquals(listOf("e3"), merged(listOf("-host:api.example.test"), "status:failed"))
        assertEquals(listOf("e6", "e7", "e8"), merged(listOf("host:api.example.test"), "host:push.example.test"), "the typed host replaces the saved one")
        assertEquals(listOf("e2"), merged(listOf("host:api.example.test", "-status:200"), null))
        assertEquals(emptyList(), merged(listOf("-host:api.example.test"), "host:api.example.test"), "a saved exclusion still applies")
    }

    @Test
    fun `quoting and patterns read as written`() {
        assertEquals(listOf("e1"), run("body:\"Ada\""))
        assertEquals(listOf("e2"), run("body:\"order=42\""))
        assertEquals(listOf("e2"), run("req:\"order=42\""))
        assertEquals(listOf("e1"), run("body:/ADA/"), "patterns ignore case")
        assertEquals(emptyList(), run("body:/(?-i)ADA/"))
        assertEquals(ids - "e1", run("-body:/ada/"))
        assertEquals(emptyList(), run("body:\"/ada/\""), "quoted slashes are literal text")
    }

    @Test
    fun `a line rendered back parses to the same filters`() {
        val lines = words.keys.toList() + words.keys.filter { ':' in it && !it.startsWith("kind") && !it.startsWith("source:app") }.map { "-$it" } +
            listOf("GET host:api.example.test -host:cdn.example.test -status:5xx", "ws -dir:sent topic:prices")
        for (line in lines) {
            val p = QueryLine.parse(line)
            assertEquals(emptyList(), p.problems, line)
            val again = QueryLine.parse(QueryLine.render(p))
            assertEquals(p.filter, again.filter, line)
            assertEquals(p.exclude, again.exclude, line)
        }
    }

    @Test
    fun `mistakes are named, never read as something else`() {
        for (bad in listOf("hots:a", "-hots:a", "status:abc", "-status:", "host:", "dir:up", "frame:json", "kind:tcp", "source:web", "-run:all", "-since:5m", "duration:1s", "size:big", "-kin", "body:/[/")) {
            assertTrue(QueryLine.parse(bad).problems.isNotEmpty(), bad)
        }
        assertTrue(QueryLine.parse("host:a host:b").problems.single().message.contains("given twice"))
        assertEquals(emptyList(), QueryLine.parse("-host:a -host:b").problems, "exclusions may repeat")
    }
}
