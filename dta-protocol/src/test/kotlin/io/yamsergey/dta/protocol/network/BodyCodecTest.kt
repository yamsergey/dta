package io.yamsergey.dta.protocol.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyCodecTest {

    private val json = """{"user":"ana","items":[1,2,3]}""".toByteArray()

    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    private fun deflate(b: ByteArray) = ByteArrayOutputStream().also { o -> DeflaterOutputStream(o).use { it.write(b) } }.toByteArray()

    @Test
    fun `gzip and deflate decode, identity passes through`() {
        assertContentEquals(json, BodyCodec.decode(gzip(json), "gzip").bytes)
        assertContentEquals(json, BodyCodec.decode(gzip(json), "x-gzip").bytes)
        assertContentEquals(json, BodyCodec.decode(deflate(json), "deflate").bytes)
        val plain = BodyCodec.decode(json, null)
        assertContentEquals(json, plain.bytes)
        assertTrue(plain.decoded)
        assertContentEquals(json, BodyCodec.decode(json, "identity").bytes)
    }

    @Test
    fun `brotli and zstd are stated as not decoded`() {
        for (encoding in listOf("br", "zstd")) {
            val result = BodyCodec.decode(byteArrayOf(1, 2, 3), encoding)
            assertFalse(result.decoded)
            assertContentEquals(byteArrayOf(1, 2, 3), result.bytes)
            assertTrue(encoding in result.note!!, result.note)
        }
    }

    @Test
    fun `corrupt gzip is stated, raw bytes kept`() {
        val result = BodyCodec.decode(byteArrayOf(1, 2, 3), "gzip")
        assertFalse(result.decoded)
        assertTrue("gzip" in result.note!!)
    }

    @Test
    fun `decoding stream undoes the encoding for file output`() {
        assertContentEquals(json, BodyCodec.decodingStream(ByteArrayInputStream(gzip(json)), "gzip")!!.readBytes())
        assertNull(BodyCodec.decodingStream(ByteArrayInputStream(json), "br"))
    }

    @Test
    fun `binary detection uses the content type, then the bytes`() {
        assertFalse(BodyCodec.isBinary("application/json; charset=utf-8", json))
        assertFalse(BodyCodec.isBinary("text/event-stream", json))
        assertFalse(BodyCodec.isBinary("application/vnd.api+json", json))
        assertTrue(BodyCodec.isBinary("application/x-protobuf", json))
        assertTrue(BodyCodec.isBinary("image/png", json))
        assertTrue(BodyCodec.isBinary("application/octet-stream", json))
        assertFalse(BodyCodec.isBinary(null, json))
        assertTrue(BodyCodec.isBinary(null, byteArrayOf(0x0a, 0x00, 0x7f, -1, -2)))
        assertTrue(BodyCodec.isBinary(null, byteArrayOf(0x41, -1, 0x41)), "invalid UTF-8 in the middle")
    }

    @Test
    fun `preview is at most 256 chars of decoded text, null for binary`() {
        val long = "x".repeat(1000).toByteArray()
        assertEquals(BodyInfo.PREVIEW_CHARS, BodyCodec.preview(long, "text/plain")!!.length)
        assertEquals(String(json), BodyCodec.preview(json, "application/json"))
        assertNull(BodyCodec.preview(json, "application/x-protobuf"))
    }

    @Test
    fun `a utf-8 sequence cut by the head is still text`() {
        val text = "привет мир".toByteArray()
        assertFalse(BodyCodec.isBinary(null, text.copyOf(text.size - 1)))
    }
}
