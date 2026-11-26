package io.yamsergey.dta.protocol.network

import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 072 A2 (R6, FR-008): a JWT is masked by parts; what helps to read expiry stays, personal data and the signature are masked with own fingerprints. */
class JwtMaskTest {
    private val key = FingerprintKey(ByteArray(32) { (it * 3).toByte() })

    @BeforeTest fun install() = Redactor.use(key)
    @AfterTest fun reset() = Redactor.use(null)

    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
    private val signature = "c2lnbmF0dXJlLWJ5dGVzLTEyMzQ1Njc4OQ"
    private fun jwt(header: String, claims: String, sig: String = signature) = "${b64(header)}.${b64(claims)}.$sig"

    private val token = jwt(
        """{"alg":"RS256","typ":"JWT","kid":"k1"}""",
        """{"iss":"https://auth.example.test","aud":"app","sub":"user-4711","iat":1795690860,"nbf":1795690860,"exp":1795694460,"email":"ana@example.test","role":"admin"}""",
    )

    private fun fp(s: String) = key.of(s)

    @Test
    fun `readable parts are shown, subject, other claims and signature carry their own fingerprints`() {
        val m = Redactor.mask(token)
        assertTrue(m.startsWith("[redacted jwt #${fp(token)} "), m)
        for (readable in listOf("alg=RS256", "typ=JWT", "kid=k1", "iss=https://auth.example.test", "aud=app", "iat=2026-11-26T11:01:00Z", "nbf=2026-11-26T11:01:00Z", "exp=2026-11-26T12:01:00Z")) {
            assertTrue(readable in m, "$readable in $m")
        }
        assertTrue("sub=#${fp("user-4711")}" in m, m)
        assertTrue("claims=2" in m && "email=#${fp("ana@example.test")}" in m && "role=#${fp("admin")}" in m, m)
        assertTrue("signature=#${fp(signature)}" in m, m)
        assertFalse(listOf("user-4711", "ana@example.test", "admin", signature).any { it in m }, m)
        assertTrue(m.endsWith("]"), m)
    }

    @Test
    fun `a masked location lists the whole token's fingerprint and the parts under jwt`() {
        val (headers, masked) = Redactor.headers(listOf(Header("Authorization", "Bearer $token")), "e")
        assertEquals("Bearer ${Redactor.mask(token)}", headers.single().value)
        val loc = masked.single()
        assertEquals(fp(token), loc.fingerprint)
        assertEquals(token.length, loc.length)
        val jwt = assertNotNull(loc.jwt)
        assertEquals("RS256", jwt.alg)
        assertEquals("JWT", jwt.typ)
        assertEquals("k1", jwt.kid)
        assertEquals("https://auth.example.test", jwt.iss)
        assertEquals("app", jwt.aud)
        assertEquals("2026-11-26T12:01:00Z", jwt.exp)
        assertEquals(fp("user-4711"), jwt.sub)
        assertEquals(mapOf("email" to fp("ana@example.test"), "role" to fp("admin")), jwt.claims)
        assertEquals(fp(signature), jwt.signature)
    }

    @Test
    fun `a JWT in a cookie, a JSON field and free text is masked by parts too`() {
        val cookie = Redactor.headers(listOf(Header("Cookie", "session=$token; theme=dark")), "e").first.single().value
        assertTrue("session=[redacted jwt #${fp(token)} " in cookie && "theme=${Redactor.mask("dark")}" in cookie, cookie)
        val json = Redactor.body("""{"access_token":"$token"}""", "application/json", "b")
        assertEquals("""{"access_token":"${Redactor.mask(token)}"}""", json.text)
        assertNotNull(json.masked.single().jwt)
        val free = Redactor.text("retry with token=$token later", "t")
        assertEquals("retry with token=${Redactor.mask(token)} later", free.first)
    }

    @Test
    fun `claims that are not there are not shown, an audience list is joined, times that are not numbers are masked claims`() {
        val t = jwt("""{"alg":"HS256"}""", """{"aud":["a","b"],"exp":"soon","sub":"s"}""")
        val m = Redactor.mask(t)
        assertTrue("alg=HS256" in m && "aud=a,b" in m, m)
        assertFalse("typ=" in m || "kid=" in m || "iss=" in m || "iat=" in m || "nbf=" in m, m)
        assertTrue("claims=1 exp=#${fp("soon")}" in m, m)
        assertTrue("sub=#${fp("s")}" in m, m)
    }

    @Test
    fun `an unsecured token with an empty signature is read`() {
        val m = Redactor.mask("${b64("""{"alg":"none"}""")}.${b64("""{"sub":"x"}""")}.")
        assertTrue("alg=none" in m && "sub=#${fp("x")}" in m, m)
    }

    @Test
    fun `a value that only looks like a JWT is masked whole and says so`() {
        for (v in listOf("eyJhbGciOiJ.eyJzdWIiOiJ4.sig", "${b64("""{"a":1}""")}.${b64("[1,2]")}.sig", "${b64("""{"a":1}""")}.not-json.sig")) {
            val m = Redactor.mask(v)
            assertEquals("[redacted len=${v.length} #${fp(v)} not a readable JWT]", m, v)
        }
        val (_, masked) = Redactor.headers(listOf(Header("Authorization", "Bearer eyJhbGciOiJ.eyJzdWIiOiJ4.sig")), "e")
        assertNull(masked.single().jwt)
    }

    @Test
    fun `an ordinary dotted value is a plain mask`() {
        assertEquals("[redacted len=11 #${fp("abc.def.ghi")}]", Redactor.mask("abc.def.ghi"))
        assertEquals("[redacted len=3 #${fp("a.b")}]", Redactor.mask("a.b"))
    }
}
