package io.yamsergey.dta.protocol.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.util.Base64

/**
 * 072 R6 / FR-008: what a JWT shows when it is masked. Readable: `alg`, `typ`, `kid` of the header,
 * `iss`, `aud` and the times `iat`, `nbf`, `exp` (ISO UTC) of the claims — what answers "is this token
 * stale?". Masked with their own fingerprints: `sub`, every other claim and the signature.
 */
@Serializable
data class JwtParts(
    val alg: String? = null,
    val typ: String? = null,
    val kid: String? = null,
    val iss: String? = null,
    val aud: String? = null,
    val iat: String? = null,
    val nbf: String? = null,
    val exp: String? = null,
    /** Fingerprint of `sub`. */
    val sub: String? = null,
    /** Other claims: name to the fingerprint of the value as written. */
    val claims: Map<String, String> = emptyMap(),
    /** Fingerprint of the signature part as written (empty for an unsecured token). */
    val signature: String,
) {
    /** `[redacted jwt #<whole> alg=… … sub=#… claims=N name=#… signature=#…]` (contracts/mask-format.md). */
    fun mask(whole: String): String = buildString {
        append(Redactor.MASK_PREFIX).append(" jwt #").append(whole)
        alg?.let { append(" alg=").append(it) }
        typ?.let { append(" typ=").append(it) }
        kid?.let { append(" kid=").append(it) }
        iss?.let { append(" iss=").append(it) }
        aud?.let { append(" aud=").append(it) }
        iat?.let { append(" iat=").append(it) }
        nbf?.let { append(" nbf=").append(it) }
        exp?.let { append(" exp=").append(it) }
        sub?.let { append(" sub=#").append(it) }
        if (claims.isNotEmpty()) {
            append(" claims=").append(claims.size)
            claims.forEach { (name, fp) -> append(' ').append(name).append("=#").append(fp) }
        }
        append(" signature=#").append(signature).append(']')
    }

    companion object {
        private val json = Json { isLenient = false }
        private val B64URL = Regex("[A-Za-z0-9_-]+")
        private val dec = Base64.getUrlDecoder()

        /** True when [value] has the shape of a JWT (`eyJ…` header, three parts) whether or not it can be read. */
        fun looksLike(value: String): Boolean {
            val parts = value.split('.')
            return parts.size == 3 && parts[0].startsWith("eyJ") && B64URL.matches(parts[0]) && B64URL.matches(parts[1]) && (parts[2].isEmpty() || B64URL.matches(parts[2]))
        }

        /** The parts of [value] when it is a JWT whose first two parts decode to JSON objects; null otherwise. */
        fun parse(value: String, fingerprint: (String) -> String): JwtParts? {
            if (!looksLike(value)) return null
            val parts = value.split('.')
            val header = obj(parts[0]) ?: return null
            val claims = obj(parts[1]) ?: return null
            fun text(o: JsonObject, name: String): String? = (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun time(name: String): String? = (claims[name] as? JsonPrimitive)?.takeIf { !it.isString }
                ?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }?.let { Instant.ofEpochSecond(it).toString() }
            val aud = when (val a = claims["aud"]) {
                is JsonPrimitive -> a.takeIf { it.isString }?.content
                is JsonArray -> a.takeIf { l -> l.isNotEmpty() && l.all { it is JsonPrimitive && it.isString } }?.joinToString(",") { (it as JsonPrimitive).content }
                else -> null
            }
            val readable = buildSet {
                if (text(claims, "iss") != null) add("iss")
                if (aud != null) add("aud")
                for (t in listOf("iat", "nbf", "exp")) if (time(t) != null) add(t)
            }
            val sub = claims["sub"]?.let { credential(it) }?.let(fingerprint)
            val others = claims.filterKeys { it !in readable && it != "sub" }.mapValues { (_, v) -> fingerprint(credential(v)!!) }
            return JwtParts(
                alg = text(header, "alg"), typ = text(header, "typ"), kid = text(header, "kid"),
                iss = text(claims, "iss"), aud = aud, iat = time("iat"), nbf = time("nbf"), exp = time("exp"),
                sub = sub, claims = others, signature = fingerprint(parts[2]),
            )
        }

        private fun credential(v: JsonElement): String? = when {
            v is JsonNull -> null
            v is JsonPrimitive && v.isString -> v.content
            else -> v.toString()
        }

        private fun obj(part: String): JsonObject? = runCatching { json.parseToJsonElement(String(dec.decode(part), Charsets.UTF_8)) as? JsonObject }.getOrNull()
    }
}
