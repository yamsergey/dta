package io.yamsergey.dta.protocol.wire

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds client→server frames (masked) byte by byte, per RFC 6455 §5.2. */
internal fun clientFrame(
    opcode: Int,
    payload: ByteArray,
    fin: Boolean = true,
    mask: Boolean = true,
    maskKey: ByteArray = byteArrayOf(0x12, 0x34, 0x56, 0x78),
    rsv: Int = 0,
    declaredLength: Long? = null,
): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(((if (fin) 0x80 else 0x00) or (rsv shl 4) or opcode))
    val len = declaredLength ?: payload.size.toLong()
    val maskBit = if (mask) 0x80 else 0x00
    when {
        len <= 125 -> out.write(maskBit or len.toInt())
        len <= 0xFFFF -> {
            out.write(maskBit or 126)
            out.write((len shr 8).toInt() and 0xFF)
            out.write(len.toInt() and 0xFF)
        }
        else -> {
            out.write(maskBit or 127)
            for (shift in 56 downTo 0 step 8) out.write((len shr shift).toInt() and 0xFF)
        }
    }
    if (mask) {
        out.write(maskKey)
        payload.forEachIndexed { i, b -> out.write((b.toInt() xor maskKey[i % 4].toInt()) and 0xFF) }
    } else {
        out.write(payload)
    }
    return out.toByteArray()
}

class FrameCodecTest {

    private fun reader(bytes: ByteArray, max: Long = 64L * 1024 * 1024) =
        FrameReader(ByteArrayInputStream(bytes), maxPayloadBytes = max)

    @Test
    fun `reads small masked text frame`() {
        val frame = reader(clientFrame(Opcode.TEXT, "hi".toByteArray())).read()!!
        assertEquals(Opcode.TEXT, frame.opcode)
        assertTrue(frame.fin)
        assertContentEquals("hi".toByteArray(), frame.payload)
    }

    @Test
    fun `reads 16-bit extended length`() {
        val payload = ByteArray(200) { it.toByte() }
        val frame = reader(clientFrame(Opcode.BINARY, payload)).read()!!
        assertContentEquals(payload, frame.payload)
    }

    @Test
    fun `reads 64-bit extended length`() {
        val payload = ByteArray(70_000) { (it % 251).toByte() }
        val frame = reader(clientFrame(Opcode.BINARY, payload)).read()!!
        assertContentEquals(payload, frame.payload)
    }

    @Test
    fun `returns null on clean eof at frame boundary`() {
        assertNull(reader(ByteArray(0)).read())
    }

    @Test
    fun `unmasked client frame is a protocol violation with close code 1002`() {
        val e = assertFailsWith<WireProtocolException> {
            reader(clientFrame(Opcode.TEXT, "hi".toByteArray(), mask = false)).read()
        }
        assertEquals(1002, e.closeCode)
    }

    @Test
    fun `nonzero rsv bits are a protocol violation 1002`() {
        val e = assertFailsWith<WireProtocolException> {
            reader(clientFrame(Opcode.TEXT, "hi".toByteArray(), rsv = 0x4)).read()
        }
        assertEquals(1002, e.closeCode)
    }

    @Test
    fun `fragmented control frame is a protocol violation 1002`() {
        val e = assertFailsWith<WireProtocolException> {
            reader(clientFrame(Opcode.PING, "x".toByteArray(), fin = false)).read()
        }
        assertEquals(1002, e.closeCode)
    }

    @Test
    fun `control frame longer than 125 bytes is a protocol violation 1002`() {
        val e = assertFailsWith<WireProtocolException> {
            reader(clientFrame(Opcode.PING, ByteArray(126))).read()
        }
        assertEquals(1002, e.closeCode)
    }

    @Test
    fun `declared length above the sanity bound closes with 1009`() {
        val e = assertFailsWith<WireProtocolException> {
            reader(
                clientFrame(Opcode.BINARY, ByteArray(0), declaredLength = 2048),
                max = 1024,
            ).read()
        }
        assertEquals(1009, e.closeCode)
    }

    @Test
    fun `writer emits unmasked server frame readable by the codec rules`() {
        val out = ByteArrayOutputStream()
        FrameWriter(out).write(Opcode.TEXT, "pong".toByteArray())
        val bytes = out.toByteArray()
        assertEquals(0x81, bytes[0].toInt() and 0xFF) // FIN + TEXT
        assertEquals(4, bytes[1].toInt() and 0xFF)    // no mask bit, length 4
        assertContentEquals("pong".toByteArray(), bytes.copyOfRange(2, 6))
    }

    @Test
    fun `writer uses 16-bit length encoding above 125 bytes`() {
        val out = ByteArrayOutputStream()
        val payload = ByteArray(300) { 7 }
        FrameWriter(out).write(Opcode.BINARY, payload)
        val bytes = out.toByteArray()
        assertEquals(126, bytes[1].toInt() and 0xFF)
        assertEquals(300, ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF))
        assertEquals(300 + 4, bytes.size)
    }

    @Test
    fun `writer uses 64-bit length encoding above 65535 bytes`() {
        val out = ByteArrayOutputStream()
        val payload = ByteArray(70_000)
        FrameWriter(out).write(Opcode.BINARY, payload)
        val bytes = out.toByteArray()
        assertEquals(127, bytes[1].toInt() and 0xFF)
        var len = 0L
        for (i in 2..9) len = (len shl 8) or (bytes[i].toLong() and 0xFF)
        assertEquals(70_000L, len)
    }

    @Test
    fun `sequential frames on one stream are read in order`() {
        val stream = clientFrame(Opcode.TEXT, "one".toByteArray()) +
            clientFrame(Opcode.PING, "p".toByteArray()) +
            clientFrame(Opcode.TEXT, "two".toByteArray())
        val r = reader(stream)
        assertContentEquals("one".toByteArray(), r.read()!!.payload)
        assertEquals(Opcode.PING, r.read()!!.opcode)
        assertContentEquals("two".toByteArray(), r.read()!!.payload)
        assertNull(r.read())
    }
}
