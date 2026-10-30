package io.yamsergey.dta.present

import io.yamsergey.dta.present.StatementKind.AUTO
import io.yamsergey.dta.present.StatementKind.BINARY
import io.yamsergey.dta.present.StatementKind.DECODED
import io.yamsergey.dta.present.StatementKind.DEGRADED
import io.yamsergey.dta.present.StatementKind.EVICTED
import io.yamsergey.dta.present.StatementKind.FAILED
import io.yamsergey.dta.present.StatementKind.FALLBACK
import io.yamsergey.dta.present.StatementKind.INCOMPLETE
import io.yamsergey.dta.present.StatementKind.NOT_CAPTURED
import io.yamsergey.dta.present.StatementKind.PENDING
import io.yamsergey.dta.present.StatementKind.PROCESS_ENDED
import io.yamsergey.dta.present.StatementKind.REDACTED
import io.yamsergey.dta.present.StatementKind.REDUCED_DETAIL
import io.yamsergey.dta.present.StatementKind.SCRIPTED
import io.yamsergey.dta.protocol.ops.AppRule
import io.yamsergey.dta.protocol.ops.DeviceRule
import io.yamsergey.dta.protocol.ops.SelectionStatement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatementsTest {

    private fun canned(name: String): JsonObject =
        Json.parseToJsonElement(javaClass.getResource("/canned/$name.json")!!.readText()).jsonObject

    private val list = canned("network-list")
    private fun entry(index: Int) = list["entries"]!!.jsonArray[index].jsonObject
    private fun List<Statement>.kinds() = map { it.kind }

    @Test
    fun `kinds follow the statements table with its glyphs, ASCII forms and tokens`() {
        assertEquals(
            listOf("fallback", "degraded", "reduced detail", "incomplete", "evicted", "auto", "inferred", "scripted", "redacted", "decoded", "binary", "failed", "pending", "process ended", "not captured"),
            StatementKind.entries.map { it.word },
        )
        assertEquals("▲" to "!", FALLBACK.glyph to FALLBACK.ascii)
        assertEquals("◐" to "~", INCOMPLETE.glyph to INCOMPLETE.ascii)
        assertEquals("◌" to "?", AUTO.glyph to AUTO.ascii)
        assertEquals("✎" to "*", SCRIPTED.glyph to SCRIPTED.ascii)
        assertEquals("◼" to "[]", PROCESS_ENDED.glyph to PROCESS_ENDED.ascii)
        assertEquals("caveat", REDUCED_DETAIL.token)
        assertEquals("inferred", AUTO.token)
        assertEquals("intervened", SCRIPTED.token)
        assertEquals("status-server", FAILED.token)
        assertEquals("ink-muted", NOT_CAPTURED.token)
    }

    @Test
    fun `entries state what happened to them, in table order`() {
        assertEquals(emptyList(), Statements.forEntry(entry(0)))
        val scripted = Statements.forEntry(entry(1))
        assertEquals(listOf(INCOMPLETE, SCRIPTED, BINARY), scripted.kinds())
        assertEquals("truncated", scripted[0].word)
        assertEquals("truncated: body bound 64 KB", scripted[0].detail)
        assertEquals("script abc123 changed status,delay, delayed 2000 ms", scripted[1].detail)
        assertEquals(listOf(PENDING), Statements.forEntry(entry(2)).kinds())
        assertEquals(listOf(PROCESS_ENDED), Statements.forEntry(entry(3)).kinds())
        val failed = Statements.forEntry(entry(4)).single()
        assertEquals(FAILED, failed.kind)
        assertEquals("UnknownHostException: down.example.com", failed.detail)
        assertEquals(emptyList(), Statements.forEntry(entry(5)))
    }

    @Test
    fun `a list states eviction, unsearched runs and degradation codes verbatim`() {
        val statements = Statements.forNetworkList(list)
        assertEquals(listOf(DEGRADED, DEGRADED, EVICTED), statements.kinds())
        assertEquals("network-hooks-unavailable", statements[0].code)
        assertEquals("network: network-hooks-unavailable", statements[0].detail)
        assertEquals("not searched: previous runs", statements[1].detail)
        assertEquals("34 older entries evicted", statements[2].detail)
    }

    @Test
    fun `bodies state decoding, binary, completeness and redaction`() {
        val decoded = Statements.forBody(canned("body-decoded"))
        assertEquals(listOf(REDACTED, DECODED), decoded.kinds())
        assertEquals("decoded as protobuf fixture.v1.Order (schema fixture)", decoded[1].detail)
        assertEquals("redacted: authorization", decoded[0].detail)

        val warned = Statements.forBody(canned("body-truncated-warnings"))
        assertEquals(listOf(DEGRADED, DEGRADED, DEGRADED, INCOMPLETE, BINARY), warned.kinds())
        assertTrue(warned.any { it.detail == "decoded as fixture.v1.Order with warnings: unknown field 9" }, "$warned")
        assertTrue(warned.any { it.detail == "redaction requested but overridden" }, "$warned")
        assertTrue(warned.any { it.code == "body-store-unavailable" }, "$warned")
    }

    @Test
    fun `a capture states fallback with its reason, reduced detail and degradation codes`() {
        val statements = Statements.forCapture(canned("layout-fallback"))
        assertEquals(listOf(FALLBACK, DEGRADED, REDUCED_DETAIL), statements.kinds())
        assertEquals("fallback: no DTA in com.example.plain", statements[0].detail)
        assertTrue("Build it with DTA" in statements[0].sentence, statements[0].sentence)
        assertEquals("compose-tooling-absent", statements[1].code)
        assertTrue("ui-tooling" in statements[1].sentence && "debug build" in statements[1].sentence, "cause and next step: ${statements[1].sentence}")
        assertEquals("detail full reduced to basic", statements[2].detail)
    }

    @Test
    fun `every degradation code says what is missing and what to do`() {
        val codes = io.yamsergey.dta.protocol.layout.DegradationCodes::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }
        assertTrue(codes.size > 10, "$codes")
        val missing = codes.filter { Degradations.explain(it) == null }
        assertEquals(emptyList(), missing, "codes without an explanation")
    }

    @Test
    fun `a capability whose reason carries a code is explained by it`() {
        val report = kotlinx.serialization.json.Json.parseToJsonElement("""
            {"capabilities":[{"name":"layout","status":"degraded","reason":"compose present, enrichment unavailable: compose-tooling-absent"}]}
        """).jsonObject
        val layout = Statements.forCapability(report).single()
        assertEquals("compose-tooling-absent", layout.code)
        assertTrue("ui-tooling" in layout.sentence, layout.sentence)
    }

    @Test
    fun `libraries not captured are information, other gaps are degradation`() {
        val statements = Statements.forCapability(canned("capability"))
        assertEquals(listOf(DEGRADED, NOT_CAPTURED), statements.kinds())
        assertEquals("java-websocket", statements[0].subject)
        assertEquals("degraded", statements[0].word)
        assertEquals("grpc", statements[1].subject)
        assertEquals("not captured", statements[1].word)
    }

    @Test
    fun `marks keep table order, at most three, then +N`() {
        val many = listOf(PENDING, SCRIPTED, INCOMPLETE, BINARY, FAILED).map { Statement(it, it.word) }
        val marks = Statements.marks(many)
        assertEquals(listOf(INCOMPLETE, SCRIPTED, BINARY), marks.shown.kinds())
        assertEquals(2, marks.more)
        assertEquals("+2", marks.moreLabel)
        assertEquals(0, Statements.marks(many.take(2)).more)
        assertEquals(null, Statements.marks(many.take(2)).moreLabel)
    }

    @Test
    fun `a history cleared by another client is stated with who and how much`() {
        val data = kotlinx.serialization.json.Json.parseToJsonElement("""{"subscription":"s1","device":"emulator-5554","app":"com.example.shop","cleared":{"clearedEntries":3,"clearedAtMs":1},"by":"mcp"}""") as kotlinx.serialization.json.JsonObject
        val st = Statements.forCleared(data)!!
        assertEquals(StatementKind.EVICTED, st.kind)
        assertEquals("cleared", st.word)
        assertEquals("History cleared by mcp: 3 entries removed.", st.detail)
        assertEquals(null, Statements.forCleared(kotlinx.serialization.json.JsonObject(emptyMap())))
    }
}
