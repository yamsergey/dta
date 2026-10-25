package io.yamsergey.dta.protocol.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RedactorTest {

    @Test
    fun `credential headers are masked, names kept`() {
        val (headers, masked) = Redactor.headers(
            listOf(
                Header("Authorization", "Bearer SECRET-1"),
                Header("Proxy-Authorization", "Basic SECRET-2"),
                Header("Cookie", "session=SECRET-3"),
                Header("Set-Cookie", "id=SECRET-4; Path=/"),
                Header("X-Api-Key", "SECRET-5"),
                Header("Content-Type", "application/json"),
            ),
            "entry e1 request",
        )
        assertEquals(listOf("Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie", "X-Api-Key", "Content-Type"), headers.map { it.name })
        assertTrue(headers.take(5).all { it.value == Redactor.MASK })
        assertEquals("application/json", headers.last().value)
        assertEquals("entry e1 request.header Authorization", masked.first())
        assertEquals(5, masked.size)
    }

    @Test
    fun `sensitive query parameters are masked in urls`() {
        val (url, masked) = Redactor.url("https://h/p?access_token=SECRET&page=2&email=a%40b.c", "entry e1")
        assertEquals("https://h/p?access_token=${Redactor.MASK}&page=2&email=${Redactor.MASK}", url)
        assertEquals(listOf("entry e1 url.query access_token", "entry e1 url.query email"), masked)
        assertEquals("https://h/p" to emptyList<String>(), Redactor.url("https://h/p", "x"))
    }

    @Test
    fun `json bodies are masked at any depth with structure and other values kept`() {
        val result = Redactor.body(
            """{"token":"SECRET-T","user":{"email":"a@b.c","name":"visible","phones":[{"phone":"+1"}]},"refresh_token":42,"count":3}""",
            "application/json",
            "entry e1 response",
        )
        val json = Json.parseToJsonElement(result.text!!).jsonObject
        assertEquals(Redactor.MASK, json["token"]!!.jsonPrimitive.content)
        assertEquals(Redactor.MASK, json["user"]!!.jsonObject["email"]!!.jsonPrimitive.content)
        assertEquals("visible", json["user"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(Redactor.MASK, json["user"]!!.jsonObject["phones"]!!.jsonPrimitive.content, "a sensitive key masks its whole subtree")
        assertEquals(Redactor.MASK, json["refresh_token"]!!.jsonPrimitive.content)
        assertEquals(3, json["count"]!!.jsonPrimitive.content.toInt())
        assertTrue("entry e1 response $.token" in result.masked, "${result.masked}")
        assertTrue("entry e1 response $.user.phones" in result.masked, "${result.masked}")
        assertFalse(listOf("SECRET-T", "a@b.c", "+1").any { it in result.text!! })
        assertNull(result.withheld)
    }

    @Test
    fun `json is recognized by content type or by shape`() {
        assertEquals(Redactor.MASK, Json.parseToJsonElement(Redactor.body("""{"password":"p"}""", null, "x").text!!).jsonObject["password"]!!.jsonPrimitive.content)
        assertEquals(Redactor.MASK, Json.parseToJsonElement(Redactor.body("""{"password":"p"}""", "application/vnd.api+json", "x").text!!).jsonObject["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `form bodies are masked by field name`() {
        val result = Redactor.body("username=ana&password=SECRET&remember=1", "application/x-www-form-urlencoded", "entry e2 request")
        assertEquals("username=ana&password=${Redactor.MASK}&remember=1", result.text)
        assertEquals(listOf("entry e2 request form password"), result.masked)
    }

    @Test
    fun `unrecognized formats are withheld, stating why`() {
        for ((text, type) in listOf("token SECRET" to "text/plain", "<a>SECRET</a>" to "application/xml", "not json {" to null)) {
            val result = Redactor.body(text, type, "entry e3 response")
            assertNull(result.text, "$type must not pass through")
            assertTrue("entry e3 response" in result.withheld!!, result.withheld)
        }
    }

    @Test
    fun `entries get urls and headers masked, previews redacted or dropped`() {
        val entry = HttpEntry(
            "e1", "r", 1, "okhttp3", method = "POST", url = "https://h/login?token=SECRET-Q", state = HttpState.COMPLETE,
            requestHeaders = listOf(Header("Authorization", "Bearer SECRET-H")),
            requestBody = BodyInfo(BodyPart.REQUEST, 10, contentType = "application/json", completeness = Completeness.COMPLETE, preview = """{"password":"SECRET-P"}"""),
            responseBody = BodyInfo(BodyPart.RESPONSE, 10, contentType = "text/plain", completeness = Completeness.COMPLETE, preview = "SECRET-PLAIN"),
        )
        val (redacted, info) = Redactor.entry(entry)
        val text = redacted.toString()
        assertFalse(listOf("SECRET-Q", "SECRET-H", "SECRET-P", "SECRET-PLAIN").any { it in text }, text)
        redacted as HttpEntry
        assertTrue(redacted.requestBody!!.preview!!.contains(Redactor.MASK))
        assertNull(redacted.responseBody!!.preview)
        assertTrue(info.masked.isNotEmpty())
        assertTrue(info.withheld.any { "response preview" in it }, "${info.withheld}")
    }

    @Test
    fun `message payload previews are redacted like bodies`() {
        val message = WsMessageEntry(
            "m1", "r", 1, "okhttp3-ws", connectionId = "c1", direction = Direction.IN, opcode = MessageOpcode.TEXT,
            payload = BodyInfo(BodyPart.MESSAGE, 10, completeness = Completeness.COMPLETE, preview = """{"session":"SECRET-S","event":"x"}"""),
        )
        val (redacted, _) = Redactor.entry(message)
        val preview = (redacted as WsMessageEntry).payload.preview!!
        assertFalse("SECRET-S" in preview)
        assertTrue("\"event\":\"x\"" in preview)
    }
}
