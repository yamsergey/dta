package io.yamsergey.dta.present.links

import io.yamsergey.dta.present.network.arr
import io.yamsergey.dta.present.network.long
import io.yamsergey.dta.present.network.o
import io.yamsergey.dta.present.network.obj
import io.yamsergey.dta.present.network.str
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One link in the list: when, the URI, how it arrived, which activity. */
data class LinkRow(val id: String, val time: String, val uri: String, val delivery: String, val activity: String)

/** The Links view (feature 012): links that reached the app, one link in full, App Links, an opened link. */
object LinksView {
    const val EMPTY = "No links have reached the app yet. Open one with Open Link…, or from another app."

    fun rows(page: JsonObject, zone: ZoneId = ZoneId.systemDefault()): List<LinkRow> {
        val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone)
        return page.arr("entries").map { it.o }.map { l ->
            LinkRow(
                l.str("id").orEmpty(), time.format(Instant.ofEpochMilli(l.long("timeMs") ?: 0)), l.str("uri").orEmpty(),
                l.str("delivery").orEmpty().replace('-', ' '), l.str("activity").orEmpty().substringAfterLast('.'),
            )
        }
    }

    /** Label → value lines; extras that could not be read say why. */
    fun detail(l: JsonObject): List<Pair<String, String>> = buildList {
        add("URI" to l.str("uri").orEmpty())
        add("Delivery" to l.str("delivery").orEmpty().replace('-', ' '))
        add("Activity" to l.str("activity").orEmpty())
        add("Referrer" to (l.str("referrer") ?: "unknown"))
        l.str("action")?.let { add("Action" to it) }
        l.arr("categories").takeIf { it.isNotEmpty() }?.let { c -> add("Categories" to c.joinToString(", ") { it.toString().trim('"') }) }
        l.str("flags")?.let { add("Flags" to it) }
        l.arr("query").forEach { q -> add((q.o.str("name") ?: "?") to (q.o.str("value") ?: "")) }
        l.arr("extras").forEach { e ->
            val x = e.o
            add((x.str("key") ?: "?") to (x.str("notRead")?.let { "not read: $it" } ?: (x.str("value") ?: "")))
        }
    }

    fun opened(r: JsonObject): String {
        val main = when (r.str("outcome")) {
            "received" -> "Received by ${r.str("activity") ?: r.str("packageName")}" + (r.str("recordId")?.let { " (link $it)" } ?: "") + "."
            "other-app" -> "Opened by ${r.str("packageName")}."
            "chooser" -> "The system showed a chooser."
            else -> "Nothing on the device can open it."
        }
        val why = r.obj("appLinks")?.let { h -> " ${h.str("host")}: ${h.str("state")}" + (h.str("reason")?.let { " — $it" } ?: "") + "." }
            ?: r.str("reason")?.let { " $it." }
        return main + why.orEmpty()
    }

    fun verification(s: JsonObject): List<String> {
        if (s["available"]?.toString() != "true") return listOf(s.str("reason") ?: "unavailable")
        val hosts = s.arr("hosts")
        if (hosts.isEmpty()) return listOf(s.str("reason") ?: "No App Links hosts.")
        return hosts.map { h ->
            val x = h.o
            "${x.str("host")}: ${x.str("state")}" + (x.str("reason")?.let { " — $it" } ?: "") + if (x["enabled"]?.toString() == "false") " · turned off by the user" else ""
        }
    }
}
