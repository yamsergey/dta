package io.yamsergey.dta.protocol.network

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 072 A1: a mask shows the credential's length and a keyed fingerprint; equal credentials share it wherever they occur. */
class RedactorFingerprintTest {
    private val key = FingerprintKey(ByteArray(32) { it.toByte() })

    @BeforeTest fun install() = Redactor.use(key)
    @AfterTest fun reset() = Redactor.use(null)

    private val token = "eyJ-not-a-jwt-TOKEN-0123456789"
    private val maskOfToken = "[redacted len=${token.length} #${key.of(token)}]"
    private val fp = Regex("""#([0-9a-f]{12})\]""")

    @Test
    fun `a fingerprint is 12 lowercase hex, keyed, stable`() {
        assertTrue(Regex("[0-9a-f]{12}").matches(key.of("x")))
        assertEquals(key.of("x"), FingerprintKey(ByteArray(32) { it.toByte() }).of("x"))
        assertNotEquals(key.of("x"), FingerprintKey(ByteArray(32) { (it + 1).toByte() }).of("x"), "another key, another fingerprint")
        assertNotEquals(key.of("x"), key.of("y"))
    }

    @Test
    fun `the mask shows the length of the credential and its fingerprint`() {
        assertEquals(maskOfToken, Redactor.mask(token))
        assertFalse(token in Redactor.mask(token))
    }

    @Test
    fun `a credential has one fingerprint in a header, a query, a form, JSON and free text, without scheme or name`() {
        val (headers, hm) = Redactor.headers(listOf(Header("Authorization", "Bearer $token")), "e")
        assertEquals("Bearer $maskOfToken", headers.single().value, "the scheme stays readable, the credential is masked")
        val (url, um) = Redactor.url("https://h/p?access_token=$token&page=2", "e")
        assertEquals("https://h/p?access_token=$maskOfToken&page=2", url)
        val form = Redactor.body("user=ana&password=$token", "application/x-www-form-urlencoded", "e")
        assertEquals("user=ana&password=$maskOfToken", form.text)
        val json = Redactor.body("""{"token":"$token","n":1}""", "application/json", "e")
        assertEquals("""{"token":"$maskOfToken","n":1}""", json.text)
        val free = Redactor.text("failed: token=$token (retry)", "e")
        assertEquals("failed: token=$maskOfToken (retry)", free.first)
        val scheme = Redactor.text("sent Bearer $token", "e")
        assertEquals("sent Bearer $maskOfToken", scheme.first)
        val all = hm + um + form.masked + json.masked + free.second + scheme.second
        assertEquals(setOf(key.of(token)), all.map { it.fingerprint }.toSet())
        assertTrue(all.all { it.length == token.length }, all.toString())
    }

    @Test
    fun `basic and digest credentials are fingerprinted without the scheme`() {
        val (headers, m) = Redactor.headers(listOf(Header("Proxy-Authorization", "Basic dXNlcjpwYXNz"), Header("Authorization", "opaque-value")), "e")
        assertEquals("Basic ${Redactor.mask("dXNlcjpwYXNz")}", headers[0].value)
        assertEquals(Redactor.mask("opaque-value"), headers[1].value, "no scheme: the whole value is the credential")
        assertEquals(listOf(key.of("dXNlcjpwYXNz"), key.of("opaque-value")), m.map { it.fingerprint })
    }

    @Test
    fun `a cookie masks each cookie's value, names stay readable`() {
        val (headers, m) = Redactor.headers(listOf(Header("Cookie", "session=abc123def; theme=dark"), Header("Set-Cookie", "id=xyz789; Path=/; HttpOnly")), "e")
        assertEquals("session=${Redactor.mask("abc123def")}; theme=${Redactor.mask("dark")}", headers[0].value)
        assertEquals("id=${Redactor.mask("xyz789")}; Path=/; HttpOnly", headers[1].value, "attributes of a Set-Cookie are kept")
        assertEquals(listOf("abc123def", "dark", "xyz789").map { key.of(it) }, m.map { it.fingerprint })
    }

    @Test
    fun `a token returned in JSON and the one a request sends have one fingerprint`() {
        val login = Redactor.body("""{"access_token":"$token"}""", "application/json", "response")
        val request = Redactor.headers(listOf(Header("Authorization", "Bearer $token")), "request").second
        assertEquals(login.masked.single().fingerprint, request.single().fingerprint)
        assertEquals("response $.access_token", login.masked.single().place)
    }

    @Test
    fun `non-string JSON values and subtrees are fingerprinted as written`() {
        val r = Redactor.body("""{"password":12345,"phones":[{"phone":"+1"}],"ok":true}""", "application/json", "b")
        assertTrue("\"password\":\"${Redactor.mask("12345")}\"" in r.text!!, r.text)
        assertTrue("\"phones\":\"${Redactor.mask("""[{"phone":"+1"}]""")}\"" in r.text!!, r.text)
        assertEquals(listOf(5, 16), r.masked.map { it.length })
    }

    @Test
    fun `masking twice changes nothing and lists nothing`() {
        val once = Redactor.body("""{"token":"$token"}""", "application/json", "b")
        val twice = Redactor.body(once.text!!, "application/json", "b")
        assertEquals(once.text, twice.text)
        assertEquals(emptyList(), twice.masked)
        val text = Redactor.text("token=$token", "t")
        assertEquals(emptyList(), Redactor.text(text.first, "t").second)
    }

    @Test
    fun `a masked location carries place, fingerprint and length in an entry, a link and the list form`() {
        val entry = HttpEntry(
            "e1", "r", 1, "okhttp3", method = "GET", url = "https://h/x?token=$token", state = HttpState.COMPLETE,
            requestHeaders = listOf(Header("Authorization", "Bearer $token")),
            responseBody = BodyInfo(BodyPart.RESPONSE, 10, contentType = "application/json", completeness = Completeness.COMPLETE, preview = """{"refresh_token":"$token"}"""),
        )
        val (redacted, info) = Redactor.entry(entry)
        assertEquals(setOf(key.of(token)), info.masked.map { it.fingerprint }.toSet())
        assertEquals(3, info.masked.size, info.masked.toString())
        assertTrue(fp.containsMatchIn((redacted as HttpEntry).requestHeaders!!.single().value))
        assertFalse(token in redacted.toString())
    }
}
