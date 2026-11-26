package io.yamsergey.dta.protocol.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where a search hit is (feature 040). */
@Serializable
enum class SearchPlace {
    @SerialName("url") URL,
    @SerialName("request-header") REQUEST_HEADER,
    @SerialName("response-header") RESPONSE_HEADER,
    @SerialName("request-body") REQUEST_BODY,
    @SerialName("response-body") RESPONSE_BODY,
    @SerialName("payload") PAYLOAD,
    @SerialName("envelope") ENVELOPE,
    /** 072: free text an entry carries (a failure, a close reason, a script's error): found by `redacted:#fp` only. */
    @SerialName("failure") FAILURE,
}

/**
 * One match of a search term: [start]..[end] in the text of its place — the URL, the header's
 * value (or its name when [inName]), the body text as `network.body` gives it (decoded JSON
 * for a schema-decoded binary body; [binary] when found as bytes, with no usable offsets).
 */
@Serializable
data class SearchHit(
    val id: String,
    val place: SearchPlace,
    val term: String,
    val start: Int,
    val end: Int,
    val excerpt: Fuzzy.Excerpt,
    /** The body part (request, response, app-side, message) for body places. */
    val part: BodyPart? = null,
    val header: String? = null,
    val inName: Boolean = false,
    val binary: Boolean = false,
)

/**
 * Feature 040: the search words of a query line applied to entries — every term must occur
 * somewhere in the same entry; the result is where. Pure, with the same ports as
 * [NetworkFilterEval], so the app and the daemon search alike.
 */
