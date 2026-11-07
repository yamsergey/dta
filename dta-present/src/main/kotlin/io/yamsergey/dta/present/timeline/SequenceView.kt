package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.protocol.timeline.SequenceFlow
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

    /** What a hover says: the label and the longer text, without repeating; a flow holds a row by its time only. */
    fun hover(s: SequenceStep, flowNames: Map<String, String> = emptyMap()): String = (
        listOfNotNull(label(s), s.detail?.takeIf { it != s.label }) +
            s.flows.mapNotNull { flowNames[it] }.map { "In $it by time, not by a proved cause." }
        ).joinToString("\n")

    fun rows(n: Int) = if (n == 1) "1 row" else "$n rows"

    /** A flow's name on its line; `●` says it is recording. */
    fun flowTitle(f: SequenceFlow): String = (if (f.flow.recording) "● " else "") + f.flow.name

    /** What stands after the name on a flow's line: ` · 0:17.8 to 0:18.1 · 4 rows · manual`. */
    fun flowMeta(f: SequenceFlow, pageStart: Long, viewStart: Long? = null): String {
        val cut = f.shown < f.rows
        val parts = listOfNotNull(
            if (f.flow.recording) "recording since ${clock(f.flow.startMs, pageStart)}" else "${clock(f.flow.startMs, pageStart)} to ${clock(f.flow.endMs!!, pageStart)}",
            when {
                cut -> "${f.shown} of ${rows(f.rows)}"
                f.flow.recording -> null
                else -> rows(f.rows)
            },
            "started before this view".takeIf { viewStart != null && f.flow.startMs < viewStart },
            "older rows no longer kept".takeIf { f.rowsGone },
            f.flow.source,
        )
        return parts.joinToString("") { " · $it" }
    }

    /** What is on the page and what is not. */
    fun counts(page: SequencePage): String = listOfNotNull(
        if (page.matched < page.total) "${page.matched} of ${page.total} rows match" else rows(page.total),
        page.steps.size.takeIf { it < page.matched }?.let { "the newest $it here" },
        page.beforeStart.takeIf { it > 0 }?.let { "$it before the start left out" },
        page.hits.takeIf { it > 0 }?.let { "$it hits" },
        page.socketsNote,
        page.flowsNote,
    ).joinToString(" · ")

    fun lines(page: SequencePage): List<String> {
        val flows = page.flows.map { "flow ${it.flow.id} ${it.flow.name}" + flowMeta(it, page.startWallMs) }
        val names = page.flows.associate { it.flow.id to it.flow.name }
        if (page.total == 0 && page.beforeStart == 0) return listOf("Nothing recorded yet.") + flows
        val lanes = page.lanes.associate { it.id to it.title }
        return listOf(counts(page)) + flows + page.steps.map { s ->
            val where = lanes[s.from].orEmpty().ifEmpty { s.from } + (s.to?.let { " → " + (lanes[it] ?: it) } ?: "")
            val inFlows = if (page.onlyFlow != null) emptyList() else s.flows.mapNotNull { names[it] }.map { "in $it" }
            val marks = (listOfNotNull("failed".takeIf { s.failed }, "hit".takeIf { s.hit }) + inFlows).joinToString(", ").ifEmpty { null }
            "${seconds(s.wallMs, page.startWallMs)}  $where  ${label(s)}" + (marks?.let { "  ($it)" } ?: "")
        }
    }
}

enum class SequenceAction(val label: String) { RESUME("Resume"), SHOW_ALL("Show all"), END_FLOW("End flow") }

/** Tells the user's scrolling from a list growing: a scroll bar reports both alike. */
class ScrollWatch {
    private var last = Int.MIN_VALUE

    fun userMoved(value: Int, adjusting: Boolean, programmatic: Boolean): Boolean {
        if (adjusting) return false
        val moved = value != last
        last = value
        return moved && !programmatic
    }
}

/** What the Sequence view draws, top to bottom: rows, and a line above a flow's first (feature 049). */
sealed interface SequenceItem {
    data class Line(val flow: SequenceFlow) : SequenceItem

