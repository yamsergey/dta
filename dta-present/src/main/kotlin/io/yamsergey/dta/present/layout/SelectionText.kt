package io.yamsergey.dta.present.layout

import io.yamsergey.dta.protocol.layout.LayoutSelection
import io.yamsergey.dta.protocol.layout.SelectedElement
import io.yamsergey.dta.protocol.layout.Selections
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Feature 055: the shared layout selection in words — the plugin's line under the tree, and the CLI. */
object SelectionText {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** The line under the tree; null when nothing is selected and nothing is to be said. */
    fun line(s: LayoutSelection, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (s.elements.isEmpty()) return s.note
        val n = s.elements.size + (s.omitted ?: 0)
        val who = when (s.changedBy) {
            null, "plugin" -> "$n selected · agents can read ${if (n == 1) "it" else "them"}"
            else -> "$n selected by ${if (s.changedBy == "mcp") "an agent (mcp)" else s.changedBy}" + (s.changedAt?.let { " at ${time(it, zone)}" } ?: "")
        }
        val gone = s.elements.filter { it.now == Selections.GONE }
        return who + if (gone.isEmpty()) "" else " · ${gone.size} no longer on screen: " + gone.joinToString(", ") { short(it) }
    }

    /** The CLI's answer. */
    fun text(s: LayoutSelection, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        if (s.elements.isEmpty()) {
            append("Nothing selected in the Layout tab" + (s.note?.let { " ($it)" } ?: "") + ".")
            return@buildString
        }
        append("${s.elements.size} selected" + (s.changedBy?.let { " by $it" } ?: "") + (s.changedAt?.let { " at ${time(it, zone)}" } ?: ""))
        s.omitted?.let { append(" · $it more left out") }
        s.elements.forEachIndexed { i, e ->
            val n = e.node
            append("\n  ${i + 1}. ${n.str("kind") ?: "node"} ${name(n)}")
            label(n)?.let { append(" \"$it\"") }
            n.str("id")?.let { append(" #$it") }
            n.str("sourceFile")?.let { f -> append(" · $f" + (n.str("lineNumber")?.let { ":$it" } ?: "")) }
            e.now?.let { append(" · ${it.replace('-', ' ')}") }
            append("\n     ")
            append(listOfNotNull(e.path.joinToString(" › ").ifEmpty { null }, bounds(n), "captured ${time(e.capturedAt, zone)}", e.screen).joinToString(" · "))
        }
    }

    private fun short(e: SelectedElement) = name(e.node) + (label(e.node)?.let { " \"$it\"" } ?: "")

    private fun name(n: JsonObject) = (n.str("name") ?: n.str("className")?.substringAfterLast('.') ?: "node")

    private fun label(n: JsonObject) = (n.str("text") ?: n.str("contentDescription"))?.take(40)

    private fun bounds(n: JsonObject): String? {
        val b = n["bounds"] as? JsonObject ?: return null
        fun v(k: String) = (b[k] as? JsonPrimitive)?.intOrNull ?: 0
        return "[${v("left")},${v("top")} ${v("right") - v("left")}×${v("bottom") - v("top")}]"
    }

    private fun time(ms: Long, zone: ZoneId) = TIME.format(Instant.ofEpochMilli(ms).atZone(zone))

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content
}
