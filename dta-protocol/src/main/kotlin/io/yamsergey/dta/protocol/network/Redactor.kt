package io.yamsergey.dta.protocol.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one redactor (constitution VI), applied by the daemon when output is requested
 * with `redact = true` (US6, FR-030..032). Masks credential headers, and fields whose
 * names look like secrets or personal data in URLs, JSON and form bodies (a sensitive
 * JSON key masks its whole value, nested objects included; a key/value pair object —
 * `{"key": "user.email", "value": …}`, as in OTLP attributes — is the field its key names,
 * feature 006; JSON inside a string value too). Text of other formats, and free text, by
 * name/value rules ([text], decision 2026-11-22); only binary that does not decode is withheld
 * (by the daemon). Every masked or withheld location is listed, so redacted output is safe to
 * share and says what it hid.
 */
object Redactor {

    /** What every mask starts with; a value that starts so is already masked. */
    const val MASK_PREFIX = "[redacted"

    @Volatile private var key: FingerprintKey? = null
    private val processKey: FingerprintKey by lazy { FingerprintKey(java.security.SecureRandom().let { r -> ByteArray(32).also { r.nextBytes(it) } }) }

    /**
     * Installs the key the fingerprints are made under (the daemon passes the one in the DTA home; tests a
     * fixed one); null goes back to a random key of this process, so a process that never installed one
     * still masks consistently within itself.
     */
    fun use(key: FingerprintKey?) { this.key = key }

    private fun key() = key ?: processKey

    /** `[redacted len=N #fp12]` of a [credential] (feature 072, contracts/mask-format.md). */
    fun mask(credential: String): String = "$MASK_PREFIX len=${credential.length} #${key().of(credential)}]"

    fun isMasked(value: String) = value.startsWith(MASK_PREFIX)

    /** Masks [credential] and lists the place with its fingerprint and length. */
    internal fun hide(credential: String, place: String, masked: MutableList<MaskedLocation>): String {
        masked += MaskedLocation(place, key().of(credential), credential.length)
        return mask(credential)
    }

    private val SECRET_HEADERS = setOf("authorization", "proxy-authorization", "cookie", "set-cookie")
    private val SENSITIVE_PARTS = listOf(
        "token", "secret", "password", "passwd", "apikey", "api_key", "api-key", "auth", "session", "cookie",
        "credential", "private", "signature", "email", "phone", "ssn", "card",
    )
    private val json = Json { isLenient = false }

    data class BodyRedaction(
        /** Redacted text; null when withheld. */
        val text: String?,
        val masked: List<MaskedLocation>,
        /** Why the body was withheld; null when it was inspected. */
        val withheld: String?,
    )

    fun isSensitive(name: String): Boolean {
        val lower = name.lowercase()
        return lower in SECRET_HEADERS || SENSITIVE_PARTS.any { it in lower }
    }

    /**
     * Feature 070: false when [body] of [text] cannot differ from [text] but in characters that are
     * neither letters nor digits (no sensitive name in it, no `\u` escape to resolve): the word
     * index then keeps one rendering.
     */
    fun mayChangeWords(text: String): Boolean {
        if ("\\u" in text) return true
        val lower = text.lowercase()
        return SENSITIVE_PARTS.any { it in lower } || CREDENTIAL_WORDS.any { it in lower }
    }

    /** Auth schemes free text can quote a credential after (see [text]). */
    private val CREDENTIAL_WORDS = listOf("bearer", "basic", "digest")

    fun headers(headers: List<Header>, location: String): Pair<List<Header>, List<MaskedLocation>> {
        val masked = mutableListOf<MaskedLocation>()
        val result = headers.map { h ->
            if (isSensitive(h.name)) {
                val value = credentialHeader(h.name, h.value, "$location.header ${h.name}", masked)
                if (value == h.value) h else h.copy(value = value)
            } else {
                // Other values are free text: a Location with a token in its query, a debug header quoting a credential.
                val (value, m) = text(h.value, "$location.header ${h.name}")
                masked += m
                if (m.isEmpty()) h else h.copy(value = value)
            }
        }
        return result to masked
    }

