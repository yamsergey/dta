package io.yamsergey.dta.sidekick.network.script

import io.yamsergey.dta.protocol.network.ScriptHandler
import io.yamsergey.dta.protocol.network.ScriptLogLevel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 020 (US2): the script pushes server messages to connections, from any handler. */
class ScriptPushTest {

    private data class Push(val connectionId: String, val text: String?, val bytes: ByteArray?, val scriptId: String, val handler: ScriptHandler?, val delayMs: Long)

    private val pushes = mutableListOf<Push>()
    private val failLater = mutableMapOf<String, (String) -> Unit>()
    private var refuse: String? = null
    private val host = object : ScriptHost {
        override fun push(connectionId: String, text: String?, bytes: ByteArray?, scriptId: String, handler: ScriptHandler?, delayMs: Long, onFailure: (String) -> Unit) {
            refuse?.let { throw IllegalStateException(it) }
            pushes += Push(connectionId, text, bytes, scriptId, handler, delayMs)
            failLater[connectionId] = onFailure
        }
        override fun connections() = listOf(ScriptConnection("ws://h/a", "okhttp3-ws", "r1-1:5"), ScriptConnection("wss://h/b", "nv-websocket", "r1-1:8"))
    }
    private val logs = mutableListOf<Triple<ScriptLogLevel, ScriptHandler?, String>>()

    private fun compile(source: String) =
        ScriptEngine.compile(source, host = { host }) { level, handler, message -> logs += Triple(level, handler, message) }

    private val conn = ScriptConnection("ws://h/a", "okhttp3-ws", "r1-1:5")

    @Test
    fun `conn push in onWsSend replies on the same connection, naming the script and handler`() {
        val source = """function onWsSend(msg, conn) { if (msg.text.indexOf("sub:") === 0) conn.push("snapshot:" + msg.text.substring(4), {delayMs: 200}); }"""
        val script = compile(source)
        val result = script.onWsSend(ScriptMessage.text("sub:42"), conn)
        assertNull(result.error)
        val push = pushes.single()
        assertEquals(Push("r1-1:5", "snapshot:42", null, ScriptRuntime.idOf(source), ScriptHandler.WS_SEND, 200), push)
    }

    @Test
    fun `messages convert - text, bytes, arrays of numbers, objects as JSON`() {
        compile(
            """
            function onWsReceive(msg, conn) {
              conn.push("t");
              conn.push(msg.bytes());
              conn.push([1, 2, 255]);
              conn.push({topic: "x", v: 1});
              conn.push(7);
            }
            """,
        ).onWsReceive(ScriptMessage.binary(byteArrayOf(9, 8)), conn)
        assertEquals("t", pushes[0].text)
        assertContentEquals(byteArrayOf(9, 8), pushes[1].bytes)
        assertContentEquals(byteArrayOf(1, 2, -1), pushes[2].bytes)
        assertEquals("""{"topic":"x","v":1}""", pushes[3].text)
        assertEquals("7", pushes[4].text)
        assertTrue(pushes.all { it.handler == ScriptHandler.WS_RECEIVE && it.delayMs == 0L })
    }

    @Test
    fun `global push takes a connection or its id, and connections lists the open ones`() {
        compile(
            """
            function onRequest(req) {
              var all = connections();
              push(all[1], "to-nv");
              push("r1-1:5", "to-ok");
              all[0].push("via-view");
              log(all.length + " " + all[1].url + " " + all[1].library);
            }
            """,
        ).onRequest(ScriptRequest("https://h", "GET", emptyList(), "okhttp3", "r1-1:9", null))
        assertEquals(listOf("r1-1:8" to "to-nv", "r1-1:5" to "to-ok", "r1-1:5" to "via-view"), pushes.map { it.connectionId to it.text })
        assertEquals(ScriptHandler.REQUEST, pushes[0].handler)
        assertEquals("2 wss://h/b nv-websocket", logs.single().third)
    }

    @Test
    fun `a push that cannot start or fails later goes to the log, the handler still runs`() {
        refuse = "connection r1-1:5 is closed (code 1000); push to an open connection"
        val result = compile("""function onWsSend(msg, conn) { var ok = conn.push("x"); msg.text = "after:" + ok; return msg; }""")
            .onWsSend(ScriptMessage.text("a"), conn)
        assertNull(result.error)
        assertEquals("after:false", ((result.effect as Effect.Change<*>).change as MessageChange).text)
        assertEquals(Triple(ScriptLogLevel.ERROR, ScriptHandler.WS_SEND, "push to r1-1:5 failed: connection r1-1:5 is closed (code 1000); push to an open connection"), logs.single())

        refuse = null
        logs.clear()
        compile("""function onWsSend(msg, conn) { conn.push("x"); }""").onWsSend(ScriptMessage.text("a"), conn)
        failLater.getValue("r1-1:5")("connection r1-1:5 failed before the message was delivered")
        assertEquals(Triple(ScriptLogLevel.ERROR, ScriptHandler.WS_SEND, "push to r1-1:5 failed: connection r1-1:5 failed before the message was delivered"), logs.single())
    }

    @Test
    fun `without a host push says pushes are not available here`() {
        val script = ScriptEngine.compile("""function onWsSend(msg, conn) { conn.push("x"); }""") { level, handler, message -> logs += Triple(level, handler, message) }
        script.onWsSend(ScriptMessage.text("a"), conn)
        assertEquals("push to r1-1:5 failed: WebSocket pushes are not available in this app (DTA's network capture is not running)", logs.single().third)
    }

    @Test
    fun `the documented push recipes work as written`() {
        compile(
            """
            function onWsSend(msg, conn) {
              if (msg.text && msg.text.startsWith("sub:")) conn.push({topic: msg.text.substring(4), items: []}, {delayMs: 200});
            }
            """,
        ).onWsSend(ScriptMessage.text("sub:prices"), conn)
        assertEquals("""{"topic":"prices","items":[]}""", pushes.single().text)
        assertEquals(200, pushes.single().delayMs)

        pushes.clear()
        val dropped = compile("""function onWsSend(msg, conn) { if (msg.text === "sub:prices") { conn.push("snapshot:prices"); return drop(); } }""")
            .onWsSend(ScriptMessage.text("sub:prices"), conn)
        assertEquals(Effect.Drop, dropped.effect)
        assertEquals("snapshot:prices", pushes.single().text)

        pushes.clear()
        val scheduler = io.yamsergey.dta.sidekick.util.ManualScheduler()
        ScriptEngine.compile(
            """
            let price = 100;
            every(1000, () => { price++; connections().forEach(c => push(c, {price: price})); });
            """,
            host = { host }, scheduler = scheduler,
        ) { level, handler, message -> logs += Triple(level, handler, message) }
        scheduler.advance(2000)
        assertEquals(listOf("""{"price":101}""", """{"price":101}""", """{"price":102}""", """{"price":102}"""), pushes.map { it.text }, "one price per tick, on both connections")
    }
}
