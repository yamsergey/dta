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
    var notSearched = 0
        private set

    /** Bodies stored cut (searched as far as stored). */
    var truncated = 0
        private set

    /** The hits in [entry] (at most [PER_ENTRY]), or null when some term is not in it. */
    fun hits(entry: NetworkEntry): List<SearchHit>? {
        if (terms.isEmpty()) return null
        val places = places(entry)
        val texts = places.map { it to Fuzzy.Text(it.text) }
        val out = ArrayList<SearchHit>()
        for (term in terms) {
            var found = false
            for ((p, t) in texts) {
                val spans = if (p.binary) binarySpans(p.text, term) else t.find(term.text)
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

    private class Place(val place: SearchPlace, val text: String, val part: BodyPart? = null, val header: String? = null, val inName: Boolean = false, val binary: Boolean = false)

    private fun places(entry: NetworkEntry): List<Place> = buildList {
        fun url(u: String?) { u?.let { add(Place(SearchPlace.URL, if (redact) Redactor.url(it, "").first else it)) } }
        fun headers(list: List<Header>?, place: SearchPlace) {
            val h = list?.let { if (redact) Redactor.headers(it, "").first else it } ?: return
            h.forEach { add(Place(place, it.name, header = it.name, inName = true)); add(Place(place, it.value, header = it.name)) }
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
            decoded?.invoke(entry, part)?.let { return Place(place, it, part) }
            val body = bodyBytes(entry, part) ?: return null
            if (!body.decoded) { notSearched++; return null }
            if (redact) { notSearched++; return null }
            return Place(place, String(body.bytes, Charsets.ISO_8859_1), part, binary = true)
        }
        val body = bodyBytes(entry, part) ?: return null
        if (!body.decoded) { notSearched++; return null }
        val text = String(body.bytes, Charsets.UTF_8)
        if (!redact) return Place(place, text, part)
        val r = Redactor.body(text, info.contentType, "")
        return r.text?.let { Place(place, it, part) } ?: run { notSearched++; null }
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