    private val AUTH_SCHEME = Regex("""^(Bearer|Basic|Digest|Token)(\s+)(.+)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /**
     * A credential header's value, masked: the credential without its scheme (`Bearer x` shows `Bearer`, masks `x`,
     * R5); each cookie's value with its name kept (a Set-Cookie's attributes are kept too).
     */
    private fun credentialHeader(name: String, value: String, place: String, masked: MutableList<MaskedLocation>): String {
        when (name.lowercase()) {
            "cookie" -> return value.split(';').joinToString(";") { cookiePair(it, place, masked) }
            "set-cookie" -> {
                val parts = value.split(';')
                return (listOf(cookiePair(parts.first(), place, masked)) + parts.drop(1)).joinToString(";")
            }
        }
        if (isMasked(value)) return value
        val scheme = AUTH_SCHEME.matchEntire(value)
        if (scheme != null) {
            val credential = scheme.groupValues[3]
            return if (isMasked(credential)) value else "${scheme.groupValues[1]}${scheme.groupValues[2]}${hide(credential, place, masked)}"
        }
        return hide(value, place, masked)
    }

    private fun cookiePair(pair: String, place: String, masked: MutableList<MaskedLocation>): String {
        val eq = pair.indexOf('=')
        if (eq < 0) return pair
        val value = pair.substring(eq + 1)
        return if (value.isEmpty() || isMasked(value)) pair else pair.substring(0, eq + 1) + hide(value, place, masked)
    }

    fun url(url: String, location: String): Pair<String, List<MaskedLocation>> {
        val q = url.indexOf('?')
        if (q < 0) return url to emptyList()
        val fragment = url.indexOf('#', q).takeIf { it >= 0 } ?: url.length
        val (query, masked) = pairs(url.substring(q + 1, fragment), "$location url.query")
        return url.substring(0, q + 1) + query + url.substring(fragment) to masked
    }

    private val URL_IN_TEXT = Regex("""[A-Za-z][A-Za-z0-9+.-]*://[^\s"'<>]+""")

    private val SCHEMES = "Bearer|Basic|Digest|Token"

    /** `name=value`, `name: value`, `"name":"value"`; the value may carry an auth scheme, a quoted value may be cut. */
    private val PAIR_IN_TEXT = Regex(
        """(?<![A-Za-z0-9_.\-])(["']?)([A-Za-z0-9_.\-]*[A-Za-z][A-Za-z0-9_.\-]*)\1\s*([:=])\s*((?:(?:$SCHEMES)\s+)?)("[^"]*"?|'[^']*'?|[^\s&;,"'<>)\]}]+)""",
        RegexOption.IGNORE_CASE,
    )
    /** A credential after an auth scheme as HTTP writes it (`Bearer`, `Basic`, `Digest`): not a plain word (it has a digit or a token character). */
    private val CREDENTIAL_IN_TEXT = Regex("""\b(Bearer|Basic|Digest)\s+(?=[A-Za-z0-9\-._~+/]*[0-9\-._~+/])([A-Za-z0-9\-._~+/]{6,}=*)(?![A-Za-z0-9\-._~+/="])""")
    private val ELEMENT_IN_TEXT = Regex("""<([A-Za-z_][\w.\-]*:)?([A-Za-z_][\w.\-]*)(\s[^>]*)?>([^<]*)</\1?\2>""")

    /**
     * Free text — an exception message, a close reason, a script's own words, a text body of no
     * structured format (decision 2026-11-22): the query of every URL in it (as [url] masks it),
     * credentials after an auth scheme (`Bearer …`, `Basic …`), values of name/value pairs whose
     * name is sensitive (`token=…`, `password: …`, `"api_key":"…"`) and the content of elements
     * so named (`<password>…</password>`). The rest of the text is kept.
     */
    fun text(text: String, location: String): Pair<String, List<MaskedLocation>> {
        if (!mayChangeWords(text) && "://" !in text) return text to emptyList()
        val masked = mutableListOf<MaskedLocation>()
        var result = if ("://" in text) URL_IN_TEXT.replace(text) { m -> url(m.value, location).also { (_, l) -> masked += l }.first } else text
        result = textPairs(result, location, masked)
        result = CREDENTIAL_IN_TEXT.replace(result) { m ->
            "${m.groupValues[1]} ${hide(m.groupValues[2], "$location text credential ${m.groupValues[1]}", masked)}"
        }
        result = ELEMENT_IN_TEXT.replace(result) { m ->
            if (!isSensitive(m.groupValues[2]) || m.groupValues[4].isEmpty() || isMasked(m.groupValues[4])) return@replace m.value
            m.value.replaceRange(m.groups[4]!!.range.first - m.range.first, m.groups[4]!!.range.last + 1 - m.range.first, hide(m.groupValues[4], "$location text <${m.groupValues[2]}>", masked))
        }
        return result to masked
    }

    /** [PAIR_IN_TEXT] over [text]: a pair whose name is not sensitive is passed over at its separator, so `failed: token=…` is still found. */
    private fun textPairs(text: String, location: String, masked: MutableList<MaskedLocation>): String {
        val out = StringBuilder()
        var at = 0
        var from = 0
        while (true) {
            val m = PAIR_IN_TEXT.find(text, from) ?: break
            val name = m.groupValues[2]
            val value = m.groupValues[5]
            val bare = value.trim('"', '\'')
            if (!isSensitive(name) || isMasked(bare) || bare.isEmpty()) {
                from = m.groups[4]!!.range.first.coerceAtLeast(m.range.first + 1)
                continue
            }
            val open = value.takeWhile { it == '"' || it == '\'' }.take(1)
            val close = if (open.isNotEmpty() && value.length > 1 && value.endsWith(open)) open else ""
            out.append(text, at, m.groups[4]!!.range.first).append(open).append(hide(bare, "$location text $name", masked)).append(close)
            at = m.range.last + 1
            from = at
        }
        return out.append(text, at, text.length).toString()
    }

    fun body(text: String, contentType: String?, location: String): BodyRedaction {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type == "application/x-www-form-urlencoded") {
            val (redacted, masked) = pairs(text, "$location form")
            return BodyRedaction(redacted, masked, null)
        }
        val parsed = if (type == null || "json" in type) runCatching { json.parseToJsonElement(text) }.getOrNull() else null
        if (parsed != null) {
            val masked = mutableListOf<MaskedLocation>()
            return BodyRedaction(redactJson(parsed, "$", location, masked).toString(), masked, null)
        }
        // Decision 2026-11-22: text of any other format (plain, XML, a frame protocol, a cut preview) by the free-text rules.
        val (masked, where) = text(text, location)
        return BodyRedaction(masked, where, null)
    }

