package io.yamsergey.dta.protocol.wire

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val UPGRADE = "GET / HTTP/1.1\r\n" +
    "Host: localhost\r\n" +
    "Upgrade: websocket\r\n" +
    "Connection: Upgrade\r\n" +
    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
    "Sec-WebSocket-Version: 13\r\n\r\n"

private fun closePayload(code: Int, reason: String = ""): ByteArray {
    val reasonBytes = reason.toByteArray()
    return byteArrayOf((code shr 8).toByte(), code.toByte()) + reasonBytes
}

/** Parses unmasked server frames out of the connection's output stream,
 * skipping the HTTP response head. */
private fun serverFrames(raw: ByteArray): List<Frame> {
    var idx = raw.indexOfHead()
    val frames = mutableListOf<Frame>()
    while (idx < raw.size) {
        val b0 = raw[idx].toInt() and 0xFF
        val b1 = raw[idx + 1].toInt() and 0xFF
        check(b1 and 0x80 == 0) { "server frame must be unmasked" }
        var len = (b1 and 0x7F).toLong()
        var headerLen = 2
        if (len == 126L) {
            len = (((raw[idx + 2].toInt() and 0xFF) shl 8) or (raw[idx + 3].toInt() and 0xFF)).toLong()
            headerLen = 4
        } else if (len == 127L) {
            len = 0
            for (i in 0 until 8) len = (len shl 8) or (raw[idx + 2 + i].toLong() and 0xFF)
            headerLen = 10
        }
        val start = idx + headerLen
        frames += Frame(b0 and 0x80 != 0, b0 and 0x0F, raw.copyOfRange(start, start + len.toInt()))
        idx = start + len.toInt()
    }
    return frames
}

private fun ByteArray.indexOfHead(): Int {
    val terminator = "\r\n\r\n".toByteArray()
    outer@ for (i in indices) {
        for (j in terminator.indices) {
            if (i + j >= size || this[i + j] != terminator[j]) continue@outer
        }
        return i + terminator.size
    }
    error("no HTTP head terminator in output")
}

private class RecordingListener : WebSocketConnection.Listener {
    val texts = mutableListOf<String>()
    val binaries = mutableListOf<ByteArray>()
    val failures = mutableListOf<Throwable>()
    var opened = false
    var closedCode: Int? = null
    var closedReason: String? = null

    override fun onOpen(connection: WebSocketConnection) { opened = true }
    override fun onText(connection: WebSocketConnection, text: String) { texts += text }
    override fun onBinary(connection: WebSocketConnection, data: ByteArray) { binaries += data }
    override fun onClosed(connection: WebSocketConnection, code: Int, reason: String) {
        closedCode = code
        closedReason = reason
    }
    override fun onFailure(connection: WebSocketConnection, error: Throwable) { failures += error }
}

class WebSocketConnectionTest {

    private fun run(vararg frames: ByteArray, upgrade: String = UPGRADE): Pair<RecordingListener, ByteArray> {
        val inputBytes = upgrade.toByteArray(Charsets.ISO_8859_1) + frames.fold(ByteArray(0)) { acc, f -> acc + f }
        val output = ByteArrayOutputStream()
        val listener = RecordingListener()
        WebSocketConnection(ByteArrayInputStream(inputBytes), output, listener).run()
        return listener to output.toByteArray()
    }

    @Test
    fun `text message delivered and clean close echoed`() {
        val (listener, out) = run(
            clientFrame(Opcode.TEXT, "hello".toByteArray()),
            clientFrame(Opcode.CLOSE, closePayload(1000, "bye")),
        )
        assertTrue(listener.opened)
        assertEquals(listOf("hello"), listener.texts)
        assertEquals(1000, listener.closedCode)
        assertEquals("bye", listener.closedReason)
        val closeEcho = serverFrames(out).single { it.opcode == Opcode.CLOSE }
        assertEquals(1000, ((closeEcho.payload[0].toInt() and 0xFF) shl 8) or (closeEcho.payload[1].toInt() and 0xFF))
    }

