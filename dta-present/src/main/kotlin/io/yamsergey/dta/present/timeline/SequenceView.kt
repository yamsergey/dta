package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.protocol.timeline.SequenceLane
import io.yamsergey.dta.protocol.timeline.SequenceLanes
import io.yamsergey.dta.protocol.timeline.SequencePage
import io.yamsergey.dta.protocol.timeline.SequenceStep
import java.util.Locale

/** What a row says, for the plugin and the CLI alike (feature 048). */
object SequenceText {
    /** `0:06.7` from the session's start. */
    fun clock(ms: Long, start: Long): String {
        val t = (ms - start).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d.%d", t / 60_000, t / 1_000 % 60, t / 100 % 10)
    }

    /** A row's time, finer: `6.70`; past 1000 s the clock. */
    fun seconds(ms: Long, start: Long): String {
        val t = (ms - start).coerceAtLeast(0)
        return if (t >= 1_000_000) clock(ms, start) else String.format(Locale.ROOT, "%.2f", t / 1_000.0)
    }

    /** `⤷` says it has a recorded cause. */
    fun label(s: SequenceStep): String = if (s.linked) "⤷ " + s.label.removePrefix("→ ") else s.label

    /** What a hover says: the label and the longer text, without repeating. */
    fun hover(s: SequenceStep): String = listOfNotNull(label(s), s.detail?.takeIf { it != s.label }).joinToString("\n")

    /** What is on the page and what is not. */
    fun counts(page: SequencePage): String = listOfNotNull(
        if (page.matched < page.total) "${page.matched} of ${page.total} rows match" else "${page.total} rows",
        page.steps.size.takeIf { it < page.matched }?.let { "the newest $it here" },
        page.beforeStart.takeIf { it > 0 }?.let { "$it before the start left out" },
        page.hits.takeIf { it > 0 }?.let { "$it hits" },
        page.socketsNote,
    ).joinToString(" · ")

    fun lines(page: SequencePage): List<String> {
        if (page.total == 0 && page.beforeStart == 0) return listOf("Nothing recorded yet.")
        val lanes = page.lanes.associate { it.id to it.title }
        return listOf(counts(page)) + page.steps.map { s ->
            val where = lanes[s.from].orEmpty().ifEmpty { s.from } + (s.to?.let { " → " + (lanes[it] ?: it) } ?: "")
            val marks = listOfNotNull("failed".takeIf { s.failed }, "hit".takeIf { s.hit }).joinToString(", ").ifEmpty { null }
            "${seconds(s.wallMs, page.startWallMs)}  $where  ${label(s)}" + (marks?.let { "  ($it)" } ?: "")
        }
    }
}

enum class SequenceAction(val label: String) { RESUME("Resume"), SHOW_ALL("Show all") }

/** A lane title's room across the diagram: from the midpoint with its left neighbour to the one with its right. */
data class LaneRoom(val id: String, val from: Int, val to: Int)

object LaneTitles {
    /** Rooms touch and never overlap, at any width; a lane without room gets an empty one. */
    fun of(ids: List<String>, width: Int, x: (String) -> Int): List<LaneRoom> = ids.mapIndexed { i, id ->
        val here = x(id)
        val from = if (i == 0) 0 else (x(ids[i - 1]) + here) / 2
        val to = if (i == ids.lastIndex) width else (here + x(ids[i + 1])) / 2
        LaneRoom(id, from, maxOf(from, to))
    }
}

