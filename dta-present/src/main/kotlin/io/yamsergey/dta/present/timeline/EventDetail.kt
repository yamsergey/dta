package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.present.Degradations

/** What one timeline event was, as label–value rows for the detail pane (feature 017 US2). */
data class EventDetail(
    val title: String,
    val rows: List<Row>,
    val notes: List<String> = emptyList(),
    /** The Network entry a network event can open. */
    val networkEntry: String? = null,
) {
    data class Row(val label: String, val value: String)

    companion object {
        fun of(e: TimelineEvent): EventDetail {
            val rows = ArrayList<Row>()
            fun row(label: String, value: Any?) {
                value?.toString()?.takeIf { it.isNotBlank() }?.let { rows += Row(label, it) }
            }
            var title = TimelineEventText.describe(e).substringBefore("  ")
            e.ui?.let { ui ->
                title = e.kind.substringAfter('/').replace("window-", ui.window?.kind?.let { k -> io.yamsergey.dta.protocol.ui.WindowKind.serializer().descriptor.getElementName(k.ordinal) + " " } ?: "window ")
                    .replace("-", " ")
                row("title", ui.window?.title)
                row("text", ui.text)
                row("content", ui.content?.elements?.mapNotNull { it.text ?: it.description }?.joinToString(" · "))
                row("owner", ui.window?.owner)
                row("lived", ui.durationMs?.let { "$it ms" }.takeIf { ui.kind.name.endsWith("DISAPPEARED") })
                if (ui.window?.app == false) row("window", "not the app's")
            }
            e.network?.let { n ->
                row("request", listOfNotNull(n.method, listOfNotNull(n.host, n.path).joinToString("")).joinToString(" "))
                row("status", n.status)
                row("duration", n.durationMs?.let { "$it ms" })
                row("error", n.error)
                row("entry", n.entryId)
            }
            e.nav?.let { n ->
                row("library", n.library)
                row("destination", n.destination)
                row("arguments", n.args.entries.joinToString(" ") { "${it.key}=${it.value}" })
                row("depth", n.depth)
            }
            e.app?.let { a -> a.attrs.forEach { (k, v) -> row(k, v) } }
            e.input?.let { i ->
                row("element", i.element?.key)
                row("text", i.text)
                row("window", i.windowId)
                row("via", i.via)
            }
            e.gapCount?.let { row("lost", "$it events") }
            e.facets.forEach { c -> row(c.name, "${c.previous ?: "—"} → ${c.value ?: "—"}") }
            row("event", e.id)
            val notes = e.completeness.map { m ->
                m.reason.substringAfter(": ", "").ifEmpty { Degradations.explain(m.reason.substringBefore(":")) ?: m.reason }
            }
            return EventDetail(title, rows, notes, e.network?.entryId)
        }
    }
}