    @Test
    fun `binary message delivered`() {
        val payload = ByteArray(300) { it.toByte() }
        val (listener, _) = run(
            clientFrame(Opcode.BINARY, payload),
            clientFrame(Opcode.CLOSE, closePayload(1000)),
        )
        assertContentEquals(payload, listener.binaries.single())
    }

    @Test
    fun `ping answered with pong carrying same payload, not surfaced`() {
        val (listener, out) = run(
            clientFrame(Opcode.PING, "marco".toByteArray()),
            clientFrame(Opcode.CLOSE, closePayload(1000)),
        )
        val pong = serverFrames(out).single { it.opcode == Opcode.PONG }
        assertContentEquals("marco".toByteArray(), pong.payload)
        assertTrue(listener.texts.isEmpty())
    }

    @Test
    fun `fragmented text reassembled with control frame interleaved`() {
        val (listener, out) = run(
            clientFrame(Opcode.TEXT, "hel".toByteArray(), fin = false),
            clientFrame(Opcode.PING, "p".toByteArray()),
            clientFrame(Opcode.CONTINUATION, "lo".toByteArray(), fin = true),
            clientFrame(Opcode.CLOSE, closePayload(1000)),
        )
        assertEquals(listOf("hello"), listener.texts)
        assertEquals(1, serverFrames(out).count { it.opcode == Opcode.PONG })
    }

    @Test
    fun `protocol violation closes with 1002 and reports failure`() {
        val (listener, out) = run(
            clientFrame(Opcode.TEXT, "x".toByteArray(), mask = false),
        )
        val close = serverFrames(out).single { it.opcode == Opcode.CLOSE }
        assertEquals(1002, ((close.payload[0].toInt() and 0xFF) shl 8) or (close.payload[1].toInt() and 0xFF))
        assertIs<WireProtocolException>(listener.failures.single())
    }

    @Test
    fun `continuation without a started message is a protocol violation`() {
        val (listener, _) = run(
            clientFrame(Opcode.CONTINUATION, "x".toByteArray(), fin = true),
        )
        assertIs<WireProtocolException>(listener.failures.single())
    }

    @Test
    fun `eof without close reports abnormal closure 1006`() {
        val (listener, _) = run(clientFrame(Opcode.TEXT, "hi".toByteArray()))
        assertEquals(listOf("hi"), listener.texts)
        assertEquals(1006, listener.closedCode)
    }

    @Test
    fun `failed handshake never opens and answers 400`() {
        val output = ByteArrayOutputStream()
        val listener = RecordingListener()
        WebSocketConnection(
            ByteArrayInputStream("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray(Charsets.ISO_8859_1)),
            output,
            listener,
        ).run()
        assertTrue(!listener.opened)
        assertTrue(output.toString(Charsets.ISO_8859_1).startsWith("HTTP/1.1 400 "))
    }

    @Test
    fun `sendText and sendBinary write well-formed unmasked frames`() {
        val output = ByteArrayOutputStream()
        val listener = object : WebSocketConnection.Listener {
            override fun onOpen(connection: WebSocketConnection) {
                connection.sendText("""{"a":1}""")
                connection.sendBinary(byteArrayOf(1, 2, 3))
            }
        }
        WebSocketConnection(
            ByteArrayInputStream(
                UPGRADE.toByteArray(Charsets.ISO_8859_1) +
                    clientFrame(Opcode.CLOSE, closePayload(1000)),
            ),
            output,
            listener,
        ).run()
        val frames = serverFrames(output.toByteArray())
        assertContentEquals("""{"a":1}""".toByteArray(), frames.first { it.opcode == Opcode.TEXT }.payload)
        assertContentEquals(byteArrayOf(1, 2, 3), frames.first { it.opcode == Opcode.BINARY }.payload)
    }
}
