package io.yamsergey.dta.present.ui

import io.yamsergey.dta.present.Degradations
import io.yamsergey.dta.protocol.ui.LinkRelation
import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.UiSource
import io.yamsergey.dta.protocol.ui.WindowKind
import io.yamsergey.dta.protocol.ui.WindowRef
import java.util.Locale

/**
 * How a UI journal event reads (feature 015): one line for the CLI and watch, and the one-line
 * summary an agent gets at the end of every tool answer. Shared, so every surface says the same.
 */
object UiEventText {
    const val SUMMARY_MAX = 200
    private const val CONTENT_TEXTS = 6

    fun line(e: UiEvent): String = when (e.kind) {
        UiEventKind.TOAST -> "toast  \"${e.text.orEmpty()}\"" + duration(e.durationMs)
        UiEventKind.PROCESS_ENDED -> "process ended"
        UiEventKind.GAP -> "gap  " + listOfNotNull(e.gapCount?.let { "$it events dropped" }, explanation(e)).joinToString(": ")
        else -> if (e.window?.app == false) foreign(e) else windowLine(e)
    }

    /** "Since your last call: N UI events — …." or null when there were none. */
    fun summary(events: List<UiEvent>): String? {
        val items = items(events)
        if (items.isEmpty()) return null
        val head = "Since your last call: ${items.size} UI event${if (items.size == 1) "" else "s"} — "
        val shown = ArrayList<String>()
        for ((i, item) in items.withIndex()) {
            val rest = items.size - i - 1
            val tail = if (rest > 0) ", and $rest more." else "."
            val candidate = head + (shown + item).joinToString(", ") + tail
            if (candidate.length > SUMMARY_MAX && shown.isNotEmpty()) break
            shown += item
        }
        val left = items.size - shown.size
        return head + shown.joinToString(", ") + if (left > 0) ", and $left more." else "."
    }

    private fun items(events: List<UiEvent>): List<String> = events.mapNotNull { item(it, events) }

    /** One event's item in a summary of [events] (a window's life folds into its appearance), or null. */
    fun item(e: UiEvent, events: List<UiEvent>): String? {
        val closed = events.filter { it.kind == UiEventKind.WINDOW_DISAPPEARED }.associateBy { it.appearedId }
        return run {
            when (e.kind) {
                UiEventKind.WINDOW_APPEARED, UiEventKind.WINDOW_PRESENT_AT_START, UiEventKind.ELEMENT_APPEARED -> {
                    val what = if (e.window?.app == false) "window not the app's: ${foreignName(e.window)}" else name(e)
                    what + (closed[e.id]?.durationMs?.let { " (closed after ${seconds(it, 1)} s)" } ?: "")
                }
                UiEventKind.WINDOW_DISAPPEARED, UiEventKind.ELEMENT_DISAPPEARED ->
                    if (e.appearedId != null && events.any { it.id == e.appearedId }) null else name(e) + " closed"
                UiEventKind.TOAST -> "toast \"${e.text.orEmpty()}\""
                UiEventKind.PROCESS_ENDED -> "the app's process ended"
                UiEventKind.GAP -> "a gap" + (e.gapCount?.let { " ($it events lost)" } ?: "")
                UiEventKind.WINDOW_SETTLED -> null
            }
        }
    }

    private fun name(e: UiEvent): String {
        val kind = if (e.source == UiSource.ELEMENT) "element" else kindName(e.window?.kind)
        val title = e.window?.title?.takeIf { e.source != UiSource.ELEMENT } ?: e.text
        return kind + (title?.let { " \"$it\"" } ?: "")
    }

    private fun windowLine(e: UiEvent): String {
        val kind = if (e.source == UiSource.ELEMENT) "element" else kindName(e.window?.kind)
        val verb = when (e.kind) {
            UiEventKind.WINDOW_APPEARED, UiEventKind.ELEMENT_APPEARED -> "appeared"
            UiEventKind.WINDOW_SETTLED -> "settled"
            UiEventKind.WINDOW_DISAPPEARED, UiEventKind.ELEMENT_DISAPPEARED -> "closed"
            UiEventKind.WINDOW_PRESENT_AT_START -> "open at start"
            else -> e.kind.name.lowercase()
        }
        val title = (if (e.source == UiSource.ELEMENT) e.text else e.window?.title)?.let { "  \"$it\"" } ?: ""
        val texts = e.content?.elements.orEmpty().mapNotNull { it.text ?: it.description }
            .filter { it != e.window?.title }.take(CONTENT_TEXTS)
        val content = if (texts.isEmpty()) "" else "  · " + texts.joinToString(" · ")
        val after = if (e.kind == UiEventKind.WINDOW_DISAPPEARED || e.kind == UiEventKind.ELEMENT_DISAPPEARED) e.durationMs?.let { "  after ${seconds(it, 2)} s" } ?: "" else ""
        val links = e.links.joinToString("") { "  ⟂ ${it.kind} ${it.id} ${if (it.relation == LinkRelation.IN_FLIGHT) "in flight" else "just finished"}" }
        val notes = e.completeness.mapNotNull { it.reason.substringBefore(':').takeIf { c -> c == "ui-secure-window" } }.joinToString("") { "  (secure: texts not recorded)" }
        return "$kind $verb$title$content$after$links$notes"
    }

    private fun foreign(e: UiEvent): String {
        val verb = if (e.kind == UiEventKind.WINDOW_DISAPPEARED) "closed" else "appeared"
        return "not the app  ${foreignName(e.window)} $verb"
    }

    private fun foreignName(w: WindowRef?): String = listOfNotNull(w?.owner, w?.windowId).joinToString(": ")

    private fun kindName(kind: WindowKind?): String = when (kind) {
        WindowKind.BOTTOM_SHEET -> "bottom sheet"
        WindowKind.OTHER_APP -> "other app"
        null -> "window"
        else -> kind.name.lowercase()
    }

    private fun duration(ms: Long?): String = when (ms) {
        null -> ""
        2_000L -> " (short)"
        3_500L -> " (long)"
        else -> " (${seconds(ms, 1)} s)"
    }

    private fun explanation(e: UiEvent): String? = e.completeness.firstOrNull()?.reason?.let { r ->
        r.substringAfter(": ", "").ifEmpty { Degradations.explain(r.substringBefore(':')) }
    }

    private fun seconds(ms: Long, decimals: Int) = String.format(Locale.ROOT, "%.${decimals}f", ms / 1000.0)
}