    /** [index] is the row's place among the page's steps. */
    data class Row(val step: SequenceStep, val index: Int, val flow: SequenceFlow?) : SequenceItem
}

/** An entry of the Flows list. */
data class FlowMenuItem(val id: String, val name: String, val meta: String, val enabled: Boolean)

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

/** The Sequence view (feature 048): one flat diagram; nothing is grouped, flows only highlight (feature 049). */
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
    /** The flow the view should go to once its line is there. */
    val flowTarget: String? = null,
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
                recording?.let { "recording ${it.flow.name}" } ?: flows.size.takeIf { it > 0 }?.let { if (it == 1) "1 flow" else "$it flows" },
            ).joinToString(" · ")
        }

    private fun rows(n: Int) = SequenceText.rows(n)

    /** What the link beside the caption does. */
    val action: SequenceAction?
        get() = when {
            paused -> SequenceAction.RESUME
            startMs != null -> SequenceAction.SHOW_ALL
            recording != null -> SequenceAction.END_FLOW
            else -> null
        }

    /** The newest known, also while paused: a flow can be started and ended then. */
    val flows: List<SequenceFlow> get() = (incoming ?: page)?.flows.orEmpty()

    val recording: SequenceFlow? get() = flows.firstOrNull { it.flow.recording }

    val flowNames: Map<String, String> get() = page?.flows.orEmpty().associate { it.flow.id to it.flow.name }

    /** Rows in place, each flow's line above its first; a flow with nothing to show here has none. */
    val items: List<SequenceItem> by lazy(LazyThreadSafetyMode.NONE) { place() }

    private fun place(): List<SequenceItem> {
        val p = page ?: return emptyList()
        val filtered = p.matched < p.total
        val byId = p.flows.associateBy { it.flow.id }
        val lineAt = HashMap<Int, MutableList<SequenceFlow>>()
        for (f in p.flows) {
            val at = if (f.shown > 0) {
                p.steps.indexOfFirst { f.flow.id in it.flows }
            } else when {
                filtered -> -1
                f.flow.recording || startMs == null || f.flow.startMs >= startMs -> p.steps.indexOfFirst { it.wallMs >= f.flow.startMs }.let { if (it < 0) p.steps.size else it }
                else -> -1
            }
            if (at >= 0) lineAt.getOrPut(at) { ArrayList() } += f
        }
        val out = ArrayList<SequenceItem>(p.steps.size + lineAt.size)
        for (i in 0..p.steps.size) {
            lineAt[i]?.forEach { out += SequenceItem.Line(it) }
            val s = p.steps.getOrNull(i) ?: continue
            out += SequenceItem.Row(s, i, s.flows.firstNotNullOfOrNull { byId[it] })
        }
        return out
    }

    /** Where a row of [steps] stands among [items]. */
    fun itemOfStep(step: Int): Int = items.indexOfFirst { it is SequenceItem.Row && it.index == step }

    /** Where a flow's line stands; -1 when it has none here. */
    fun itemOfFlow(id: String): Int = items.indexOfFirst { it is SequenceItem.Line && it.flow.flow.id == id }

    /** The Flows list, newest first. */
    fun flowMenu(): List<FlowMenuItem> {
        val p = page
        val start = p?.startWallMs ?: 0
        val filtered = p != null && p.matched < p.total
        return flows.reversed().map { f ->
            val meta = when {
                f.rows == 0 && f.rowsGone -> "rows no longer kept"
                filtered && f.shown == 0 -> "hidden by the filter"
                else -> SequenceText.clock(f.flow.startMs, start) + " · " + SequenceText.rows(f.rows)
            }
            FlowMenuItem(f.flow.id, f.flow.name, meta, !(f.rows == 0 && f.rowsGone) && !(filtered && f.shown == 0))
        }
    }

    /** Goes to a flow's line; rows Clear left out come back if it is among them. */
    fun goToFlow(id: String): SequenceView {
        val f = flows.firstOrNull { it.flow.id == id } ?: return this
        val before = startMs != null && f.flow.startMs < startMs
        return copy(flowTarget = id, following = false, startMs = if (before) null else startMs, hitIndex = -1)
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
