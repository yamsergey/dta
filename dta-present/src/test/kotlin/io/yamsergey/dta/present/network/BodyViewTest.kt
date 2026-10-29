package io.yamsergey.dta.present.network

import io.yamsergey.dta.present.StatementKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyViewTest {
    private fun canned(name: String): JsonObject = Json.parseToJsonElement(javaClass.getResource("/canned/$name.json")!!.readText()).jsonObject
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `the header names part, type, size, decoding and redaction`() {
        val view = BodyView.of(canned("body-decoded"))
        assertEquals("request · application/x-protobuf · 12 B", view.header.line)
        assertEquals(listOf(StatementKind.REDACTED, StatementKind.DECODED), view.header.statements.map { it.kind })
        val truncated = BodyView.of(canned("body-truncated-warnings"))
        assertTrue(truncated.header.statements.any { it.kind == StatementKind.INCOMPLETE && it.word == "truncated" })
    }

    @Test
    fun `formatted by default when decoded or JSON, raw text otherwise, hex for binary that did not decode`() {
        val decoded = BodyView.of(canned("body-decoded"))
        assertEquals(BodyMode.FORMATTED, decoded.defaultMode)
        assertEquals("{\n  \"id\": \"42\"\n}", decoded.render(BodyMode.FORMATTED))
        assertEquals("{\"id\":\"42\"}", decoded.render(BodyMode.RAW))

        val plain = BodyView.of(json("""{"body":{"part":"response","storedBytes":5,"contentType":"text/plain","completeness":"complete"},"text":"hello","bytes":5}"""))
        assertEquals(BodyMode.RAW, plain.defaultMode)
        assertEquals(listOf(BodyMode.RAW, BodyMode.HEX), plain.modes)

        val binary = BodyView.of(json("""{"body":{"part":"response","storedBytes":300,"binary":true,"completeness":"complete"},"decoding":{"outcome":"not-decoded","reason":"no mapping for this URL"},"file":"/tmp/b.bin","sha256":"ab12","bytes":300}"""))
        assertEquals(BodyMode.HEX, binary.defaultMode)
        assertEquals(listOf(BodyMode.HEX), binary.modes)
        assertTrue(binary.header.statements.any { it.detail == "not decoded: no mapping for this URL" })
    }

    @Test
    fun `hex shows offsets, bytes and the printable column`() {
        val dump = Hex.dump("DTA\u0000hello, world!!!xyz".toByteArray())
        assertEquals(
            "00000000  44 54 41 00 68 65 6c 6c  6f 2c 20 77 6f 72 6c 64  |DTA.hello, world|\n" +
                "00000010  21 21 21 78 79 7a                                 |!!!xyz|",
            dump,
        )
    }

    @Test
    fun `bodies over the inline limit come as a file, opened in an editor tab`() {
        assertFalse(BodyView.needsFile(storedBytes = 1_048_576))
        assertTrue(BodyView.needsFile(storedBytes = 1_048_577))
        val big = BodyView.of(json("""{"body":{"part":"response","storedBytes":5000000,"contentType":"application/json","completeness":"complete"},"file":"/tmp/big.json","sha256":"ff00","bytes":5000000}"""))
        val file = big.content as BodyContent.File
        assertEquals("/tmp/big.json", file.path)
        assertEquals("ff00", file.sha256)
        assertTrue(big.tooLarge)
        assertNull(big.text)
    }

    @Test
    fun `invalid JSON stays raw rather than being guessed at`() {
        assertNull(PrettyJson.format("{not json"))
        assertEquals("[\n  1,\n  2\n]", PrettyJson.format("[1,2]"))
    }

    private val text = JsonObject(mapOf("text" to kotlinx.serialization.json.JsonPrimitive("{}")))
    private val file = JsonObject(mapOf("path" to kotlinx.serialization.json.JsonPrimitive("/tmp/b")))

    @Test
    fun `a binary body the daemon refuses as text is fetched to a file for the hex view`() {
        val calls = mutableListOf<String>()
        val got = BodyView.fetch(16, binary = true, inline = { calls += "inline"; throw IllegalStateException("binary body") }, toFile = { calls += "file"; file })
        assertEquals(file, got)
        assertEquals(listOf("inline", "file"), calls, "inline first: a protobuf schema may decode it to JSON")
    }

    @Test
    fun `a decoded binary body stays text, a large body goes straight to a file, other failures are stated`() {
        assertEquals(text, BodyView.fetch(16, binary = true, inline = { text }, toFile = { error("not needed") }))
        assertEquals(file, BodyView.fetch(BodyView.INLINE_LIMIT + 1, binary = false, inline = { error("not needed") }, toFile = { file }))
        val e = kotlin.test.assertFailsWith<IllegalStateException> { BodyView.fetch(16, binary = false, inline = { throw IllegalStateException("gone") }, toFile = { file }) }
        assertEquals("gone", e.message)
    }
}
