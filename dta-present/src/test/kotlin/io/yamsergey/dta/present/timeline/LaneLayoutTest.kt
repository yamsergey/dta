package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.present.timeline.TimelineFixture.click
import io.yamsergey.dta.present.timeline.TimelineFixture.dialog
import io.yamsergey.dta.present.timeline.TimelineFixture.ended
import io.yamsergey.dta.present.timeline.TimelineFixture.flow
import io.yamsergey.dta.present.timeline.TimelineFixture.gap
import io.yamsergey.dta.present.timeline.TimelineFixture.request
import io.yamsergey.dta.present.timeline.TimelineFixture.toast
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.SourceStatus
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.ui.SourceState
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 017 (T007): where the Timeline tab draws what. */
class LaneLayoutTest {
    private val statuses = listOf(
        SourceStatus("app", SourceState.ON), SourceStatus("input", SourceState.ON), SourceStatus("ui", SourceState.ON),
        SourceStatus("network", SourceState.ON), SourceStatus("nav", SourceState.ON), SourceStatus("screen", SourceState.OFF, "hooks unavailable"),
    )

    /** 1 ms per px, the right edge at 2 000 ms: the fixture's events from 1 100 to 1 600 ms are in view. */
    private fun view(events: List<TimelineEvent> = flow + gap + ended) = TimelineView()
        .reduce(TimelineChange.Loaded(events))
        .reduce(TimelineChange.Sources(statuses))
        .reduce(TimelineChange.Follow(false))
        .copy(window = ViewWindow(endMs = 2_000, msPerPx = 1.0, follow = false))

    private fun layout(v: TimelineView = view(), width: Int = 1_000) = LaneLayout.of(v, width, ZoneOffset.UTC)

    @Test
    fun `lanes in the fixed order, sources that are off listed with their reason`() {
        val l = layout()
        assertEquals(listOf("input", "ui", "network", "nav", "app"), l.lanes.map { it.source })
        assertEquals(listOf(LaneLayout.Off("screen", "hooks unavailable")), l.off)
    }

    @Test
    fun `a source with events but no status gets a lane after the known ones`() {
        val custom = toast.copy(id = "7:50", seq = 50, source = "otel", kind = "otel/span")
        assertEquals("otel", layout(view(flow + custom)).lanes.last().source)
    }

    @Test
    fun `marks are placed by wall time`() {
        val input = layout().lanes.single { it.source == Sources.INPUT }
        assertEquals(1_100.0 - 1_000.0, input.marks.single().x, 0.5)
    }

    @Test
    fun `events closer than the spacing become a group with a count`() {
        val ui = layout().lanes.single { it.source == Sources.UI }
        val network = layout().lanes.single { it.source == Sources.NETWORK }
        assertEquals(2, ui.marks.size, "dialog and toast are far apart")
        val zoomedOut = layout(view().copy(window = ViewWindow(endMs = 2_000, msPerPx = 100.0, follow = false)))
        val grouped = zoomedOut.lanes.single { it.source == Sources.NETWORK }.marks
        assertEquals(1, grouped.size)
        assertEquals(2, grouped.single().count)
        assertEquals(listOf(request.id, TimelineFixture.response.id), grouped.single().eventIds)
        assertEquals(2, network.marks.size)
    }

    @Test
    fun `gaps and process ends span every lane`() {
        val spans = layout().spans
        assertEquals(listOf(Kinds.GAP, Kinds.PROCESS_ENDED), spans.map { it.kind })
        assertEquals(1_800.0 - 1_000.0, spans.last().x0, 0.5)
    }

    @Test
    fun `the selected mark and marks without a known cause are flagged`() {
        val l = layout(view().reduce(TimelineChange.Select(dialog.id)))
        val ui = l.lanes.single { it.source == Sources.UI }.marks
        assertTrue(ui.single { dialog.id in it.eventIds }.selected)
        assertTrue(ui.single { toast.id in it.eventIds }.unexplained)
        assertTrue(!l.lanes.single { it.source == Sources.INPUT }.marks.single().unexplained, "a click is a root")
    }

    @Test
    fun `only the window in view is laid out`() {
        val narrow = layout(view().copy(window = ViewWindow(endMs = 1_200, msPerPx = 1.0, follow = false)), width = 150)
        assertEquals(listOf(click.id, request.id, dialog.id), narrow.lanes.flatMap { it.marks }.flatMap { it.eventIds }.sorted())
    }

    @Test
    fun `ticks follow the zoom`() {
        val fine = layout()
        assertEquals("00:00:01.100", fine.ticks.first { it.x >= 99 }.label)
        val coarse = layout(view().copy(window = ViewWindow(endMs = 600_000, msPerPx = 1_000.0, follow = false)))
        assertTrue(coarse.ticks.all { it.label.matches(Regex("\\d\\d:\\d\\d(:\\d\\d)?")) }, coarse.ticks.toString())
    }

    @Test
    fun `ten thousand events are laid out quickly`() {
        val many = (1..10_000).map { i -> click.copy(id = "7:$i", seq = i.toLong(), wallMs = i * 10L, source = Sources.BUILT_IN[i % 6]) }
        val v = TimelineView().reduce(TimelineChange.Loaded(many)).copy(window = ViewWindow(endMs = 100_000, msPerPx = 62.5, follow = false))
        LaneLayout.of(v, 1_600, ZoneOffset.UTC)
        val started = System.nanoTime()
        repeat(5) { LaneLayout.of(v, 1_600, ZoneOffset.UTC) }
        val ms = (System.nanoTime() - started) / 5 / 1_000_000
        assertTrue(ms < 50, "$ms ms per layout")
    }
}
