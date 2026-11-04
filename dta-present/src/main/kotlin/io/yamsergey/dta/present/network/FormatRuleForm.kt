package io.yamsergey.dta.present.network

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Feature 039: the "Set format…" form of a connection — what the user typed, as the params of
 * `network.format.test` / `network.format.add`, and the test's result as the preview text.
 */
data class FormatRuleForm(
    val name: String = "custom",
    val urlPattern: String = "",
    /** any, out or in. */
    val direction: String = "any",
    val pattern: String = "",
    val kind: String = "",
    val idPath: String = "",
    val topic: String = "",
    val payload: String = "",
    /** json or text. */
    val payloadFormat: String = "json",
) {
    fun params(): Map<String, JsonElement> = buildMap {
        put("name", JsonPrimitive(name.trim().ifEmpty { "custom" }))
        put("urlPattern", JsonPrimitive(urlPattern.trim()))
        if (direction != "any") put("direction", JsonPrimitive(direction))
        listOf("pattern" to pattern, "kind" to kind, "idPath" to idPath, "topic" to topic, "payload" to payload)
            .forEach { (k, v) -> if (v.isNotBlank()) put(k, JsonPrimitive(if (k == "pattern") v else v.trim())) }
        if (payloadFormat != "json") put("payloadFormat", JsonPrimitive(payloadFormat))
    }

    /** Nothing to read yet: no test is worth running. */
    val empty: Boolean get() = pattern.isBlank() && listOf(kind, idPath, topic, payload).all { it.isBlank() }

    companion object {
        /**
         * A new rule for the socket at [url]: its address up to the query, any path after it
         * (`wss://host/live?token=…` → `wss://host/live*`), so the token does not have to match.
         */
        fun forConnection(url: String?): FormatRuleForm = FormatRuleForm(urlPattern = url?.substringBefore('?')?.let { "$it*" } ?: "")

        /** The form of an existing rule (network.format.list item). */
        fun of(rule: JsonObject): FormatRuleForm = FormatRuleForm(
            name = rule.str("name") ?: "custom", urlPattern = rule.str("urlPattern") ?: "", direction = rule.str("direction") ?: "any",
            pattern = rule.str("pattern") ?: "", kind = rule.str("kind") ?: "", idPath = rule.str("idPath") ?: "", topic = rule.str("topic") ?: "",
            payload = rule.str("payload") ?: "", payloadFormat = rule.str("payloadFormat") ?: "json",
        )

        /** `network.format.test`'s result as the form's preview. */
        fun preview(result: JsonObject): String = buildString {
            append("Reads ${result.long("read") ?: 0} of ${result.long("of") ?: 0} of the connection's latest text messages.")
            result.str("slow")?.let { append("\n▲ ").append(it) }
            val samples = result.arr("samples")
            if (samples.isNotEmpty()) append("\n\nRead:")
            samples.forEach { s ->
                val o = s as? JsonObject ?: return@forEach
                val e = envelopeOf(o.obj("envelope"))
                append("\n  ").append(e?.label()?.takeIf { it.isNotEmpty() }?.let { "[$it]  " } ?: "[nothing named]  ").append(oneLine(o.str("text") ?: "").take(160))
                e?.note?.let { append("   (").append(it).append(')') }
            }
            val unread = result.arr("unread")
            if (unread.isNotEmpty()) append("\n\nNot read:")
            unread.forEach { append("\n  ").append(oneLine((it as? JsonPrimitive)?.content ?: "").take(160)) }
        }

        /** The rules in use by a connection's messages, by id. */
        fun rulesInUse(messages: List<EntryRow>): List<String> = messages.mapNotNull { it.envelope?.rule }.distinct()
    }
}
