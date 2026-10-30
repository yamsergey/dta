package io.yamsergey.dta.sidekick.network.script

import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.ScriptHandler
import io.yamsergey.dta.protocol.network.ScriptLogLevel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScriptEngineTest {

    private val logs = mutableListOf<Triple<ScriptLogLevel, ScriptHandler?, String>>()

    private fun compile(source: String) = ScriptEngine.compile(source) { level, handler, message -> logs += Triple(level, handler, message) }

    private var bodyReads = 0

    private fun request(
        url: String = "https://h/api/feed?x=1",
        body: String? = """{"user":"ana","n":1}""",
        contentType: String? = "application/json",
        binary: Boolean = false,
    ) = ScriptRequest(
        url = url,
        method = "POST",
        headers = listOf(Header("Accept", "*/*"), Header("Content-Type", contentType ?: "")),
        library = "okhttp3",
        id = "r1-1:1",
        body = body?.let { b -> BodySource(contentType, binary) { bodyReads++; b.toByteArray() } },
    )

    @Test
    fun `a syntax error is reported with line and column`() {
        val e = assertFailsWith<ScriptCompileException> { compile("function onRequest(req) {\n  return req.;\n}") }
        assertEquals(2, e.line)
        assertTrue(e.column > 0, "column ${e.column}")
        assertTrue(e.message!!.isNotBlank(), "relocated Rhino resource bundles produce a message")
    }

    @Test
    fun `defined handlers are discovered`() {
        assertEquals(setOf(ScriptHandler.REQUEST, ScriptHandler.WS_RECEIVE), compile("function onRequest(r){}\nfunction onWsReceive(m,c){}").handlers)
    }

    @Test
    fun `a script with no handler is rejected`() {
        assertFailsWith<ScriptCompileException> { compile("var x = 1;") }
    }

    @Test
    fun `undefined and an unchanged object mean no change`() {
        assertEquals(Effect.None, compile("function onRequest(req) {}").onRequest(request()).effect)
        assertEquals(Effect.None, compile("function onRequest(req) { return req; }").onRequest(request()).effect)
    }

    @Test
    fun `mutated fields come back as a change`() {
        val result = compile(
            """
            function onRequest(req) {
              req.url = req.url.replace("feed", "feed2");
              req.method = "PUT";
              req.headers["X-Debug"] = "1";
              req.body = "replaced";
              return req;
            }
            """,
        ).onRequest(request())
        val change = assertIs<Effect.Change<RequestChange>>(result.effect).change
        assertEquals("https://h/api/feed2?x=1", change.url)
        assertEquals("PUT", change.method)
        assertEquals("1", change.headers!!.single { it.name == "X-Debug" }.value)
        assertContentEquals("replaced".toByteArray(), change.body)
    }

    @Test
    fun `an object body is serialized as json`() {
        val change = assertIs<Effect.Change<RequestChange>>(
            compile("function onRequest(req) { req.body = {a: 1}; return req; }").onRequest(request()).effect,
        ).change
        assertEquals("""{"a":1}""", String(change.body!!))
    }

    @Test
    fun `respond is a mock and drop is a drop`() {
        val mock = compile("""function onRequest(req) { return respond({status: 503, headers: {"Retry-After": "5"}, body: "down"}); }""").onRequest(request())
        val response = assertIs<Effect.Mock>(mock.effect).response
        assertEquals(503, response.status)
        assertEquals(listOf(Header("Retry-After", "5")), response.headers)
        assertContentEquals("down".toByteArray(), response.body)
        assertEquals(Effect.Drop, compile("function onRequest(req) { return drop(); }").onRequest(request()).effect)
    }

    @Test
    fun `null is not a drop - it is an error and nothing changes`() {
        val result = compile("function onRequest(req) { return null; }").onRequest(request())
        assertEquals(Effect.None, result.effect)
        assertTrue("drop()" in result.error!!, result.error)
    }

    @Test
    fun `a throwing handler changes nothing and names the error`() {
        val result = compile("function onRequest(req) { throw new Error('boom'); }").onRequest(request())
        assertEquals(Effect.None, result.effect)
        assertTrue("boom" in result.error!!, result.error)
    }

    @Test
    fun `description says in the script's words what it did, kept short`() {
        assertEquals("slow auth", compile("function onRequest(req) { sleep(1); description('slow auth'); }").onRequest(request()).description)
        assertEquals("slow auth; then 503", compile("function onRequest(req) { description('slow auth'); description('then 503'); }").onRequest(request()).description)
        assertNull(compile("function onRequest(req) { description('  '); }").onRequest(request()).description)
        val long = compile("function onRequest(req) { description('x'.repeat(500)); }").onRequest(request()).description!!
        assertEquals(ScriptGlobals.DESCRIPTION_MAX, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `sleep is accounted as delay, not script time`() {
        val result = compile("function onRequest(req) { sleep(120); }").onRequest(request())
        assertTrue(result.delayMs in 120..400, "delay ${result.delayMs}")
        assertTrue(result.scriptMs < 120, "script ${result.scriptMs}")
    }

    @Test
    fun `bodies are read only when asked for`() {
        compile("function onRequest(req) { sleep(0); }").onRequest(request())
        assertEquals(0, bodyReads)
        val result = compile("""function onRequest(req) { if (req.json().user === "ana") return drop(); }""").onRequest(request())
        assertEquals(Effect.Drop, result.effect)
        assertEquals(1, bodyReads)
        val text = compile("""function onRequest(req) { if (req.text().indexOf("ana") >= 0) return drop(); }""").onRequest(request())
        assertEquals(Effect.Drop, text.effect)
    }

    @Test
    fun `binary bodies come as bytes and text access says it is binary`() {
        val binary = request(body = "\u0000\u0001", contentType = "application/x-protobuf", binary = true)
        val bytes = compile("function onRequest(req) { if (req.bytes().length === 2) return drop(); }").onRequest(binary)
        assertEquals(Effect.Drop, bytes.effect)
        val text = compile("function onRequest(req) { req.text(); }").onRequest(binary)
        assertTrue("binary" in text.error!!, text.error)
    }

    @Test
    fun `an unreadable body states why and can still be replaced`() {
        val oneShot = request().copy(body = BodySource("application/json", false, unreadable = "one-shot request body", reader = null))
        assertTrue("one-shot" in compile("function onRequest(req) { req.text(); }").onRequest(oneShot).error!!)
        val change = assertIs<Effect.Change<RequestChange>>(
            compile("function onRequest(req) { req.body = 'new'; return req; }").onRequest(oneShot).effect,
        ).change
        assertContentEquals("new".toByteArray(), change.body)
    }

    @Test
    fun `responses can change status headers and body and be throttled`() {
        val response = ScriptResponse(404, listOf(Header("Content-Type", "text/plain")), BodySource("text/plain", false) { "nope".toByteArray() })
        val result = compile(
            """
            function onResponse(res, req) {
              if (req.url.indexOf("feed") < 0) return;
              throttle(1024);
              res.status = 200;
              res.headers["X-Patched"] = "1";
              res.body = res.text() + "!";
              return res;
            }
            """,
        ).onResponse(response, request())
        val change = assertIs<Effect.Change<ResponseChange>>(result.effect).change
        assertEquals(200, change.status)
        assertNotNull(change.headers!!.singleOrNull { it.name == "X-Patched" })
        assertContentEquals("nope!".toByteArray(), change.body)
        assertEquals(1024, result.throttleBytesPerSecond)
    }

    @Test
    fun `throttle outside a response handler is an error`() {
        assertTrue("onResponse" in compile("function onRequest(req) { throttle(10); }").onRequest(request()).error!!)
    }

    @Test
    fun `messages can be rewritten or dropped`() {
        val script = compile(
            """
            function onWsSend(msg, conn) {
              if (msg.text === "ping") return drop();
              if (msg.text) { msg.text = msg.text.replace("v1", "v2"); return msg; }
            }
            function onWsReceive(msg, conn) { if (msg.binary && msg.bytes().length > 0 && conn.url.indexOf("ws") === 0) return msg; }
            """,
        )
        val conn = ScriptConnection("ws://h/ws", "okhttp3-ws", "r1-1:5")
        assertEquals(Effect.Drop, script.onWsSend(ScriptMessage.text("ping"), conn).effect)
        val change = assertIs<Effect.Change<MessageChange>>(script.onWsSend(ScriptMessage.text("v1-hello"), conn).effect).change
        assertEquals("v2-hello", change.text)
        assertEquals(Effect.None, script.onWsReceive(ScriptMessage.binary(byteArrayOf(1)), conn).effect)
    }

    @Test
    fun `undefined handlers do nothing`() {
        assertEquals(Effect.None, compile("function onRequest(req) {}").onWsSend(ScriptMessage.text("x"), ScriptConnection("ws://h", "nv-websocket", "c")).effect)
    }

    @Test
    fun `state persists across calls and log lines reach the sink`() {
        val script = compile(
            """
            function onRequest(req) {
              var n = (state.get("n") || 0) + 1; state.set("n", n);
              log("call", n);
              if (n % 3 === 0) return drop();
            }
            """,
        )
        val effects = (1..6).map { script.onRequest(request()).effect }
        assertEquals(listOf(false, false, true, false, false, true), effects.map { it == Effect.Drop })
        assertEquals("call 3", logs.filter { it.first == ScriptLogLevel.LOG }[2].third)
        assertEquals(ScriptHandler.REQUEST, logs.first().second)
    }

    @Test
    fun `headers keep multiple values joined and compare unchanged when untouched`() {
        val req = request().copy(headers = listOf(Header("Accept", "a"), Header("Accept", "b")))
        assertEquals(Effect.None, compile("function onRequest(req) { if (req.headers['Accept'] === 'a, b') return req; return drop(); }").onRequest(req).effect)
    }

    @Test
    fun `a missing body is null to the script`() {
        val noBody = request(body = null)
        assertEquals(Effect.Drop, compile("function onRequest(req) { if (req.text() === null) return drop(); }").onRequest(noBody).effect)
        assertNull(noBody.body)
    }
}
