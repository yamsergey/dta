package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@Serializable
enum class EntryKind {
    @SerialName("http") HTTP,
    @SerialName("ws-connection") WS_CONNECTION,
    @SerialName("ws-message") WS_MESSAGE,
    /** Feature 012: a link that reached an activity; only when asked for (not network traffic). */
    @SerialName("link") LINK,
}

@Serializable
enum class Order {
    @SerialName("newest-first") NEWEST_FIRST,
    @SerialName("oldest-first") OLDEST_FIRST,
}

@Serializable
enum class RunScope {
    @SerialName("current") CURRENT,
    @SerialName("all") ALL,
}

/** On-device filters (FR-019..021); every field optional, all AND-combined. */
@Serializable
data class NetworkFilter(
    /** Case-insensitive URL substring; a message matches by its connection's URL. */
    val url: String? = null,
    /** HTTP method, case-insensitive. */
    val method: String? = null,
    /** `200`, `4xx`, `5xx`, `failed`, `pending`. */
    val status: String? = null,
    val kinds: List<EntryKind>? = null,
    val sinceMs: Long? = null,
    val untilMs: Long? = null,
    /** Substring of decoded text bodies and payloads, case ignored (051); binary as bytes. */
    val bodyContains: String? = null,
    /** Default: all runs. */
    val runs: RunScope? = null,
    /** Feature 005: true = only entries the network script touched; false = only untouched. */
    val intervened: Boolean? = null,
    /** Feature 012, links: case-insensitive substring of the receiving activity's class. */
    val activity: String? = null,
    /** Feature 012, links: cold-start, created, new-intent or from-recents. */
    val delivery: String? = null,
    /** Feature 040: the URL's host, case-insensitive; `*.example.test` takes subdomains too. */
    val host: String? = null,
    /** Feature 040: entries matching any of these are left out (each a filter of its own). */
    val exclude: List<NetworkFilter>? = null,
    /** Feature 051: WebSocket messages sent ([Direction.OUT]) or received. */
    val direction: Direction? = null,
    /** Feature 051: WebSocket messages by frame type. */
    val frame: MessageOpcode? = null,
    /** Feature 051: text in a text message's type, as its format reads it (038/039); case ignored. */
    val envelopeType: String? = null,
    /** Feature 051: text in a text message's topic, as its format reads it; case ignored. */
    val topic: String? = null,
    /** Feature 051: a WebSocket connection by id, with its messages. */
    val connection: String? = null,
    /** Feature 051: a request, response or handshake header by name, case ignored... */
    val headerName: String? = null,
    /** ...whose value holds this text, case ignored. */
    val headerValue: String? = null,
    /** Feature 051: text in a body's content type, case ignored. */
    val mime: String? = null,
    /** Feature 051: an HTTP call's duration bounds, inclusive. */
    val minDurationMs: Long? = null,
    val maxDurationMs: Long? = null,
    /** Feature 051: body bytes (request + response, or the message), stored or declared; inclusive bounds. */
    val minSize: Long? = null,
    val maxSize: Long? = null,
    /** Feature 051: text in an HTTP request body only (the app-side one too); case ignored. */
    val requestContains: String? = null,
    /** Feature 051: text in an HTTP response body only; case ignored. */
    val responseContains: String? = null,
    /** Feature 053: a pattern found in some body (request, response or message); case ignored unless its flags say. */
    val bodyPattern: String? = null,
    /** Feature 053: a pattern found in the HTTP request body. */
    val requestPattern: String? = null,
    /** Feature 053: a pattern found in the HTTP response body. */
    val responsePattern: String? = null,
) {
    /** Feature 053: has a body pattern of its own or in an exclusion. */
    val hasPattern: Boolean get() = bodyPattern != null || requestPattern != null || responsePattern != null || exclude?.any { it.hasPattern } == true

    /** Feature 053: the same filter without body patterns (for an app that would read them as nothing). */
    fun withoutPatterns(): NetworkFilter = copy(
        bodyPattern = null, requestPattern = null, responsePattern = null,
        exclude = exclude?.map { it.withoutPatterns() }?.filter { it != NetworkFilter() }?.ifEmpty { null },
    )

    /** [other]'s fields where it has them, this one's elsewhere; exclusions add up. */
    fun overlay(other: NetworkFilter): NetworkFilter = NetworkFilter(
        url = other.url ?: url, method = other.method ?: method, status = other.status ?: status, kinds = other.kinds ?: kinds,
        sinceMs = other.sinceMs ?: sinceMs, untilMs = other.untilMs ?: untilMs, bodyContains = other.bodyContains ?: bodyContains,
        runs = other.runs ?: runs, intervened = other.intervened ?: intervened, activity = other.activity ?: activity,
        delivery = other.delivery ?: delivery, host = other.host ?: host,
        exclude = ((exclude ?: emptyList()) + (other.exclude ?: emptyList())).ifEmpty { null },
        direction = other.direction ?: direction, frame = other.frame ?: frame, envelopeType = other.envelopeType ?: envelopeType,
        topic = other.topic ?: topic, connection = other.connection ?: connection,
        headerName = other.headerName ?: headerName, headerValue = if (other.headerName != null) other.headerValue else headerValue,
        mime = other.mime ?: mime, minDurationMs = other.minDurationMs ?: minDurationMs, maxDurationMs = other.maxDurationMs ?: maxDurationMs,
        minSize = other.minSize ?: minSize, maxSize = other.maxSize ?: maxSize,
        requestContains = other.requestContains ?: requestContains, responseContains = other.responseContains ?: responseContains,
        bodyPattern = other.bodyPattern ?: bodyPattern, requestPattern = other.requestPattern ?: requestPattern,
        responsePattern = other.responsePattern ?: responsePattern,
    )
}