class NetworkSearch(
    private val terms: List<Term>,
    private val connectionUrl: (String) -> String?,
    private val decoded: ((NetworkEntry, BodyPart) -> String?)? = null,
    private val bodyBytes: (NetworkEntry, BodyPart) -> NetworkFilterEval.BodyBytes?,
    /** Search URLs, headers and bodies as redacted (masked values are not found). */
    private val redact: Boolean = false,
) {
    /** Bodies not searched: encoding not undone, or not inspectable while redacting. */
    val notSearched: Int get() = ownNotSearched + (maskedSearch?.ownNotSearched ?: 0)
    private var ownNotSearched = 0

    /** Feature 072: the query has a `redacted:#fp` term, which reads masked places only. */
    val searchesMaskedOnly: Boolean = terms.any { it.redacted }

    /** Without redaction (the plugin's own views), `redacted:#fp` still reads the redacted rendering: this search, redacting. */
    private var maskedSearch: NetworkSearch? = null
    private fun maskedPlaces(entry: NetworkEntry): List<Place> =
        (if (redact) places(entry) else (maskedSearch ?: NetworkSearch(emptyList(), connectionUrl, decoded, bodyBytes, redact = true).also { maskedSearch = it }).places(entry)) + freeTextPlaces(entry)

    /** The free text of an entry that redaction masks (a failure, a close reason, what a script said): places a fingerprint is found in, though no word search reads them. */
    private fun freeTextPlaces(entry: NetworkEntry): List<Place> = buildList {
        fun free(text: String?) {
            text ?: return
            val shown = Redactor.text(text, "").first
            if (shown != text) add(Place(SearchPlace.FAILURE, Redactor.plain(shown), shown = shown))
        }
        when (entry) {
            is HttpEntry -> free(entry.failure)
            is WsConnectionEntry -> { free(entry.failure); free(entry.closeReason) }
            else -> Unit
        }
        entry.intervention?.let { free(it.error); free(it.description) }
    }

    /** Feature 041: bodies the host has no copy of yet, searched by their preview. */
    var byPreview = 0
        private set

    /** Bodies stored cut (searched as far as stored). */
    var truncated = 0
        private set

    /** The hits in [entry] (at most [PER_ENTRY]), or null when some term is not in it. */
    fun hits(entry: NetworkEntry): List<SearchHit>? {
        if (terms.isEmpty()) return null
        val words = terms.any { !it.redacted }
        val places = if (words) places(entry) else emptyList()
        val texts = places.map { it to Fuzzy.Text(it.text) }
        val out = ArrayList<SearchHit>()
        for (term in terms) {
            var found = false
            if (term.redacted) {
                // Feature 072: the fingerprint as the answer shows it in a mask (`#…`), in the redacted rendering only.
                for (p in maskedPlaces(entry)) {
                    var i = p.shown.indexOf(term.text, ignoreCase = true)
                    while (i >= 0) {
                        found = true
                        if (out.size < PER_ENTRY) {
                            val s = Fuzzy.Span(i, i + term.text.length)
                            out += SearchHit(entry.id, p.place, term.text, s.start, s.end, Fuzzy.excerpt(p.shown, s), p.part, p.header, p.inName, false)
                        }
                        i = p.shown.indexOf(term.text, i + term.text.length, ignoreCase = true)
                    }
                }
                if (!found) return null
                continue
            }
            for ((p, t) in texts) {
                val spans = if (p.binary) binarySpans(p.text, term) else t.find(term.text, term.typos)
                if (spans.isEmpty()) continue
                found = true
                spans.forEach { s ->
                    if (out.size < PER_ENTRY) {
                        val excerpt = Fuzzy.excerpt(p.text, s).let { e -> if (p.binary) e.copy(text = e.text.map { c -> if (c.code in 32..126 || c == '…') c else '.' }.joinToString("")) else e }
                        out += SearchHit(entry.id, p.place, term.text, s.start, s.end, excerpt, p.part, p.header, p.inName, p.binary)
                    }
                }
            }
            if (!found) return null
        }
        return out
    }

    /** [text] is what words are searched in (a mask reads as [Redactor.plain]); [shown] the text the answer shows (a mask with its fingerprint), searched by `redacted:#fp`. */
    private class Place(val place: SearchPlace, val text: String, val part: BodyPart? = null, val header: String? = null, val inName: Boolean = false, val binary: Boolean = false, val shown: String = text)

    private fun places(entry: NetworkEntry): List<Place> = buildList {
        fun url(u: String?) { u?.let { add(if (redact) Redactor.url(it, "").first.let { shown -> Place(SearchPlace.URL, Redactor.plain(shown), shown = shown) } else Place(SearchPlace.URL, it)) } }
        fun headers(list: List<Header>?, place: SearchPlace) {
            val h = list?.let { if (redact) Redactor.headers(it, "").first else it } ?: return
            h.forEach { add(Place(place, it.name, header = it.name, inName = true)); add(Place(place, if (redact) Redactor.plain(it.value) else it.value, header = it.name, shown = it.value)) }
        }
        fun body(info: BodyInfo?, part: BodyPart, place: SearchPlace) { info?.let { bodyPlace(entry, it, part, place)?.let(::add) } }
        when (entry) {
            is HttpEntry -> {
                url(entry.url)
                headers(entry.requestHeaders, SearchPlace.REQUEST_HEADER)
                headers(entry.responseHeaders, SearchPlace.RESPONSE_HEADER)
                body(entry.requestBody, BodyPart.REQUEST, SearchPlace.REQUEST_BODY)
                body(entry.responseBody, BodyPart.RESPONSE, SearchPlace.RESPONSE_BODY)
                entry.appRequest?.let { headers(it.headers, SearchPlace.REQUEST_HEADER); body(it.body, BodyPart.APP_REQUEST, SearchPlace.REQUEST_BODY) }
                entry.appResponse?.let { headers(it.headers, SearchPlace.RESPONSE_HEADER); body(it.body, BodyPart.APP_RESPONSE, SearchPlace.RESPONSE_BODY) }
            }
            is WsConnectionEntry -> { url(entry.url); headers(entry.handshakeHeaders, SearchPlace.REQUEST_HEADER) }
            is WsMessageEntry -> {
                body(entry.payload, BodyPart.MESSAGE, SearchPlace.PAYLOAD)
                body(entry.appPayload, BodyPart.APP_MESSAGE, SearchPlace.PAYLOAD)
                entry.envelope?.let { e -> listOfNotNull(e.kind, e.topic, e.id).joinToString(" ").takeIf { it.isNotBlank() }?.let { add(Place(SearchPlace.ENVELOPE, it)) } }
            }
            is LinkEntry -> url(entry.uri)
        }
    }

    private fun bodyPlace(entry: NetworkEntry, info: BodyInfo, part: BodyPart, place: SearchPlace): Place? {
        if (info.completeness == Completeness.TRUNCATED) truncated++
        if (info.binary) {
            decoded?.invoke(entry, part)?.let { json ->
                if (!redact) return Place(place, json, part)
                // Decoded protobuf is redacted as JSON (006 FR-016), and searched as it is shown.
                return Redactor.body(json, "application/json", "").text?.let { Place(place, Redactor.plain(it), part, shown = it) } ?: run { ownNotSearched++; null }
            }
            val body = bodyBytes(entry, part) ?: run { if (info.storedBytes > 0) ownNotSearched++; return null }
            if (!body.decoded) { ownNotSearched++; return null }
            if (redact) { ownNotSearched++; return null }
            return Place(place, String(body.bytes, Charsets.ISO_8859_1), part, binary = true)
        }
        val body = bodyBytes(entry, part) ?: return previewPlace(info, part, place)
        if (!body.decoded) { ownNotSearched++; return null }
        val text = String(body.bytes, Charsets.UTF_8)
        if (!redact) return Place(place, text, part)
        val r = Redactor.body(text, info.contentType, "")
        return r.text?.let { Place(place, Redactor.plain(it), part, shown = it) } ?: run { ownNotSearched++; null }
    }

    /** Feature 041: no host copy — the preview is what is known; nothing is skipped without a count. */
    private fun previewPlace(info: BodyInfo, part: BodyPart, place: SearchPlace): Place? {
        if (info.storedBytes <= 0 && info.preview == null) return null
        val preview = info.preview ?: run { ownNotSearched++; return null }
        byPreview++
        val shown = if (redact) Redactor.body(preview, info.contentType, "").text ?: run { ownNotSearched++; return null } else preview
        return Place(place, if (redact) Redactor.plain(shown) else shown, part, shown = shown)
    }

    /** Strings inside binary bytes: the term's UTF-8 bytes, case-insensitive for ASCII letters. */
    private fun binarySpans(latin1: String, term: Term): List<Fuzzy.Span> {
        val needle = String(term.text.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        val out = ArrayList<Fuzzy.Span>()
        var i = latin1.indexOf(needle, ignoreCase = true)
        while (i >= 0 && out.size < PER_ENTRY) { out += Fuzzy.Span(i, i + needle.length); i = latin1.indexOf(needle, i + needle.length, ignoreCase = true) }
        return out
    }

    companion object {
        const val PER_ENTRY = 20
    }
}
