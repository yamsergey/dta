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
    /** Case-sensitive substring of decoded text bodies and payloads; never binary. */
    val bodyContains: String? = null,
    /** Default: all runs. */
    val runs: RunScope? = null,
    /** Feature 005: true = only entries the network script touched; false = only untouched. */
    val intervened: Boolean? = null,
)

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
) {
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
