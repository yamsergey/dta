package io.yamsergey.dta.protocol.network

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * The one place bodies are interpreted (constitution VI): Content-Encoding decoding,
 * text-vs-binary sniffing and previews. The sidekick stores wire bytes; this is used for
 * previews on the device and for decoding in the daemon. Encodings it cannot undo
 * (br, zstd) are stated, never guessed at.
 */
object BodyCodec {

    data class Decoded(val bytes: ByteArray, val decoded: Boolean, val note: String? = null)

    private val TEXT_MARKERS = listOf("json", "xml", "javascript", "x-www-form-urlencoded", "graphql", "event-stream", "yaml", "csv", "html")
    private val BINARY_TYPES = listOf("image/", "audio/", "video/", "font/")
    private val BINARY_MARKERS = listOf("octet-stream", "protobuf", "grpc", "zip", "gzip", "pdf", "msgpack", "cbor", "avro", "wasm")

    fun decode(bytes: ByteArray, encoding: String?): Decoded {
        return try {
            val stream = decodingStream(ByteArrayInputStream(bytes), encoding)
                ?: return Decoded(bytes, decoded = false, note = "$encoding encoding not decoded; bytes are as sent")
            Decoded(stream.use { it.readBytes() }, decoded = true)
        } catch (e: Exception) {
            Decoded(bytes, decoded = false, note = "$encoding decoding failed (${e.message ?: e.javaClass.simpleName}); bytes are as sent")
        }
    }

    /** A stream undoing [encoding], [input] itself for none/identity, null when unsupported. */
    fun decodingStream(input: InputStream, encoding: String?): InputStream? =
        when (encoding?.trim()?.lowercase()) {
            null, "", "identity" -> input
            "gzip", "x-gzip" -> GZIPInputStream(input)
            "deflate" -> deflateStream(input)
            else -> null
        }

    /** HTTP "deflate" is zlib-wrapped in practice, raw DEFLATE sometimes; tell by the header. */
    private fun deflateStream(input: InputStream): InputStream {
        val buffered = input.buffered()
        buffered.mark(2)
        val cmf = buffered.read()
        val flg = buffered.read()
        buffered.reset()
        val zlib = cmf >= 0 && flg >= 0 && (cmf and 0x0F) == 8 && ((cmf shl 8) or flg) % 31 == 0
        return InflaterInputStream(buffered, Inflater(!zlib))
    }

    fun isBinary(contentType: String?, head: ByteArray): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type != null) {
            if (type.startsWith("text/") || TEXT_MARKERS.any { it in type }) return false
            if (BINARY_TYPES.any { type.startsWith(it) } || BINARY_MARKERS.any { it in type }) return true
        }
        return !looksLikeText(head)
    }

    /** At most [BodyInfo.PREVIEW_CHARS] characters of [decodedHead]; null for binary. */
    fun preview(decodedHead: ByteArray, contentType: String?): String? {
        if (isBinary(contentType, decodedHead)) return null
        val text = String(decodedHead, Charsets.UTF_8).trimEnd('�')
        return text.take(BodyInfo.PREVIEW_CHARS)
    }

    /** Valid UTF-8 without NUL/control bytes; a sequence cut at the end of the head is fine. */
    private fun looksLikeText(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            val length = when {
                b == 0 -> return false
                b < 0x20 -> if (b == 0x09 || b == 0x0A || b == 0x0D || b == 0x0C) 1 else return false
                b < 0x80 -> 1
                b in 0xC2..0xDF -> 2
                b in 0xE0..0xEF -> 3
                b in 0xF0..0xF4 -> 4
                else -> return false
            }
            for (k in 1 until length) {
                if (i + k >= bytes.size) return true
                if ((bytes[i + k].toInt() and 0xC0) != 0x80) return false
            }
            i += length
        }
        return true
    }
}
