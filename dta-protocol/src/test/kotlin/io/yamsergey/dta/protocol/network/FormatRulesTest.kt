package io.yamsergey.dta.protocol.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormatRulesTest {
    private fun rule(
        id: String = "f1",
        url: String = "wss://api.example.test/live*",
        pattern: String? = null,
        kind: String? = null,
        idPath: String? = null,
        topic: String? = null,
        payload: String? = null,
        direction: Direction? = null,
        payloadFormat: PayloadFormat = PayloadFormat.JSON,
        addedAtMs: Long = 1,
    ) = FormatRule(id, "orders", url, direction, pattern, kind, idPath, topic, payload, payloadFormat, addedAtMs)

    @Test
    fun `a pattern reads kind, id and payload by named groups`() {
        val r = rule(pattern = """^(?<id>\d+)\|(?<kind>\w+)\|(?<payload>.*)$""")
        val e = FormatRules.apply(r, """42|ORDER_UPDATE|{"orderId":7,"state":"filled"}""")!!
        assertEquals(listOf("orders", "ORDER_UPDATE", "42", null, "f1"), listOf(e.format, e.kind, e.id, e.topic, e.rule))
        assertEquals("""{"orderId":7,"state":"filled"}""", e.payload)
        assertNull(e.note)
    }

    @Test
    fun `paths read a JSON protocol's own keys`() {
        val r = rule(kind = "h.op", idPath = "h.rid", topic = "h.ch[0]", payload = "b")
        val e = FormatRules.apply(r, """{"h":{"op":"sub","rid":3,"ch":["quotes","x"]},"b":{"symbols":["A"]}}""")!!
        assertEquals(listOf("sub", "3", "quotes"), listOf(e.kind, e.id, e.topic))
        assertEquals("""{"symbols":["A"]}""", e.payload)
    }

    @Test
    fun `paths read from the payload a pattern found`() {
        val r = rule(pattern = """^MSG (?<payload>\{.*)$""", kind = "t", idPath = "seq")
        val e = FormatRules.apply(r, """MSG {"t":"quote","seq":9,"p":1.5}""")!!
        assertEquals(listOf("quote", "9"), listOf(e.kind, e.id))
    }

    @Test
    fun `a text that does not match gives nothing`() {
        assertNull(FormatRules.apply(rule(pattern = """^(?<id>\d+)\|"""), "hello"))
        // Paths on a text that is not JSON read nothing.
        assertNull(FormatRules.apply(rule(kind = "op"), "hello"))
    }

    @Test
    fun `a payload that is not JSON is kept and said`() {
        val e = FormatRules.apply(rule(pattern = """^(?<kind>\w+):(?<payload>.*)$"""), "PING:abc")!!
        assertEquals("abc", e.payload)
        assertEquals("payload is not JSON", e.note)
        val text = FormatRules.apply(rule(pattern = """^(?<kind>\w+):(?<payload>.*)$""", payloadFormat = PayloadFormat.TEXT), "PING:abc")!!
        assertNull(text.note)
    }

    @Test
    fun `a rule is refused with what is wrong`() {
        val bad = assertFailsWith<FormatRuleException> { FormatRules.validate(spec(pattern = "^(?<id>\\d+")) }
        assertTrue(bad.message!!.startsWith("pattern does not compile"), bad.message)
        val group = assertFailsWith<FormatRuleException> { FormatRules.validate(spec(pattern = "^(?<name>\\w+)")) }
        assertTrue("name" in group.message!! && "kind, id, topic, payload" in group.message!!, group.message)
        val nothing = assertFailsWith<FormatRuleException> { FormatRules.validate(spec()) }
        assertTrue("pattern" in nothing.message!! && "paths" in nothing.message!!, nothing.message)
        assertFailsWith<FormatRuleException> { FormatRules.validate(spec(pattern = "(?<kind>a)", url = " ")) }
        assertFailsWith<FormatRuleException> { FormatRules.validate(spec(kind = "a..b")) }
    }

    @Test
    fun `the most literal URL pattern wins, then the newest, and direction limits`() {
        val wide = rule(id = "f1", url = "wss://*", pattern = "(?<kind>.+)", addedAtMs = 5)
        val narrow = rule(id = "f2", url = "wss://api.example.test/live*", pattern = "(?<kind>.+)", addedAtMs = 1)
        val newer = rule(id = "f3", url = "wss://api.example.test/live*", pattern = "(?<kind>.+)", addedAtMs = 2)
        val outOnly = rule(id = "f4", url = "wss://api.example.test/live/v2", pattern = "(?<kind>.+)", direction = Direction.OUT)
        val rules = listOf(wide, narrow, newer, outOnly)
        assertEquals("f3", FormatRules.select(rules, "wss://api.example.test/live/v1", Direction.IN)?.id)
        assertEquals("f4", FormatRules.select(rules, "wss://api.example.test/live/v2", Direction.OUT)?.id)
        assertEquals("f3", FormatRules.select(rules, "wss://api.example.test/live/v2", Direction.IN)?.id)
        assertEquals("f1", FormatRules.select(rules, "wss://other.test/", Direction.IN)?.id)
        assertNull(FormatRules.select(rules, "https://x", Direction.IN))
    }

    @Test
    fun `detect tries the app's rule first, then the known formats`() {
        val r = rule(pattern = """^(?<id>\d+)\|(?<kind>\w+)\|(?<payload>.*)$""")
        assertEquals("orders", WsFormats.detect("""1|A|{}""", r)!!.format)
        // Not this rule's message: 038's detection.
        assertEquals("json", WsFormats.detect("""{"type":"tick"}""", r)!!.format)
    }

    @Test
    fun `a pattern that backtracks without end stops and says so`() {
        // A backreference keeps the JDK from cutting the backtracking short.
        val r = rule(pattern = """^(?<kind>(a+)\2?)+$""")
        val started = System.nanoTime()
        val e = FormatRules.apply(r, "a".repeat(40) + "!")
        assertTrue(System.nanoTime() - started < 2_000_000_000L)
        assertNull(e)
        assertTrue(FormatRules.slow(r))
    }

    private fun spec(pattern: String? = null, kind: String? = null, url: String = "wss://x/*") =
        FormatRuleSpec(name = "orders", urlPattern = url, pattern = pattern, kind = kind)
}
