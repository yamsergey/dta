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

    /** Decision 2026-11-22 (4): text of other formats is masked by the free-text rules, saying what, instead of withheld whole. */
    @Test
    fun `text of other formats is masked by name and value, not withheld`() {
        for ((text, type, expected) in listOf(
            Triple("token=SECRET-1 user=ana", "text/plain", "token=${Redactor.MASK} user=ana"),
            Triple("<a><password>SECRET-2</password><b>keep</b></a>", "application/xml", "<a><password>${Redactor.MASK}</password><b>keep</b></a>"),
            Triple("""{"email":"SECRET-3","na""", null, """{"email":"${Redactor.MASK}","na"""),
            Triple("42[\"auth\",{\"token\":\"SECRET-4\"}]", "text/plain", "42[\"auth\",{\"token\":\"${Redactor.MASK}\"}]"),
            Triple("nothing to hide here", "text/plain", "nothing to hide here"),
        )) {
            val result = Redactor.body(text, type, "entry e3 response")
            assertEquals(expected, result.text, "$type: $text")
            assertNull(result.withheld)
            assertEquals(text != expected, result.masked.isNotEmpty(), "masked is listed: ${result.masked}")
            assertTrue(result.masked.all { it.startsWith("entry e3 response text") }, result.masked.toString())
        }
    }

    @Test
    fun `entries get urls and headers masked, previews redacted`() {
        val entry = HttpEntry(
            "e1", "r", 1, "okhttp3", method = "POST", url = "https://h/login?token=SECRET-Q", state = HttpState.COMPLETE,
            requestHeaders = listOf(Header("Authorization", "Bearer SECRET-H")),
            requestBody = BodyInfo(BodyPart.REQUEST, 10, contentType = "application/json", completeness = Completeness.COMPLETE, preview = """{"password":"SECRET-P"}"""),
            responseBody = BodyInfo(BodyPart.RESPONSE, 10, contentType = "text/plain", completeness = Completeness.COMPLETE, preview = "session=SECRET-PLAIN; ok"),
        )
        val (redacted, info) = Redactor.entry(entry)
        val text = redacted.toString()
        assertFalse(listOf("SECRET-Q", "SECRET-H", "SECRET-P", "SECRET-PLAIN").any { it in text }, text)
        redacted as HttpEntry
        assertTrue(redacted.requestBody!!.preview!!.contains(Redactor.MASK))
        assertEquals("session=${Redactor.MASK}; ok", redacted.responseBody!!.preview, "a text preview is masked, not dropped")
        assertTrue(info.masked.any { "response preview text session" in it }, "${info.masked}")
        assertTrue(info.withheld.isEmpty(), "${info.withheld}")
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

    @Test
    fun `key value pairs are masked by the key they name`() {
        val otlp = """{"attributes":[
            {"key":"user.email","value":{"stringValue":"alice@example.com"}},
            {"key":"order.id","value":{"stringValue":"ord-42"}},
            {"key":"session.token","value":{"stringValue":"sk-secret-123"}}]}"""
        val r = Redactor.body(otlp, "application/json", "entry e request body")
        val text = r.text!!
        assertFalse("alice@example.com" in text || "sk-secret-123" in text, text)
        assertTrue("\"key\":\"user.email\"" in text && "ord-42" in text, "keys and harmless values stay: $text")
        assertEquals(
            listOf(
                "entry e request body $.attributes[0].value (key user.email)",
                "entry e request body $.attributes[2].value (key session.token)",
            ),
            r.masked,
        )
    }

    @Test
    fun `key value pairs are found at any depth, in arrays of arrays too`() {
        val nested = """{"resourceLogs":[{"scopeLogs":[{"logRecords":[{"attributes":[{"key":"auth.password","value":{"stringValue":"hunter2"}}]}]}]}]}"""
        val text = Redactor.body(nested, "application/json", "x").text!!
        assertFalse("hunter2" in text, text)
        val arrays = """[[{"key":"api_key","value":"K-1"}]]"""
        assertFalse("K-1" in Redactor.body(arrays, "application/json", "x").text!!)
    }

    @Test
    fun `objects that only look like pairs are ordinary JSON`() {
        val numberKey = """{"key":7,"value":"stays"}"""
        assertTrue("stays" in Redactor.body(numberKey, "application/json", "x").text!!)
        val noValue = """{"key":"password","other":"stays"}"""
        assertTrue("stays" in Redactor.body(noValue, "application/json", "x").text!!)
    }

    /** NET-RED-020: the app-side copies a script keeps (005 FR-017) are masked like the wire view. */
    @Test
    fun `app-side copies of a scripted exchange and message are masked like the wire view`() {
        val json = """{"token":"SECRET-B","name":"visible"}"""
        fun body(part: BodyPart) = BodyInfo(part, json.length.toLong(), contentType = "application/json", completeness = Completeness.COMPLETE, preview = json)
        val http = HttpEntry(
            id = "e1", runId = "r", timeMs = 1, library = "okhttp3", method = "POST", url = "https://h/p?page=2",
            state = HttpState.COMPLETE, status = 200,
            appRequest = HttpRequestSnapshot("https://h/p?page=2&access_token=SECRET-Q", "POST", listOf(Header("Authorization", "Bearer SECRET-H"), Header("Cookie", "s=SECRET-C")), body(BodyPart.APP_REQUEST)),
            appResponse = HttpResponseSnapshot(200, listOf(Header("Set-Cookie", "s=SECRET-R")), body(BodyPart.APP_RESPONSE)),
        )
        val (r, info) = Redactor.entry(http)
        val text = r.toString()
        assertFalse("SECRET" in text, text)
        val app = (r as HttpEntry).appRequest!!
        assertTrue("page=2" in app.url && "\"name\":\"visible\"" in app.body!!.preview!!, "the rest stays: $app")
        assertEquals(listOf("Authorization", "Cookie"), app.headers.map { it.name })
        for (place in listOf("app-request url.query access_token", "app-request.header Authorization", "app-request preview $.token", "app-response.header Set-Cookie", "app-response preview $.token")) {
            assertTrue(info.masked.any { place in it }, "masked names $place: ${info.masked}")
        }

        val message = WsMessageEntry(
            id = "m1", runId = "r", timeMs = 2, library = "okhttp3-ws", connectionId = "c1", direction = Direction.OUT, opcode = MessageOpcode.TEXT,
            payload = BodyInfo(BodyPart.MESSAGE, 2, completeness = Completeness.COMPLETE, preview = "{}"),
            appPayload = BodyInfo(BodyPart.APP_MESSAGE, json.length.toLong(), completeness = Completeness.COMPLETE, preview = json),
        )
        val (m, mi) = Redactor.entry(message)
        assertFalse("SECRET" in m.toString(), m.toString())
        assertTrue(mi.masked.any { "app-payload preview $.token" in it }, mi.masked.toString())
    }

    /** A message carries its socket's URL (feature 025): the handshake query is masked there too. */
    @Test
    fun `a message's socket url is masked like its connection's`() {
        val message = WsMessageEntry(
            id = "m1", runId = "r", timeMs = 2, library = "okhttp3-ws", connectionId = "c1", direction = Direction.IN, opcode = MessageOpcode.TEXT,
            payload = BodyInfo(BodyPart.MESSAGE, 2, completeness = Completeness.COMPLETE, preview = "{}"),
            url = "wss://h/socket?room=7&token=SECRET-WS",
        )
        val (m, info) = Redactor.entry(message)
        assertEquals("wss://h/socket?room=7&token=${Redactor.MASK}", (m as WsMessageEntry).url)
        assertTrue(info.masked.any { "url.query token" in it }, info.masked.toString())
    }

    /** Free text that quotes a URL (an exception message, a close reason, a script's words) has its query masked. */
    @Test
    fun `urls quoted in failures, close reasons and script words are masked`() {
        val http = HttpEntry(
            id = "e1", runId = "r", timeMs = 1, library = "urlconnection", method = "GET", url = "https://h/p?page=2&access_token=SECRET-Q",
            state = HttpState.FAILED, failure = "java.io.FileNotFoundException: https://h/p?page=2&access_token=SECRET-Q",
            intervention = InterventionRecord("s1", error = "TypeError at https://h/p?api_key=SECRET-E", description = "rewrote https://h/p?session=SECRET-D"),
        )
        val (r, info) = Redactor.entry(http)
        assertFalse("SECRET" in r.toString(), r.toString())
        assertEquals("java.io.FileNotFoundException: https://h/p?page=2&access_token=${Redactor.MASK}", (r as HttpEntry).failure)
        assertTrue(info.masked.any { "failure" in it && "access_token" in it }, info.masked.toString())
        val socket = WsConnectionEntry(
            id = "c1", runId = "r", timeMs = 1, library = "okhttp3-ws", url = "wss://h/s", state = WsState.FAILED,
            failure = "Expected HTTP 101 response but was '401' at wss://h/s?token=SECRET-F", closeReason = "bye wss://h/s?token=SECRET-C",
        )
        assertFalse("SECRET" in Redactor.entry(socket).first.toString())
        assertEquals("no url here", Redactor.text("no url here", "x").first)
    }

    /** Decision 2026-11-22 (4): JSON carried inside a string value is parsed and masked inside, staying a string. */
    @Test
    fun `json nested in a string value is masked inside`() {
        val body = """{"event":"login","data":"{\"token\":\"SECRET-N\",\"user\":\"ana\"}","list":"[{\"password\":\"SECRET-L\"}]","note":"{not json"}"""
        val r = Redactor.body(body, "application/json", "entry e1 request body")
        val text = r.text!!
        assertFalse("SECRET-N" in text || "SECRET-L" in text, text)
        val parsed = Json.parseToJsonElement(text).jsonObject
        val data = Json.parseToJsonElement(parsed["data"]!!.jsonPrimitive.content).jsonObject
        assertEquals(Redactor.MASK, data["token"]!!.jsonPrimitive.content)
        assertEquals("ana", data["user"]!!.jsonPrimitive.content, "the rest of the nested JSON stays")
        assertEquals("{not json", parsed["note"]!!.jsonPrimitive.content)
        assertEquals(listOf("entry e1 request body $.data(json).token", "entry e1 request body $.list(json)[0].password"), r.masked)
        val untouched = """{"data":"{\"user\":\"ana\"}"}"""
        assertEquals(untouched, Redactor.body(untouched, "application/json", "x").text, "nested JSON with nothing to mask is kept as written")
    }

    /** Decision 2026-11-22 (4): free text loses credentials and sensitive name/value pairs, not only URL queries. */
    @Test
    fun `free text masks credentials and sensitive name value pairs`() {
        val cases = listOf(
            "401 for Authorization: Bearer SECRET-B1.x_y" to "401 for Authorization: ${Redactor.MASK}",
            "sent Bearer SECRET-B2abc to the proxy" to "sent Bearer ${Redactor.MASK} to the proxy",
            "Basic U0VDUkVULUIz= refused" to "Basic ${Redactor.MASK} refused",
            "login token=SECRET-K1 user=ana" to "login token=${Redactor.MASK} user=ana",
            "password: SECRET-K2, retry in 5s" to "password: ${Redactor.MASK}, retry in 5s",
            "bad field \"api_key\":\"SECRET-K3\" in request" to "bad field \"api_key\":\"${Redactor.MASK}\" in request",
            "cut preview {\"session\":\"SECRET-K4" to "cut preview {\"session\":\"${Redactor.MASK}",
            "<user><password>SECRET-X1</password><name>ana</name></user>" to "<user><password>${Redactor.MASK}</password><name>ana</name></user>",
            "Authorization: Token SECRET-T1" to "Authorization: ${Redactor.MASK}",
            "see https://h/p?page=2&token=SECRET-U1" to "see https://h/p?page=2&token=${Redactor.MASK}",
            "WWW-Authenticate realm is \"api\"; page=2, name=visible" to "WWW-Authenticate realm is \"api\"; page=2, name=visible",
        )
        for ((given, expected) in cases) {
            val (text, masked) = Redactor.text(given, "entry e1 failure")
            assertEquals(expected, text, given)
            assertEquals(given != expected, masked.isNotEmpty(), "masked is listed: $masked")
            assertTrue(masked.all { it.startsWith("entry e1 failure") }, masked.toString())
        }
    }

    /** The same rules over header values whose names are not sensitive, and over JSON string values. */
    @Test
    fun `free text rules apply to other header values and to json strings`() {
        val (headers, masked) = Redactor.headers(
            listOf(Header("Location", "https://h/cb?code=1&access_token=SECRET-H1"), Header("X-Debug", "Bearer SECRET-H2abc"), Header("Accept", "application/json")),
            "entry e1 response",
        )
        assertFalse(headers.any { "SECRET" in it.value }, headers.toString())
        assertEquals("application/json", headers.last().value)
        assertTrue(masked.any { "header Location" in it } && masked.any { "header X-Debug" in it }, masked.toString())
        val body = Redactor.body("""{"message":"failed for token=SECRET-J1","status":"ok"}""", "application/json", "x")
        assertFalse("SECRET-J1" in body.text!!, body.text)
        assertTrue("\"status\":\"ok\"" in body.text!!)
        assertTrue(body.masked.any { "$.message" in it }, body.masked.toString())
    }
}
