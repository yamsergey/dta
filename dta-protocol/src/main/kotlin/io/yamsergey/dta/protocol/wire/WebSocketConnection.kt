package io.yamsergey.dta.protocol.wire

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * One server-side WebSocket session over a pair of streams. Transport-agnostic
 * on purpose (constitution IV): production feeds it LocalSocket streams; tests
 * feed it byte arrays or TCP sockets.
 */
class WebSocketConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val listener: Listener,
    private val maxPayloadBytes: Long = MAX_FRAME_PAYLOAD_BYTES,
) {
    interface Listener {
        fun onOpen(connection: WebSocketConnection) {}
        fun onText(connection: WebSocketConnection, text: String) {}
        fun onBinary(connection: WebSocketConnection, data: ByteArray) {}
        fun onClosed(connection: WebSocketConnection, code: Int, reason: String) {}
        fun onFailure(connection: WebSocketConnection, error: Throwable) {}
    }

    private val writer = FrameWriter(output)

    @Volatile
    private var closeSent = false

    /** Blocking: performs the handshake, then pumps frames until close/EOF.
     * Run on the thread that owns this connection. */
    fun run() {
        try {
            if (!WebSocketHandshake.perform(input, output)) return
        } catch (e: IOException) {
            listener.onFailure(this, e)
            return
        }
        listener.onOpen(this)
        try {
            readLoop()
        } catch (e: WireProtocolException) {
            sendClose(e.closeCode, e.message ?: "protocol error")
            listener.onFailure(this, e)
        } catch (e: EOFException) {
            listener.onClosed(this, 1006, "stream ended mid-frame")
        } catch (e: IOException) {
            if (!closeSent) listener.onFailure(this, e)
            listener.onClosed(this, 1006, e.message ?: "connection lost")
        }
    }

    private fun readLoop() {
        val reader = FrameReader(input, maxPayloadBytes)
        var messageOpcode = -1
        var messageBuffer = ByteArrayOutputStream()

        while (true) {
            val frame = reader.read()
            if (frame == null) {
                // EOF at a frame boundary without a close frame: abnormal closure.
                listener.onClosed(this, 1006, "peer went away without close")
                return
            }

            if (frame.isControl) {
                when (frame.opcode) {
                    Opcode.PING -> writer.write(Opcode.PONG, frame.payload)
                    Opcode.PONG -> Unit
                    Opcode.CLOSE -> {
                        val (code, reason) = parseClose(frame.payload)
                        if (!closeSent) sendClose(code, "")
                        listener.onClosed(this, code, reason)
                        return
                    }
                    else -> throw WireProtocolException("Unknown control opcode ${frame.opcode}", 1002)
                }
                continue
            }

            when (frame.opcode) {
                Opcode.TEXT, Opcode.BINARY -> {
                    if (messageOpcode != -1) {
                        throw WireProtocolException("New data frame while a fragmented message is open", 1002)
                    }
                    if (frame.fin) {
                        deliver(frame.opcode, frame.payload)
                    } else {
                        messageOpcode = frame.opcode
                        messageBuffer.write(frame.payload)
                    }
                }
                Opcode.CONTINUATION -> {
                    if (messageOpcode == -1) {
                        throw WireProtocolException("Continuation frame without a started message", 1002)
                    }
                    if (messageBuffer.size() + frame.payload.size > maxPayloadBytes) {
                        throw WireProtocolException("Reassembled message exceeds $maxPayloadBytes-byte bound", 1009)
                    }
                    messageBuffer.write(frame.payload)
                    if (frame.fin) {
                        deliver(messageOpcode, messageBuffer.toByteArray())
                        messageOpcode = -1
                        messageBuffer = ByteArrayOutputStream()
                    }
                }
                else -> throw WireProtocolException("Unknown data opcode ${frame.opcode}", 1002)
            }
        }
    }

    private fun deliver(opcode: Int, payload: ByteArray) {
        if (opcode == Opcode.TEXT) {
            listener.onText(this, String(payload, Charsets.UTF_8))
        } else {
            listener.onBinary(this, payload)
        }
    }

    fun sendText(text: String) = writer.write(Opcode.TEXT, text.toByteArray(Charsets.UTF_8))

    fun sendBinary(data: ByteArray) = writer.write(Opcode.BINARY, data)

    fun close(code: Int = 1000, reason: String = "") = sendClose(code, reason)

    private fun sendClose(code: Int, reason: String) {
        if (closeSent) return
        closeSent = true
        val reasonBytes = reason.toByteArray(Charsets.UTF_8).let {
            if (it.size > 123) it.copyOf(123) else it
        }
        try {
            writer.write(Opcode.CLOSE, byteArrayOf((code shr 8).toByte(), code.toByte()) + reasonBytes)
        } catch (_: IOException) {
            // Peer already gone; nothing to tell it.
        }
    }

    private fun parseClose(payload: ByteArray): Pair<Int, String> {
        if (payload.size < 2) return 1005 to ""
        val code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        return code to String(payload, 2, payload.size - 2, Charsets.UTF_8)
    }
}
