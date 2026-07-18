package io.yamsergey.dta.protocol.wire

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * Server half of the RFC 6455 opening handshake.
 *
 * Sidekick deliberately hand-rolls this instead of depending on a WebSocket
 * library: sidekick instruments WS libraries in host apps, so shipping one
 * would mean instrumenting our own transport (constitution I).
 */
object WebSocketHandshake {

    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val MAX_HEADER_BYTES = 16 * 1024

    /**
     * Reads one HTTP request from [input] byte-by-byte (never past the header
     * terminator — frame bytes may already be buffered behind it). On a valid
     * upgrade writes the 101 response and returns true; otherwise writes 400
     * and returns false.
     */
    fun perform(input: InputStream, output: OutputStream): Boolean {
        val head = readHead(input) ?: return reject(output)
        val lines = head.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return reject(output)

        val requestLine = lines.first().split(" ")
        if (requestLine.size < 3 || requestLine[0] != "GET") return reject(output)

        val headers = lines.drop(1).mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null
            else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
        }.toMap()

        val upgrade = headers["upgrade"] ?: return reject(output)
        val connection = headers["connection"] ?: return reject(output)
        val key = headers["sec-websocket-key"] ?: return reject(output)
        val version = headers["sec-websocket-version"] ?: return reject(output)

        val valid = upgrade.equals("websocket", ignoreCase = true) &&
            connection.split(',').any { it.trim().equals("upgrade", ignoreCase = true) } &&
            version == "13"
        if (!valid) return reject(output)

        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: ${acceptKeyFor(key)}\r\n" +
            "\r\n"
        output.write(response.toByteArray(Charsets.ISO_8859_1))
        output.flush()
        return true
    }

    fun acceptKeyFor(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((key + GUID).toByteArray(Charsets.ISO_8859_1))
        return Base64.getEncoder().encodeToString(digest)
    }

    /** Reads up to and including the blank-line terminator; null on EOF/overflow. */
    private fun readHead(input: InputStream): String? {
        val buffer = StringBuilder()
        while (buffer.length < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            buffer.append(b.toChar())
            if (buffer.length >= 4 && buffer.endsWith("\r\n\r\n")) return buffer.toString()
        }
        return null
    }

    private fun reject(output: OutputStream): Boolean {
        output.write(
            "HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(Charsets.ISO_8859_1),
        )
        output.flush()
        return false
    }
}
