package io.yamsergey.dta.protocol.wire

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object Opcode {
    const val CONTINUATION = 0x0
    const val TEXT = 0x1
    const val BINARY = 0x2
    const val CLOSE = 0x8
    const val PING = 0x9
    const val PONG = 0xA
}

/** Frame payload sanity bound (contract §1): protects against a corrupt length
 * header allocating gigabytes; not a payload policy cap. */
const val MAX_FRAME_PAYLOAD_BYTES: Long = 64L * 1024 * 1024

class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray) {
    val isControl: Boolean get() = opcode >= 0x8
}

/** Protocol violation carrying the WS close code the peer should receive. */
class WireProtocolException(message: String, val closeCode: Int) : IOException(message)

class FrameReader(
    input: InputStream,
    private val maxPayloadBytes: Long = MAX_FRAME_PAYLOAD_BYTES,
) {
    private val data = DataInputStream(input)

    /** Reads one frame; returns null on clean EOF at a frame boundary. */
    fun read(): Frame? {
        val b0 = data.read()
        if (b0 < 0) return null
        val b1 = try {
            data.readUnsignedByte()
        } catch (e: EOFException) {
            throw WireProtocolException("Stream ended mid-frame-header", 1002)
        }

        if (b0 and 0x70 != 0) throw WireProtocolException("Nonzero RSV bits (no extensions negotiated)", 1002)
        val fin = b0 and 0x80 != 0
        val opcode = b0 and 0x0F
        val masked = b1 and 0x80 != 0
        var length = (b1 and 0x7F).toLong()

        if (length == 126L) {
            length = data.readUnsignedShort().toLong()
        } else if (length == 127L) {
            length = data.readLong()
            if (length < 0) throw WireProtocolException("Negative frame length", 1002)
        }

        if (opcode >= 0x8) {
            if (!fin) throw WireProtocolException("Fragmented control frame", 1002)
            if (length > 125) throw WireProtocolException("Control frame payload over 125 bytes", 1002)
        }
        if (length > maxPayloadBytes) {
            throw WireProtocolException("Frame payload $length exceeds $maxPayloadBytes-byte bound", 1009)
        }
        if (!masked) {
            // RFC 6455 §5.1: client-to-server frames must be masked.
            throw WireProtocolException("Unmasked client frame", 1002)
        }

        val maskKey = ByteArray(4)
        data.readFully(maskKey)
        val payload = ByteArray(length.toInt())
        data.readFully(payload)
        for (i in payload.indices) {
            payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
        }
        return Frame(fin, opcode, payload)
    }
}

class FrameWriter(private val output: OutputStream) {

    /** Server frames are never masked (RFC 6455 §5.1). Synchronized so text
     * and binary writes from different threads never interleave mid-frame. */
    @Synchronized
    fun write(opcode: Int, payload: ByteArray, fin: Boolean = true) {
        val b0 = (if (fin) 0x80 else 0x00) or opcode
        output.write(b0)
        when {
            payload.size <= 125 -> output.write(payload.size)
            payload.size <= 0xFFFF -> {
                output.write(126)
                output.write(payload.size shr 8 and 0xFF)
                output.write(payload.size and 0xFF)
            }
            else -> {
                output.write(127)
                val len = payload.size.toLong()
                for (shift in 56 downTo 0 step 8) output.write((len shr shift).toInt() and 0xFF)
            }
        }
        output.write(payload)
        output.flush()
    }
}