    /** List form of an entry: URL, headers and previews. Withheld previews are dropped. */
    fun entry(entry: NetworkEntry): Pair<NetworkEntry, RedactionInfo> {
        val masked = mutableListOf<MaskedLocation>()
        val withheld = mutableListOf<String>()
        val at = "entry ${entry.id}"
        fun preview(info: BodyInfo?, name: String): BodyInfo? {
            val text = info?.preview ?: return info
            val r = body(text, info.contentType, "$at $name preview")
            masked += r.masked
            r.withheld?.let { withheld += it }
            return info.copy(preview = r.text)
        }
        fun hdrs(list: List<Header>?, name: String) = list?.let { headers(it, "$at $name").also { (_, m) -> masked += m }.first }
        fun said(value: String?, name: String) = value?.let { text(it, "$at $name").also { (_, m) -> masked += m }.first }
        fun script(record: InterventionRecord?) = record?.copy(error = said(record.error, "script error"), description = said(record.description, "script description"))

        val redacted = when (entry) {
            is HttpEntry -> {
                val (u, m) = url(entry.url, at)
                masked += m
                entry.copy(
                    url = u,
                    failure = said(entry.failure, "failure"),
                    intervention = script(entry.intervention),
                    requestHeaders = hdrs(entry.requestHeaders, "request"),
                    responseHeaders = hdrs(entry.responseHeaders, "response"),
                    requestBody = preview(entry.requestBody, "request"),
                    responseBody = preview(entry.responseBody, "response"),
                    // Feature 005: the app-side copies a script keeps are the same traffic (FR-017).
                    appRequest = entry.appRequest?.let { a ->
                        val (au, am) = url(a.url, "$at app-request")
                        masked += am
                        a.copy(url = au, headers = hdrs(a.headers, "app-request")!!, body = preview(a.body, "app-request"))
                    },
                    appResponse = entry.appResponse?.let { a ->
                        a.copy(headers = hdrs(a.headers, "app-response")!!, body = preview(a.body, "app-response"))
                    },
                )
            }
            is WsConnectionEntry -> {
                val (u, m) = url(entry.url, at)
                masked += m
                entry.copy(
                    url = u,
                    handshakeHeaders = hdrs(entry.handshakeHeaders, "handshake"),
                    failure = said(entry.failure, "failure"),
                    closeReason = said(entry.closeReason, "close reason"),
                    intervention = script(entry.intervention),
                )
            }
            is WsMessageEntry -> entry.copy(
                payload = preview(entry.payload, "payload")!!,
                appPayload = preview(entry.appPayload, "app-payload"),
                // The socket's URL a message carries (feature 025) is the connection's, masked alike.
                url = entry.url?.let { u -> url(u, at).also { (_, m) -> masked += m }.first },
                intervention = script(entry.intervention),
            )
            // Feature 012: the same rules over a link's URI, its query and its extras.
            is LinkEntry -> {
                val (u, m) = url(entry.uri, at)
                masked += m
                val (query, qm) = headers(entry.query, "$at query")
                masked += qm
                val extras = entry.extras.map { e ->
                    if (e.value != null && isSensitive(e.key) && !isMasked(e.value!!)) {
                        e.copy(value = hide(e.value!!, "$at extra ${e.key}", masked))
                    } else e
                }
                entry.copy(uri = u, query = query, extras = extras)
            }
        }
        return redacted to RedactionInfo(masked = masked, withheld = withheld)
    }

