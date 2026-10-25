package io.yamsergey.dta.protocol.network

/**
 * Evaluates a [NetworkFilter] against entries (FR-019..022): every field AND-combined,
 * the same rules for HTTP exchanges and WebSocket traffic. Pure; bodies and connection
 * URLs come through ports so it runs without a device.
 *
 * @param connectionUrl the URL of a WebSocket connection by id (a message matches `url`
 *   through its connection)
 * @param bodyText decoded text of a body or payload, null when not stored or not text
 */
class NetworkFilterEval(
    private val filter: NetworkFilter,
    private val liveRunId: String,
    private val connectionUrl: (String) -> String?,
    private val bodyText: (NetworkEntry, BodyPart) -> String?,
) {
    /** Binary bodies `bodyContains` skipped so far (stated in coverage.notSearched). */
    var binaryNotSearched = 0
        private set

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
            val hits = bodies(entry).filter { (info, part) -> searchable(info) && bodyText(entry, part)?.contains(needle) == true }
            if (hits.isEmpty()) return null
            locations += hits.map { (_, part) -> location(part) }.distinct()
        }
        return locations
    }

    private fun searchable(info: BodyInfo): Boolean {
        if (info.binary) binaryNotSearched++
        return !info.binary
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

    private fun location(part: BodyPart) = when (part) {
        BodyPart.REQUEST, BodyPart.APP_REQUEST -> MatchLocation.REQUEST_BODY
        BodyPart.RESPONSE, BodyPart.APP_RESPONSE -> MatchLocation.RESPONSE_BODY
        BodyPart.MESSAGE, BodyPart.APP_MESSAGE -> MatchLocation.PAYLOAD
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
