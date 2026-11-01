package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.ui.SourceState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Where the Timeline tab draws what (feature 017, research R3): one lane per source, marks by
 * device wall time, close events grouped, gaps and process ends across all lanes, axis ticks.
 * Only the window in view is laid out. Pure: the panel paints it as it is.
 */
data class LaneLayout(
    val lanes: List<Lane>,
    val spans: List<Span>,
    val ticks: List<Tick>,
    val off: List<Off>,
    val startMs: Long,
    val msPerPx: Double,
) {
    data class Lane(val source: String, val marks: List<Mark>)

    data class Mark(
        val x: Double,
        val eventIds: List<String>,
        val selected: Boolean,
        val unexplained: Boolean,
        val tooltip: String,
    ) {
        val count: Int get() = eventIds.size
    }

    data class Span(val x0: Double, val x1: Double, val kind: String, val tooltip: String)

    data class Tick(val x: Double, val label: String)

    data class Off(val source: String, val reason: String)

    /** The pixel column of a wall time. */
    fun x(wallMs: Long): Double = (wallMs - startMs) / msPerPx

    companion object {
        /** Marks closer than this are drawn as one group. */
        const val SPACING_PX = 10.0
        private const val TICK_MIN_PX = 80.0
        private val ORDER = listOf(Sources.INPUT, Sources.UI, Sources.NETWORK, Sources.NAV, Sources.SCREEN, Sources.APP)
        private val STEPS = longArrayOf(
            1, 2, 5, 10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 15_000, 30_000,
            60_000, 120_000, 300_000, 600_000, 1_800_000, 3_600_000,
        )

        fun of(view: TimelineView, widthPx: Int, zone: ZoneId = ZoneId.systemDefault()): LaneLayout {
            val w = view.window
            val start = w.endMs - (widthPx * w.msPerPx).toLong()
            fun x(ms: Long) = (ms - start) / w.msPerPx

            val inView = slice(view.events, start, w.endMs).let { if (view.filter.active) it.filter(view.filter::matches) else it }
            val on = view.sources.filter { it.state != SourceState.OFF }.map { it.name }
            val present = inView.map { it.source }.filter { it != Sources.TIMELINE }
            val lanesOrder = (on + present).distinct().sortedWith(compareBy({ ORDER.indexOf(it).let { i -> if (i < 0) ORDER.size else i } }, { it }))
            val byLane = inView.filter { it.source != Sources.TIMELINE }.groupBy { it.source }

            val lanes = lanesOrder.map { source ->
                val marks = ArrayList<Mark>()
                var bucket = Long.MIN_VALUE
                var group = ArrayList<TimelineEvent>()
                fun flush() {
                    if (group.isEmpty()) return
                    val ids = group.map { it.id }
                    marks += Mark(
                        x = group.sumOf { x(it.wallMs) } / group.size,
                        eventIds = ids,
                        selected = view.selectedId in ids,
                        unexplained = group.any(TimelineFilter::unexplained),
                        tooltip = if (group.size == 1) tooltip(group.single(), zone) else "${group.size} events — zoom in to tell them apart",
                    )
                    group = ArrayList()
                }
                for (e in byLane[source].orEmpty()) {
                    val b = kotlin.math.floor(x(e.wallMs) / SPACING_PX).toLong()
                    if (b != bucket) flush()
                    bucket = b
                    group += e
                }
                flush()
                Lane(source, marks)
            }

            val spans = inView.filter { it.source == Sources.TIMELINE }.mapNotNull { e ->
                when (e.kind) {
                    Kinds.GAP -> Span(x(e.gapFromMs ?: e.wallMs), x(e.gapToMs ?: e.wallMs), Kinds.GAP, TimelineEventText.describe(e).trim())
                    Kinds.PROCESS_ENDED -> Span(x(e.wallMs), x(e.wallMs), Kinds.PROCESS_ENDED, "the app's process ended")
                    else -> null
                }
            }

            val step = STEPS.firstOrNull { it / w.msPerPx >= TICK_MIN_PX } ?: STEPS.last()
            val format = DateTimeFormatter.ofPattern(
                when {
                    step < 1_000 -> "HH:mm:ss.SSS"
                    step < 60_000 -> "HH:mm:ss"
                    else -> "HH:mm"
                },
            ).withZone(zone)
            val ticks = generateSequence(Math.floorDiv(start, step) * step + step) { it + step }
                .takeWhile { it <= w.endMs }
                .map { Tick(x(it), format.format(Instant.ofEpochMilli(it))) }
                .toList()

            val off = view.sources.filter { it.state == SourceState.OFF }.map { Off(it.name, it.reason ?: "no reason given") }
            return LaneLayout(lanes, spans, ticks, off, start, w.msPerPx)
        }

        /** The events whose wall time is in [from, to], by binary search over the time-ordered list. */
        private fun slice(events: List<TimelineEvent>, from: Long, to: Long): List<TimelineEvent> {
            fun lower(ms: Long): Int {
                var lo = 0
                var hi = events.size
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (events[mid].wallMs < ms) lo = mid + 1 else hi = mid
                }
                return lo
            }
            return events.subList(lower(from), lower(to + 1))
        }

        private fun tooltip(e: TimelineEvent, zone: ZoneId): String {
            val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(zone).format(Instant.ofEpochMilli(e.wallMs))
            return "$time  ${TimelineEventText.line(e)}"
        }
    }
}