/** `network_list` params (contract §2). Shared so daemon and sidekick agree (constitution VI). */
@Serializable
data class NetworkQuery(
    val filter: NetworkFilter? = null,
    val limit: Int = DEFAULT_LIMIT,
    val order: Order = Order.NEWEST_FIRST,
    /** Cursor: the `next` of a previous page. */
    val before: String? = null,
    /** Applied by the daemon; the sidekick never redacts. */
    val redact: Boolean = false,
    /** Archived history to cover; null is the live history only (feature 011). */
    val archive: ArchiveScope? = null,
    /** Feature 040: the query line — filters and search words ([QueryLine]). */
    val q: String? = null,
    /** Feature 041: with search words, list every entry the filters keep (hits marked), not only those with hits. */
    val allRows: Boolean = false,
    /** Feature 043: do not apply the app's saved filters to this call (they stay saved). */
    val ignoreSavedFilters: Boolean = false,
) {
    /**
     * The filter of [filter] and the line's filters together (the line's win where both give a
     * field), and the line's search words. A line with problems is refused naming each one.
     */
    fun resolved(nowMs: Long = System.currentTimeMillis()): Pair<NetworkFilter?, List<Term>> {
        val line = q?.takeIf { it.isNotBlank() } ?: return filter to emptyList()
        val p = QueryLine.parse(line, nowMs)
        if (p.problems.isNotEmpty()) {
            throw InvalidParamsException("query: " + p.problems.joinToString("; ") { "${line.substring(it.start, it.end)} — ${it.message}" })
        }
        val merged = (filter ?: NetworkFilter()).overlay(p.filter.copy(exclude = p.exclude.ifEmpty { null }))
        return merged.takeIf { it != NetworkFilter() } to p.terms
    }

    fun toJson(): JsonObject = DtaJson.encodeToJsonElement(serializer(), this).jsonObject

    companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 1000
        private val STATUS = Regex("""[1-5]\d\d|[1-5]xx|failed|pending""")

        /** Null params are the defaults; malformed ones throw [InvalidParamsException]. */
        fun parse(params: JsonObject?): NetworkQuery {
            if (params == null) return NetworkQuery()
            val filter = params["filter"] as? JsonObject
            checkEnum(params["order"], Order.serializer(), "order")
            filter?.let {
                (it["kinds"] as? JsonArray)?.forEach { kind -> checkEnum(kind, EntryKind.serializer(), "kind") }
                checkEnum(it["runs"], RunScope.serializer(), "runs")
            }
            val query = try {
                DtaJson.decodeFromJsonElement(serializer(), params)
            } catch (e: SerializationException) {
                throw InvalidParamsException("malformed network_list params: ${e.message?.lineSequence()?.first()}")
            } catch (e: IllegalArgumentException) {
                throw InvalidParamsException("malformed network_list params: ${e.message?.lineSequence()?.first()}")
            }
            if (query.limit !in 1..MAX_LIMIT) {
                throw InvalidParamsException("limit must be within 1..$MAX_LIMIT, was ${query.limit}")
            }
            query.filter?.status?.let { status ->
                if (!STATUS.matches(status)) {
                    throw InvalidParamsException("unknown status '$status'; valid values: an exact code (200), a class (4xx), failed, pending")
                }
            }
            return query
        }

        private fun <E : Enum<E>> checkEnum(value: Any?, serializer: KSerializer<E>, what: String) {
            if (value !is JsonPrimitive || !value.isString) return
            val names = wireNames(serializer)
            if (value.content !in names) {
                throw InvalidParamsException("unknown $what '${value.content}'; valid values: ${names.joinToString(", ")}")
            }
        }

        private fun <E : Enum<E>> wireNames(serializer: KSerializer<E>): List<String> =
            (0 until serializer.descriptor.elementsCount).map { serializer.descriptor.getElementName(it) }
    }
}
