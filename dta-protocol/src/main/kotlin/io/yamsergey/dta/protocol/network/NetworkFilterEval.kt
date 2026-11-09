package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.DtaJson

/**
 * Evaluates a [NetworkFilter] against entries (FR-019..022): every field AND-combined,
 * the same rules for HTTP exchanges and WebSocket traffic. Pure; bodies and connection
 * URLs come through ports so it runs without a device.
 *
 * @param connectionUrl the URL of a WebSocket connection by id (a message matches `url`
 *   through its connection)
 * @param bodyBytes a stored body with its Content-Encoding undone where possible, null
 *   when not stored. Text bodies are searched as UTF-8 text, binary bodies as bytes for
 *   the needle's UTF-8 encoding (feature 006) — strings inside protobuf without a schema.
 */
class NetworkFilterEval(
    private val filter: NetworkFilter,
    private val liveRunId: String,
    private val connectionUrl: (String) -> String?,
    /** A binary body as its protobuf schema decodes it, as JSON; null when no schema decodes it (feature 011). */
    private val decoded: ((NetworkEntry, BodyPart) -> String?)? = null,
    /** Feature 051: a text message's envelope as its format reads it (whole text when known); null: none or binary. */
    private val envelope: ((WsMessageEntry) -> Envelope?)? = null,
    private val bodyBytes: (NetworkEntry, BodyPart) -> BodyBytes?,
) {
    /** [decoded] is false when the Content-Encoding could not be undone ([bytes] are as sent). */
    class BodyBytes(val bytes: ByteArray, val decoded: Boolean)

    /** Binary bodies `bodyContains` could not search — encoding not undone (stated in coverage.notSearched). */
    var binaryNotSearched = 0
        private set

    /** Binary bodies no schema decodes, searched as bytes only (stated when a decoder is given). */
    var binaryUndecoded = 0
        private set

    /** Decoded fields where the needle was found, by entry id. */
    val fieldPaths = LinkedHashMap<String, MutableList<String>>()

    private val needleBytes = HashMap<String, ByteArray>()

    /** Null when [entry] does not match; otherwise where it matched (empty when no location applies). */
    fun matches(entry: NetworkEntry): List<MatchLocation>? {
        val locations = mutableListOf<MatchLocation>()
        filter.kinds?.let { if (kindOf(entry) !in it) return null }
        filter.intervened?.let { wanted -> if ((entry.intervention != null) != wanted) return null }
        if (filter.runs == RunScope.CURRENT && entry.runId != liveRunId) return null
        filter.sinceMs?.let { if (entry.timeMs < it) return null }
        filter.untilMs?.let { if (entry.timeMs > it) return null }
        filter.method?.let { method ->
            if (entry !is HttpEntry || !entry.method.equals(method, ignoreCase = true)) return null
        }
        filter.status?.let { status ->
            val ok = when (entry) { is HttpEntry -> statusMatches(entry, status); is WsConnectionEntry -> socketStatus(entry, status); else -> false }
            if (!ok) return null
        }
        filter.url?.let { needle ->
            val url = urlOf(entry)
            if (url == null || !url.contains(needle, ignoreCase = true)) return null
            locations += MatchLocation.URL
        }
        filter.host?.let { wanted -> if (!hostMatches(urlOf(entry), wanted)) return null }
        filter.exclude?.let { ex -> if (ex.any { excluded(it).matches(entry) != null }) return null }
        filter.activity?.let { needle -> if (entry !is LinkEntry || !entry.activity.contains(needle, ignoreCase = true)) return null }
        filter.delivery?.let { wanted ->
            if (entry !is LinkEntry || DtaJson.encodeToJsonElement(LinkDelivery.serializer(), entry.delivery).let { (it as kotlinx.serialization.json.JsonPrimitive).content } != wanted) return null
        }
        filter.direction?.let { d -> if (entry !is WsMessageEntry || entry.direction != d) return null }
        filter.frame?.let { f -> if (entry !is WsMessageEntry || entry.opcode != f) return null }
        filter.connection?.let { c ->
            val of = when (entry) { is WsConnectionEntry -> entry.id; is WsMessageEntry -> entry.connectionId; else -> null }
            if (of != c) return null
        }
        if (filter.envelopeType != null || filter.topic != null) {
            val env = (entry as? WsMessageEntry)?.takeIf { it.opcode == MessageOpcode.TEXT }?.let { m -> envelope?.invoke(m) ?: m.envelope } ?: return null
            filter.envelopeType?.let { t -> if (env.kind?.contains(t, ignoreCase = true) != true) return null }
            filter.topic?.let { t -> if (env.topic?.contains(t, ignoreCase = true) != true) return null }
        }
        filter.headerName?.let { name ->
            val headers = when (entry) {
                is HttpEntry -> entry.requestHeaders.orEmpty() + entry.responseHeaders.orEmpty()
                is WsConnectionEntry -> entry.handshakeHeaders.orEmpty()
                else -> return null
            }
            val value = filter.headerValue
            if (headers.none { it.name.equals(name, ignoreCase = true) && (value == null || it.value.contains(value, ignoreCase = true)) }) return null
        }
        filter.mime?.let { m ->
            val types = bodies(entry).mapNotNull { it.first.contentType } +
                ((entry as? HttpEntry)?.let { h -> (h.requestHeaders.orEmpty() + h.responseHeaders.orEmpty()).filter { it.name.equals("content-type", true) }.map { it.value } } ?: emptyList())
            if (types.none { it.contains(m, ignoreCase = true) }) return null
        }
        if (filter.minDurationMs != null || filter.maxDurationMs != null) {
            val d = (entry as? HttpEntry)?.durationMs ?: return null
            if (filter.minDurationMs != null && d < filter.minDurationMs) return null
            if (filter.maxDurationMs != null && d > filter.maxDurationMs) return null
        }
        if (filter.minSize != null || filter.maxSize != null) {
            val own = when (entry) {
                is HttpEntry -> listOfNotNull(entry.requestBody, entry.responseBody)
                is WsMessageEntry -> listOf(entry.payload)
                else -> return null
            }
            val size = own.sumOf { it.declaredBytes ?: it.storedBytes }
            if (filter.minSize != null && size < filter.minSize) return null
            if (filter.maxSize != null && size > filter.maxSize) return null
        }
        filter.requestContains?.let { needle ->
            if (entry !is HttpEntry) return null
            val hits = bodyHits(entry, needle) { it == BodyPart.REQUEST || it == BodyPart.APP_REQUEST }
            if (hits.isEmpty()) return null
            locations += hits
        }
        filter.responseContains?.let { needle ->
            if (entry !is HttpEntry) return null
            val hits = bodyHits(entry, needle) { it == BodyPart.RESPONSE || it == BodyPart.APP_RESPONSE }
            if (hits.isEmpty()) return null
            locations += hits
        }
        filter.bodyContains?.let { needle ->
            val hits = bodyHits(entry, needle) { true }
            if (hits.isEmpty()) return null
            locations += hits
        }
        return locations.distinct()
    }

    private fun bodyHits(entry: NetworkEntry, needle: String, parts: (BodyPart) -> Boolean): List<MatchLocation> =
        bodies(entry).filter { parts(it.second) }.mapNotNull { (info, part) ->
            decodedHit(entry, info, part, needle)?.let { return@mapNotNull it }
            if (contains(entry, info, part, needle)) location(part, info.binary) else null
        }.distinct()

    private fun urlOf(entry: NetworkEntry): String? = when (entry) {
        is HttpEntry -> entry.url
        is WsConnectionEntry -> entry.url
        is WsMessageEntry -> entry.url ?: connectionUrl(entry.connectionId)
        is LinkEntry -> entry.uri
    }

    /** Evaluators for the exclusions, made once; their not-searched counts add to this one's. */
    private val exclusions = HashMap<NetworkFilter, NetworkFilterEval>()

    private fun excluded(f: NetworkFilter): NetworkFilterEval = exclusions.getOrPut(f) {
        NetworkFilterEval(f, liveRunId, connectionUrl, decoded, envelope, bodyBytes)
    }

    /** Not searched by the exclusions' body searches. */
    val excludedNotSearched: Int get() = exclusions.values.sumOf { it.binaryNotSearched }

    /** The decoded location when a schema decodes the body and a field holds the needle. */
    private fun decodedHit(entry: NetworkEntry, info: BodyInfo, part: BodyPart, needle: String): MatchLocation? {
        val decode = decoded ?: return null
        if (!info.binary) return null
        val json = decode(entry, part)
        if (json == null) {
            binaryUndecoded++
            return null
        }
        val paths = DecodedFields.paths(json, needle, ignoreCase = true)
        if (paths.isEmpty()) return null
        fieldPaths.getOrPut(entry.id) { mutableListOf() } += paths
        return when (part) {
            BodyPart.REQUEST, BodyPart.APP_REQUEST -> MatchLocation.REQUEST_BODY_DECODED
            BodyPart.RESPONSE, BodyPart.APP_RESPONSE -> MatchLocation.RESPONSE_BODY_DECODED
            BodyPart.MESSAGE, BodyPart.APP_MESSAGE -> MatchLocation.PAYLOAD_DECODED
        }
    }

    private fun contains(entry: NetworkEntry, info: BodyInfo, part: BodyPart, needle: String): Boolean {
        val body = bodyBytes(entry, part) ?: return false
        if (!info.binary) return String(body.bytes, Charsets.UTF_8).contains(needle, ignoreCase = true)
        if (!body.decoded) {
            binaryNotSearched++
            return false
        }
        return indexOf(body.bytes, needleBytes.getOrPut(needle) { needle.toByteArray(Charsets.UTF_8) }) >= 0
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        val first = needle[0]
        val last = haystack.size - needle.size
        var i = 0
        while (i <= last) {
            if (haystack[i] == first) {
                var j = 1
                while (j < needle.size && haystack[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return -1
    }

    private fun bodies(entry: NetworkEntry): List<Pair<BodyInfo, BodyPart>> = when (entry) {
        is HttpEntry -> listOfNotNull(
            entry.requestBody?.let { it to BodyPart.REQUEST },
            entry.responseBody?.let { it to BodyPart.RESPONSE },
            entry.appRequest?.body?.let { it to BodyPart.APP_REQUEST },
            entry.appResponse?.body?.let { it to BodyPart.APP_RESPONSE },
        )
        is WsMessageEntry -> listOfNotNull(entry.payload to BodyPart.MESSAGE, entry.appPayload?.let { it to BodyPart.APP_MESSAGE })
        is WsConnectionEntry, is LinkEntry -> emptyList()
    }

    private fun location(part: BodyPart, binary: Boolean) = when (part) {
        BodyPart.REQUEST, BodyPart.APP_REQUEST -> if (binary) MatchLocation.REQUEST_BODY_BINARY else MatchLocation.REQUEST_BODY
        BodyPart.RESPONSE, BodyPart.APP_RESPONSE -> if (binary) MatchLocation.RESPONSE_BODY_BINARY else MatchLocation.RESPONSE_BODY
        BodyPart.MESSAGE, BodyPart.APP_MESSAGE -> if (binary) MatchLocation.PAYLOAD_BINARY else MatchLocation.PAYLOAD
    }

    private fun socketStatus(entry: WsConnectionEntry, status: String): Boolean = when (status) {
        "open" -> entry.state == WsState.OPEN
        "closed" -> entry.state == WsState.CLOSED
        "failed" -> entry.state == WsState.FAILED
        "connecting", "pending" -> entry.state == WsState.CONNECTING
        else -> status.length == 4 && entry.closeCode == status.toIntOrNull()
    }

    private fun statusMatches(entry: HttpEntry, status: String): Boolean = when {
        status == "failed" -> entry.state == HttpState.FAILED
        status == "pending" -> entry.state == HttpState.PENDING
        status.endsWith("xx") -> entry.status?.let { it / 100 == status[0].digitToInt() } == true
        else -> status.length == 3 && status.toIntOrNull()?.let { entry.status == it } == true
    }

    companion object {
        /** [wanted] is a host (`api.example.test`) or `*.example.test` (any subdomain); case-insensitive. */
        fun hostMatches(url: String?, wanted: String): Boolean {
            val host = url?.let { hostOf(it) } ?: return false
            val w = wanted.lowercase()
            return if (w.startsWith("*.")) host.endsWith(w.drop(1)) else host == w
        }

        /** The host of [url] without port or credentials, lower case. */
        fun hostOf(url: String): String? {
            val afterScheme = url.substringAfter("://", url)
            val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
            val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
            return host.lowercase().takeIf { it.isNotEmpty() }
        }

        fun kindOf(entry: NetworkEntry): EntryKind = when (entry) {
            is HttpEntry -> EntryKind.HTTP
            is WsConnectionEntry -> EntryKind.WS_CONNECTION
            is WsMessageEntry -> EntryKind.WS_MESSAGE
            is LinkEntry -> EntryKind.LINK
        }
    }
}
