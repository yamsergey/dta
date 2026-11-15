package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.present.timeline.TimelineFixture.click
import io.yamsergey.dta.present.timeline.TimelineFixture.flow
import io.yamsergey.dta.present.timeline.TimelineFixture.request
import io.yamsergey.dta.present.timeline.TimelineFixture.response
import io.yamsergey.dta.protocol.network.SavedFiltersApplied
import io.yamsergey.dta.protocol.timeline.CauseChain
import io.yamsergey.dta.protocol.timeline.ChainDirection
import io.yamsergey.dta.protocol.timeline.ChainLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 065 (T017): what the Timeline tab shows of Network's saved filters, in both views. */
class TimelineSavedBarTest {
    private val words = listOf("-host:telemetry.example.test")

    @Test
    fun `no saved filter shows nothing`() {
        assertEquals(TimelineSavedBar.NONE, TimelineSavedBar.of(null, showing = false))
        assertFalse(TimelineSavedBar.NONE.visible)
    }

    @Test
    fun `hidden rows are counted, with the chips and Show them`() {
        val bar = TimelineSavedBar.of(SavedFiltersApplied(words, hidden = 10), showing = false)
        assertEquals(words, bar.chips)
        assertEquals("Network's filters:", bar.label)
        assertEquals("10 network rows hidden by Network's filters", bar.caption)
        assertEquals(TimelineSavedBar.Action.SHOW, bar.action)
        assertFalse(bar.off)
        assertNull(bar.banner)
    }

    @Test
    fun `rows not judged are counted beside the hidden ones`() {
        val bar = TimelineSavedBar.of(SavedFiltersApplied(words, hidden = 1, notJudged = 2), showing = false)
        assertEquals("1 network row hidden by Network's filters · 2 not judged: their requests are no longer kept", bar.caption)
        val none = TimelineSavedBar.of(SavedFiltersApplied(words, hidden = 0, notJudged = 1), showing = false)
        assertEquals("Network's filters hid nothing here · 1 not judged: its request is no longer kept", none.caption)
        assertNull(none.action)
    }

    @Test
    fun `nothing hidden keeps the chips and says nothing below`() {
        val bar = TimelineSavedBar.of(SavedFiltersApplied(words, hidden = 0), showing = false)
        assertEquals(words, bar.chips)
        assertNull(bar.caption)
        assertNull(bar.action)
    }

    @Test
    fun `shown in this view, chips are off, the filter stays saved, Hide them again`() {
        val bar = TimelineSavedBar.of(SavedFiltersApplied(words, ignored = true), showing = true)
        assertTrue(bar.off)
        assertEquals("Network's filters, off here:", bar.label)
        assertEquals("Network's filters are off in this view", bar.caption)
        assertEquals(TimelineSavedBar.Action.HIDE, bar.action)
    }

    @Test
    fun `a store that cannot be read is a banner, and nothing is hidden`() {
        val bar = TimelineSavedBar.of(SavedFiltersApplied(emptyList(), problem = "unexpected end of file"), showing = false)
        assertEquals("Network's saved filters could not be read (unexpected end of file). Nothing is hidden.", bar.banner)
        assertTrue(bar.visible)
        assertNull(bar.action)
        val off = TimelineSavedBar.of(SavedFiltersApplied(words, unavailable = "no network history"), showing = false)
        assertEquals("Network's saved filters are not applied: no network history. Nothing is hidden.", off.banner)
    }

    @Test
    fun `the list counts its own rows`() {
        val bar = TimelineSavedBar.of(SavedFiltersApplied(words, hidden = 1), showing = false, hiddenRows = 2, notJudgedRows = 0)
        assertEquals("2 network rows hidden by Network's filters", bar.caption)
    }

    @Test
    fun `the list leaves out the hidden entries' rows until shown, and marks the ones not judged`() {
        val v = TimelineView().reduce(TimelineChange.Loaded(flow)).reduce(TimelineChange.Hidden(setOf("n7"), emptySet()))
        assertFalse(v.shown.any { it.network?.entryId == "n7" })
        assertEquals(2, v.hiddenRows)
        assertEquals(flow.size - 2, v.shown.size)
        val shown = v.reduce(TimelineChange.ShowHidden(true))
        assertEquals(flow.size, shown.shown.size)
        assertEquals(0, shown.hiddenRows)
        val unjudged = TimelineView().reduce(TimelineChange.Loaded(flow)).reduce(TimelineChange.Hidden(emptySet(), setOf("n7")))
        assertTrue(unjudged.notJudged(request))
        assertFalse(unjudged.notJudged(click))
        assertEquals(2, unjudged.notJudgedRows)
    }

    @Test
    fun `a chain keeps its hidden rows, marked`() {
        val causes = CauseChain(
            response, ChainDirection.CAUSES, listOf(response, request, click),
            listOf(ChainLink(request.id, response.id, response.causes.first()), ChainLink(click.id, request.id, request.causes.first())),
            saved = SavedFiltersApplied(words, hidden = 1, hiddenIds = listOf(request.id)),
        )
        val v = ChainView.of(causes, null)
        assertEquals(listOf(true, false), v.causedBy.map { it.hidden })
    }
}
