package io.yamsergey.dta.present.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Feature 026: what Copy puts on the clipboard. */
class EntryCopyTest {
    private val post = Json.parseToJsonElement(
        """{"type":"http","id":"r1:2","method":"POST","url":"https://api.example.com/v1/cart?x=1","state":"complete","status":201,
           "requestHeaders":[{"name":"Content-Type","value":"application/json"},{"name":"X-Note","value":"it's"},{"name":"Content-Length","value":"12"},
             {"name":"Accept-Encoding","value":"gzip"},{"name":":authority","value":"api.example.com"}],
           "responseHeaders":[{"name":"Content-Type","value":"application/json"},{"name":"Set-Cookie","value":"a=1"}]}""",
    ).jsonObject

    @Test
    fun `cURL repeats the request, quoted for the shell`() {
        assertEquals(
            """
            curl -X POST 'https://api.example.com/v1/cart?x=1' \
              -H 'Content-Type: application/json' \
              -H 'X-Note: it'\''s' \
              --compressed \
              --data-raw '{"item":"it'\''s"}'
            """.trimIndent(),
            EntryCopy.curl(post, """{"item":"it's"}"""),
        )
        val get = JsonObject(post - "requestHeaders" + ("method" to JsonPrimitive("GET")))
        assertEquals("curl 'https://api.example.com/v1/cart?x=1'", EntryCopy.curl(get, null))
    }

    @Test
    fun `headers copy as lines, URL as is, the entry as JSON`() {
        assertEquals("Content-Type: application/json\nSet-Cookie: a=1", EntryCopy.headers(post, "responseHeaders"))
        assertNull(EntryCopy.headers(post, "handshakeHeaders"))
        assertEquals("https://api.example.com/v1/cart?x=1", EntryCopy.url(post))
        assertEquals("{\n  \"type\": \"http\",", EntryCopy.json(post).lines().take(2).joinToString("\n"))
    }
}
