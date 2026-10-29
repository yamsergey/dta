package io.yamsergey.dta.present.network

import io.yamsergey.dta.protocol.ops.Catalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The view's kind switch: one `kinds` filter value each. */
enum class KindChoice(val label: String, val kinds: List<String>) {
    ALL("All", emptyList()),
    HTTP("HTTP", listOf("http")),
    WEBSOCKET("WebSocket", listOf("ws-connection", "ws-message")),
}

/** A filter shown as a removable chip. */
data class Chip(val name: String, val label: String)

/**
 * The network view's filters (FR-007, patterns.md): exactly the `network.list` parameters, so a
 * filter change is a new query and nothing is filtered locally (FR-005). Chips label each one.
 */
data class NetworkFilters(
    private val chosen: Map<String, JsonElement> = emptyMap(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    fun with(name: String, value: JsonElement): NetworkFilters {
        require(name in NAMES) { "'$name' is not a network.list filter" }
        return copy(chosen = chosen + (name to value))
    }

    fun without(name: String) = copy(chosen = chosen - name)

    fun value(name: String): JsonElement? = chosen[name]

    val isEmpty: Boolean get() = chosen.isEmpty()

    /** The switch position for the `kinds` filter; null when the filter is one the switch cannot express. */
    val kind: KindChoice?
        get() {
            val kinds = (chosen["kinds"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: return KindChoice.ALL
            return KindChoice.entries.firstOrNull { it != KindChoice.ALL && it.kinds.toSet() == kinds.toSet() }
        }

    fun withKind(choice: KindChoice): NetworkFilters =
        if (choice == KindChoice.ALL) without("kinds") else with("kinds", JsonArray(choice.kinds.map { JsonPrimitive(it) }))

    /** Chips for every filter but a `kinds` one the switch already shows. */
    val chips: List<Chip>
        get() = NAMES.mapNotNull { name ->
            chosen[name]?.takeUnless { name == "kinds" && kind != null }?.let { Chip(name, label(name, it)) }
        }

    /** The `network.list` params, in catalog order as the CLI builds them. */
    fun params(limit: Int? = null, before: String? = null): JsonObject = JsonObject(buildMap {
        NAMES.forEach { name -> chosen[name]?.let { put(name, it) } }
        limit?.let { put("limit", JsonPrimitive(it)) }
        before?.let { put("before", JsonPrimitive(it)) }
    })

    fun caption(shown: Int, total: Int): String {
        val base = "$shown of $total shown"
        return when (chosen.size) {
            0 -> base
            1 -> "$base · 1 filter"
            else -> "$base · ${chosen.size} filters, combined with AND"
        }
    }

    private fun label(name: String, value: JsonElement): String {
        val text = (value as? JsonPrimitive)?.content ?: (value as? JsonArray)?.joinToString(", ") { (it as JsonPrimitive).content } ?: value.toString()
        val time = { (value as? JsonPrimitive)?.longOrNull?.let { TIME.withZone(zone).format(Instant.ofEpochMilli(it)) } ?: text }
        return when (name) {
            "url" -> "URL contains $text"
            "method" -> "Method $text"
            "status" -> "Status $text"
            "kinds" -> "Kinds $text"
            "intervened" -> if ((value as? JsonPrimitive)?.booleanOrNull == true) "Scripted only" else "Untouched only"
            "sinceMs" -> "Since ${time()}"
            "untilMs" -> "Until ${time()}"
            "bodyContains" -> "Body contains $text"
            "runs" -> if (text == "current") "Current run only" else "All runs"
            else -> "$name $text"
        }
    }

    companion object {
        private val PAGING = setOf("limit", "order", "before", "redact")

        /** The filter parameters of `network.list`, in catalog order. */
        val NAMES: List<String> = Catalog["network.list"].params.map { it.name }.filter { it !in PAGING }

        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
