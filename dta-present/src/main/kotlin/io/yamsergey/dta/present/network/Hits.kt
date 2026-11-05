package io.yamsergey.dta.present.network

import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.SearchHit
import io.yamsergey.dta.protocol.network.SearchPlace
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.serialization.json.JsonElement

/** Feature 040: search hits as people read them, the same in the plugin and the CLI. */
object Hits {
    fun of(json: JsonElement?): List<SearchHit> =
        (json as? kotlinx.serialization.json.JsonArray)?.mapNotNull { runCatching { DtaJson.decodeFromJsonElement(SearchHit.serializer(), it) }.getOrNull() }.orEmpty()

    /** "response body", "request header X-Order-Id", "app-side request body", "payload (in binary)". */
    fun place(h: SearchHit): String {
        val base = when (h.place) {
            SearchPlace.URL -> "URL"
            SearchPlace.REQUEST_HEADER -> "request header ${h.header}" + if (h.inName) " (name)" else ""
            SearchPlace.RESPONSE_HEADER -> "response header ${h.header}" + if (h.inName) " (name)" else ""
            SearchPlace.REQUEST_BODY -> "request body"
            SearchPlace.RESPONSE_BODY -> "response body"
            SearchPlace.PAYLOAD -> "payload"
            SearchPlace.ENVELOPE -> "message format"
        }
        val side = if (h.part == BodyPart.APP_REQUEST || h.part == BodyPart.APP_RESPONSE || h.part == BodyPart.APP_MESSAGE) "app-side " else ""
        return side + base + if (h.binary) " (in binary)" else ""
    }

    /** One line per place (the first hit there), at most [max]. */
    fun lines(hits: List<SearchHit>, max: Int = 3, mark: (String) -> String): List<String> {
        val byPlace = hits.distinctBy { Triple(it.place, it.part, it.header) }
        val out = byPlace.take(max).map { "${place(it)}: ${it.excerpt.render(mark)}" }
        return if (byPlace.size > max) out + "… and ${byPlace.size - max} more places" else out
    }
}
