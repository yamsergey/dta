package io.yamsergey.dta.protocol.network

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
    private val bodyBytes: (NetworkEntry, BodyPart) -> BodyBytes?,
) {
    /** [decoded] is false when the Content-Encoding could not be undone ([bytes] are as sent). */
    class BodyBytes(val bytes: ByteArray, val decoded: Boolean)

    /** Binary bodies `bodyContains` could not search — encoding not undone (stated in coverage.notSearched). */
    var binaryNotSearched = 0
        private set

    private val needleBytes = filter.bodyContains?.toByteArray(Charsets.UTF_8)

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
        filter.status?.let { status -> if (entry !is HttpEntry || !statusMatches(entry, status)) return null }
        filter.url?.let { needle ->
            val url = when (entry) {
                is HttpEntry -> entry.url
                is WsConnectionEntry -> entry.url
                is WsMessageEntry -> connectionUrl(entry.connectionId)
            }
            if (url == null || !url.contains(needle, ignoreCase = true)) return null
            locations += MatchLocation.URL
        }
        filter.bodyContains?.let { needle ->
            val hits = bodies(entry).mapNotNull { (info, part) -> if (contains(entry, info, part, needle)) location(part, info.binary) else null }
            if (hits.isEmpty()) return null
            locations += hits.distinct()
        }
        return locations
    }

    private fun contains(entry: NetworkEntry, info: BodyInfo, part: BodyPart, needle: String): Boolean {
        val body = bodyBytes(entry, part) ?: return false
        if (!info.binary) return String(body.bytes, Charsets.UTF_8).contains(needle)
        if (!body.decoded) {
            binaryNotSearched++
            return false
        }
        return indexOf(body.bytes, needleBytes!!) >= 0
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
        is WsConnectionEntry -> emptyList()
    }

    private fun location(part: BodyPart, binary: Boolean) = when (part) {
        BodyPart.REQUEST, BodyPart.APP_REQUEST -> if (binary) MatchLocation.REQUEST_BODY_BINARY else MatchLocation.REQUEST_BODY
        BodyPart.RESPONSE, BodyPart.APP_RESPONSE -> if (binary) MatchLocation.RESPONSE_BODY_BINARY else MatchLocation.RESPONSE_BODY
        BodyPart.MESSAGE, BodyPart.APP_MESSAGE -> if (binary) MatchLocation.PAYLOAD_BINARY else MatchLocation.PAYLOAD
    }

    private fun statusMatches(entry: HttpEntry, status: String): Boolean = when {
        status == "failed" -> entry.state == HttpState.FAILED
        status == "pending" -> entry.state == HttpState.PENDING
        status.endsWith("xx") -> entry.status?.let { it / 100 == status[0].digitToInt() } == true
        else -> entry.status == status.toIntOrNull()
    }

    companion object {
        fun kindOf(entry: NetworkEntry): EntryKind = when (entry) {
            is HttpEntry -> EntryKind.HTTP
            is WsConnectionEntry -> EntryKind.WS_CONNECTION
            is WsMessageEntry -> EntryKind.WS_MESSAGE
        }
    }
}
