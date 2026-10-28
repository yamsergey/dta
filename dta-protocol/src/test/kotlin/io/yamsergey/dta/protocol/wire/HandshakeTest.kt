package io.yamsergey.dta.protocol.wire

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandshakeTest {

    private fun request(
        method: String = "GET",
        headers: Map<String, String> = mapOf(
            "Host" to "localhost",
            "Upgrade" to "websocket",
            "Connection" to "Upgrade",
            "Sec-WebSocket-Key" to "dGhlIHNhbXBsZSBub25jZQ==",
            "Sec-WebSocket-Version" to "13",
        ),
    ): String {
        val lines = buildString {
            append("$method / HTTP/1.1\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        return lines
    }

    private fun perform(raw: String): Pair<Boolean, String> {
        val input = ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1))
        val output = ByteArrayOutputStream()
        val ok = WebSocketHandshake.perform(input, output)
        return ok to output.toString(Charsets.ISO_8859_1)
    }

    @Test
    fun `rfc 6455 known-answer accept key`() {
        // Vector straight from RFC 6455 §1.3
        assertEquals(
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            WebSocketHandshake.acceptKeyFor("dGhlIHNhbXBsZSBub25jZQ=="),
        )
    }

    @Test
    fun `valid upgrade gets 101 with correct accept header`() {
        val (ok, response) = perform(request())
        assertTrue(ok)
        assertTrue(response.startsWith("HTTP/1.1 101 "), "got: $response")
        assertTrue(response.contains("Upgrade: websocket"))
        assertTrue(response.contains("Connection: Upgrade"))
        assertTrue(response.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))
        assertTrue(response.endsWith("\r\n\r\n"))
    }

    @Test
    fun `header names are case-insensitive`() {
        val (ok, _) = perform(
            request(
                headers = mapOf(
                    "host" to "x",
                    "upgrade" to "WebSocket",
                    "connection" to "keep-alive, Upgrade",
                    "sec-websocket-key" to "dGhlIHNhbXBsZSBub25jZQ==",
                    "sec-websocket-version" to "13",
                ),
            ),
        )
        assertTrue(ok)
    }

    @Test
    fun `non-GET method is rejected with 400`() {
        val (ok, response) = perform(request(method = "POST"))
        assertFalse(ok)
        assertTrue(response.startsWith("HTTP/1.1 400 "), "got: $response")
    }

    @Test
    fun `missing websocket key is rejected with 400`() {
        val (ok, response) = perform(
            request(
                headers = mapOf(
                    "Host" to "x",
                    "Upgrade" to "websocket",
                    "Connection" to "Upgrade",
                    "Sec-WebSocket-Version" to "13",
                ),
            ),
        )
        assertFalse(ok)
        assertTrue(response.startsWith("HTTP/1.1 400 "))
    }

    @Test
    fun `wrong websocket version is rejected with 400`() {
        val (ok, response) = perform(
            request(
                headers = mapOf(
                    "Host" to "x",
                    "Upgrade" to "websocket",
                    "Connection" to "Upgrade",
                    "Sec-WebSocket-Key" to "dGhlIHNhbXBsZSBub25jZQ==",
                    "Sec-WebSocket-Version" to "8",
                ),
            ),
        )
        assertFalse(ok)
        assertTrue(response.startsWith("HTTP/1.1 400 "))
    }

    @Test
    fun `missing upgrade header is rejected with 400`() {
        val (ok, response) = perform(
            request(
                headers = mapOf(
                    "Host" to "x",
                    "Connection" to "Upgrade",
                    "Sec-WebSocket-Key" to "dGhlIHNhbXBsZSBub25jZQ==",
                    "Sec-WebSocket-Version" to "13",
                ),
            ),
        )
        assertFalse(ok)
        assertTrue(response.startsWith("HTTP/1.1 400 "))
    }

    @Test
    fun `plain http browser request is rejected not crashed`() {
        val (ok, response) = perform("GET /favicon.ico HTTP/1.1\r\nHost: x\r\n\r\n")
        assertFalse(ok)
        assertTrue(response.startsWith("HTTP/1.1 400 "))
    }

    @Test
    fun `handshake does not consume bytes past the header terminator`() {
        // Frames may already sit in the buffer right behind the upgrade request.
        val frameByte = 0x81.toByte()
        val raw = request().toByteArray(Charsets.ISO_8859_1) + byteArrayOf(frameByte)
        val input = ByteArrayInputStream(raw)
        val ok = WebSocketHandshake.perform(input, ByteArrayOutputStream())
        assertTrue(ok)
        assertEquals(frameByte.toInt() and 0xFF, input.read(), "first frame byte must remain unread")
    }

    private fun withAuth(value: String?): Map<String, String> = buildMap {
        put("Host", "localhost"); put("Upgrade", "websocket"); put("Connection", "Upgrade")
        put("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ=="); put("Sec-WebSocket-Version", "13")
        value?.let { put("Authorization", it) }
    }

    private fun performAuthorized(raw: String): Pair<Boolean, String> {
        val input = ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1))
        val output = ByteArrayOutputStream()
        val ok = WebSocketHandshake.perform(input, output) { headers -> headers["authorization"] == "Bearer s3cret" }
        return ok to output.toString(Charsets.ISO_8859_1)
    }

    @Test
    fun `an authorizer refuses a missing or wrong bearer with 401`() {
        val (missing, missingOut) = performAuthorized(request(headers = withAuth(null)))
        assertFalse(missing)
        assertTrue(missingOut.startsWith("HTTP/1.1 401"), missingOut)
        val (wrong, wrongOut) = performAuthorized(request(headers = withAuth("Bearer nope")))
        assertFalse(wrong)
        assertTrue(wrongOut.startsWith("HTTP/1.1 401"), wrongOut)
    }

    @Test
    fun `an authorizer admits the right bearer`() {
        val (ok, out) = performAuthorized(request(headers = withAuth("Bearer s3cret")))
        assertTrue(ok)
        assertTrue(out.startsWith("HTTP/1.1 101"), out)
    }
}