/** The Sequence view (feature 048): one flat diagram; nothing is grouped. */
data class SequenceView(
    val page: SequencePage? = null,
    /** What the page was read for. */
    val shownQuery: String = "",
    /** The line as typed. */
    val query: String = "",
    /** Pause: new pages only count as [incoming]; recording goes on. */
    val paused: Boolean = false,
    val incoming: SequencePage? = null,
    val following: Boolean = true,
    /** Clear: rows from this device time; null is everything kept. */
    val startMs: Long? = null,
    val hitIndex: Int = -1,
) {
    /** A page read for [forQuery]. While paused, one for the shown query waits; a new query is shown at once. */
    fun receive(next: SequencePage, forQuery: String): SequenceView =
        if (paused && forQuery == shownQuery && page != null) copy(incoming = next)
        else copy(page = next, incoming = null, shownQuery = forQuery, hitIndex = hitIndex.coerceAtMost(next.hits - 1))

    fun withQuery(text: String) = copy(query = text, hitIndex = -1)

    fun pause(on: Boolean) = if (on) copy(paused = true, following = false) else copy(paused = false, page = incoming ?: page, incoming = null, following = true)

    /** The view starts after the newest row anyone has seen; nothing is deleted. */
    fun clear(): SequenceView {
        val newest = (incoming ?: page)?.steps?.lastOrNull()?.wallMs ?: return this
        return copy(startMs = newest + 1, paused = false, incoming = null, following = true, hitIndex = -1)
    }

    fun showAll() = copy(startMs = null, hitIndex = -1)

    /** Rows holding every search word, by their place among [steps]. */
    val hitRows: List<Int> get() = steps.indices.filter { steps[it].hit }

    fun nextHit() = hitRows.size.let { n -> if (n == 0) this else copy(hitIndex = (hitIndex + 1).mod(n), following = false) }

    fun previousHit() = hitRows.size.let { n -> if (n == 0) this else copy(hitIndex = (if (hitIndex < 0) n - 1 else (hitIndex - 1).mod(n)), following = false) }

    /** The row the hit keys have reached. */
    val currentRow: Int get() = hitRows.getOrNull(hitIndex) ?: -1

    /** `2 / 4`, `– / 4`; null without search words. */
    fun hitLabel(hasSearch: Boolean): String? = if (!hasSearch) null else "${if (hitIndex >= 0) hitIndex + 1 else "–"} / ${hitRows.size}"

    val waiting: Int get() = incoming?.let { (it.total - (page?.total ?: 0)).coerceAtLeast(0) } ?: 0

    /** What the list shows, under it. */
    val caption: String
        get() {
            val p = page ?: return ""
            val shown = if (p.matched < p.total) "${p.matched} of ${p.total} rows shown · ${p.total - p.matched} hidden by the filter" else rows(p.total)
            return listOfNotNull(
                "Paused".takeIf { paused },
                shown,
                "the newest ${p.steps.size} are drawn".takeIf { p.steps.size < p.matched },
                "$waiting new waiting".takeIf { paused },
                startMs?.let { "from ${SequenceText.clock(it, p.startWallMs)}, ${p.beforeStart} before it are not shown" },
            ).joinToString(" · ")
        }

    private fun rows(n: Int) = if (n == 1) "1 row" else "$n rows"

    /** What the link beside the caption does. */
    val action: SequenceAction?
        get() = when {
            paused -> SequenceAction.RESUME
            startMs != null -> SequenceAction.SHOW_ALL
            else -> null
        }

    val lanes: List<SequenceLane> get() = page?.lanes.orEmpty()

    val steps: List<SequenceStep> get() = page?.steps.orEmpty()

    fun time(s: SequenceStep): String = SequenceText.seconds(s.wallMs, page?.startWallMs ?: 0)

    /** Each lane's place across the diagram, 0..1 of the width after the time column. */
    fun positions(): Map<String, Double> {
        val ids = lanes.map { it.id }
        val out = LinkedHashMap<String, Double>()
        if (SequenceLanes.USER in ids) out[SequenceLanes.USER] = 0.06
        if (SequenceLanes.APP in ids) out[SequenceLanes.APP] = 0.32
        val rest = ids.filter { it != SequenceLanes.USER && it != SequenceLanes.APP }
        when (rest.size) {
            0 -> Unit
            1 -> out[rest[0]] = 0.70
            else -> {
                val from = 0.70 - (rest.size - 2) * 0.06
                rest.forEachIndexed { i, id -> out[id] = from + (0.94 - from) * i / (rest.size - 1) }
            }
        }
        return out
    }
}