    private fun pairs(encoded: String, location: String): Pair<String, List<MaskedLocation>> {
        val masked = mutableListOf<MaskedLocation>()
        val result = encoded.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            val name = if (eq < 0) pair else pair.substring(0, eq)
            if (eq >= 0 && isSensitive(name) && !isMasked(pair.substring(eq + 1))) {
                // The credential is the value as it reads decoded (R5): a token has one fingerprint in a query and in a header.
                "$name=${hide(percentDecode(pair.substring(eq + 1)), "$location $name", masked)}"
            } else pair
        }
        return result to masked
    }

    /** The credential a sensitive JSON value is: a string unescaped, anything else as written; null when it is masked already. */
    private fun jsonCredential(value: JsonElement): String? = when {
        value is JsonPrimitive && value.isString -> value.content.takeUnless { isMasked(it) }
        else -> value.toString()
    }

    private fun hideJson(value: JsonElement, place: String, masked: MutableList<MaskedLocation>): JsonElement =
        jsonCredential(value)?.let { JsonPrimitive(hide(it, place, masked)) } ?: value

    private fun redactJson(element: JsonElement, path: String, location: String, masked: MutableList<MaskedLocation>): JsonElement = when (element) {
        is JsonObject -> pairKey(element)?.takeIf { isSensitive(it) }?.let { key ->
            JsonObject(element + ("value" to hideJson(element.getValue("value"), "$location $path.value (key $key)", masked)))
        } ?: JsonObject(
            element.mapValues { (key, value) ->
                val childPath = "$path.$key"
                if (isSensitive(key)) hideJson(value, "$location $childPath", masked)
                else redactJson(value, childPath, location, masked)
            },
        )
        is JsonArray -> JsonArray(element.mapIndexed { i, value -> redactJson(value, "$path[$i]", location, masked) })
        is JsonPrimitive -> nested(element, path, location, masked) ?: freeText(element, path, location, masked) ?: element
    }

    /** A string value of free text (decision 2026-11-22): the [text] rules; null when nothing in it is masked. */
    private fun freeText(element: JsonPrimitive, path: String, location: String, masked: MutableList<MaskedLocation>): JsonPrimitive? {
        if (!element.isString) return null
        val (text, m) = text(element.content, "$location $path")
        if (m.isEmpty()) return null
        masked += m
        return JsonPrimitive(text)
    }

    /** Decision 2026-11-22: JSON carried in a string value, masked inside; null when it is not JSON or holds nothing to mask. */
    private fun nested(element: JsonPrimitive, path: String, location: String, masked: MutableList<MaskedLocation>): JsonPrimitive? {
        if (!element.isString) return null
        val text = element.content.trimStart()
        if (!(text.startsWith("{") || text.startsWith("["))) return null
        val parsed = runCatching { json.parseToJsonElement(element.content) }.getOrNull() ?: return null
        val inner = mutableListOf<MaskedLocation>()
        val redacted = redactJson(parsed, "$path(json)", location, inner)
        if (inner.isEmpty()) return null
        masked += inner
        return JsonPrimitive(redacted.toString())
    }

    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val hex = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) { out.write(hex); i += 3 } else { out.write(c.toString().toByteArray()); i++ }
        }
        return out.toString(Charsets.UTF_8)
    }

    /** The named field of a key/value pair object: a string `key` next to a `value`. */
    private fun pairKey(element: JsonObject): String? {
        val key = element["key"] as? JsonPrimitive ?: return null
        return if (key.isString && "value" in element) key.content else null
    }
}
