package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.network.proto.SchemaException
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.FormatRuleSpec
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.NetworkEntryDetail
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.network.WsState
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageFormatsTest {
    private val dir = Files.createTempDirectory("formats").toFile()
    private val orders = FormatRuleSpec(name = "orders", urlPattern = "wss://api.example.test/live*", pattern = """^(?<id>\d+)\|(?<kind>\w+)\|(?<payload>.*)$""")

    private fun message(text: String, n: Int = 1, url: String? = "wss://api.example.test/live", direction: Direction = Direction.IN, binary: Boolean = false) = WsMessageEntry(
        "r1:$n", "r1", 1_000L + n, "okhttp3-ws", connectionId = "r1:0", direction = direction,
        opcode = if (binary) MessageOpcode.BINARY else MessageOpcode.TEXT,
        payload = BodyInfo(BodyPart.MESSAGE, text.length.toLong(), binary = binary, completeness = Completeness.COMPLETE, preview = if (binary) null else text),
        url = url,
    )

    @Test
    fun `a rule is stored, survives a restart and is removed`() {
        val f = MessageFormats(dir) { 7 }
        val rule = f.add("app", orders)
        assertEquals(listOf("f1", "orders", 7L), listOf(rule.id, rule.name, rule.addedAtMs))
        assertEquals(listOf("f1"), MessageFormats(dir).list("app").map { it.id })
        assertEquals(emptyList(), MessageFormats(dir).list("other"))
        assertEquals("f2", f.add("app", orders.copy(name = "b")).id)
        assertTrue(f.remove("app", "f1"))
        assertFalse(f.remove("app", "f1"))
        assertEquals(listOf("f2"), MessageFormats(dir).list("app").map { it.id })
    }

    @Test
    fun `a bad rule is refused with the reason`() {
        val e = assertFailsWith<SchemaException> { MessageFormats(dir).add("app", orders.copy(pattern = "(?<id>")) }
        assertTrue(e.message!!.startsWith("pattern does not compile"), e.message)
    }

    @Test
    fun `messages get the envelope the app's rule reads, others 038's`() {
        val f = MessageFormats(dir)
        f.add("app", orders)
        val read = f.annotate("app", message("42|ORDER_UPDATE|{\"orderId\":7}")) as WsMessageEntry
        assertEquals(listOf("orders", "ORDER_UPDATE", "42", "f1"), listOf(read.envelope?.format, read.envelope?.kind, read.envelope?.id, read.envelope?.rule))
        val other = f.annotate("app", message("{\"type\":\"tick\"}", url = "wss://elsewhere.test/")) as WsMessageEntry
        assertEquals("json", other.envelope?.format)
        assertNull((f.annotate("app", message("", binary = true)) as WsMessageEntry).envelope)
    }

    @Test
    fun `a connection's messages are read by its URL`() {
        val f = MessageFormats(dir)
        f.add("app", orders)
        val conn = WsConnectionEntry("r1:0", "r1", 1_000, "okhttp3-ws", url = "wss://api.example.test/live", state = WsState.OPEN)
        val detail = f.annotate("app", NetworkEntryDetail(conn, messages = listOf(message("1|A|{}", url = null))))
        assertEquals("orders", detail.messages!!.single().envelope?.format)
    }

    @Test
    fun `test says how a rule reads a connection's messages without keeping it`() {
        val f = MessageFormats(dir)
        val messages = listOf(message("1|A|{}", 1), message("hello", 2), message("2|B|{}", 3, direction = Direction.OUT), message("", 4, binary = true))
        val r = f.test(orders, messages)
        assertEquals(listOf(2, 3), listOf(r.read, r.of))
        assertEquals(listOf("A", "B"), r.samples.map { it.envelope.kind })
        assertEquals(listOf("hello"), r.unread)
        assertEquals(listOf(1, 1), f.test(orders.copy(direction = Direction.OUT), messages).let { listOf(it.read, it.of) })
        assertEquals(emptyList(), f.list("app"))
    }

    @Test
    fun `a message longer than its preview is read from its whole text, and the connection says how its rules read`() {
        val f = MessageFormats(dir)
        f.add("app", FormatRuleSpec(name = "ticker", urlPattern = "wss://api.example.test/live*", kind = "type"))
        val whole = """{"type":"tick","data":"${"x".repeat(400)}"}"""
        val long = message(whole, n = 2).let { it.copy(payload = it.payload.copy(storedBytes = whole.length.toLong(), preview = whole.take(BodyInfo.PREVIEW_CHARS))) }
        val unknown = long.copy(id = "r1:3")
        val texts = mapOf(long.id to whole); val full: (WsMessageEntry) -> String? = { texts[it.id] }
        assertEquals("tick", (f.annotate("app", long, full) as WsMessageEntry).envelope?.kind)
        // Without the whole text the preview is all there is: not read, and said why.
        assertEquals("json", (f.annotate("app", unknown, full) as WsMessageEntry).envelope?.format)
        val socket = WsConnectionEntry("r1:0", "r1", 1_000, "okhttp3-ws", url = "wss://api.example.test/live", state = WsState.OPEN)
        val detail = f.annotate("app", NetworkEntryDetail(socket, messages = listOf(long, unknown, message("""{"type":"pong"}""", n = 4))), full)
        val use = detail.formatUse!!.single()
        assertEquals(listOf("ticker", 2, 3), listOf(use.name, use.read, use.of))
        assertEquals("only the first ${BodyInfo.PREVIEW_CHARS} characters are known (the message is not copied from the app yet)", use.unread.single().reason)
    }

}
