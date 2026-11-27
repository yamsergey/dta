package io.yamsergey.dta.present.network

import io.yamsergey.dta.present.network.ScopeCaption.Action
import io.yamsergey.dta.protocol.network.EpochCloseReason
import io.yamsergey.dta.protocol.network.FindState
import io.yamsergey.dta.protocol.network.HistoryScope
import io.yamsergey.dta.protocol.network.RunEnd
import io.yamsergey.dta.protocol.network.ScopeEpoch
import io.yamsergey.dta.protocol.network.ScopeRun
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** Feature 071 (US5, boards 1–5 of the approved mockup): the scope's words. Counts grouped as the tab writes them. */
class ScopeCaptionTest {
    private val zone = ZoneId.of("UTC")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) = LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    private val run = ScopeRun("r9", 48_210, startedMs = at(2026, 11, 22, 0, 0, 3))
    private val off = HistoryScope(archive = false, entries = 48_210, run = run, outside = 1_203_550)
    private val on = HistoryScope(archive = true, entries = 1_251_760, run = run)

    @Test
    fun `board 1 - searching this run, with Stop`() {
        assertEquals(ScopeCaption.Caption("Searching this run · 9 shown · 48 210 in this run", Action.STOP, searching = true), ScopeCaption.of(off, 9, filtered = true, FindState.SEARCHING))
    }

    @Test
    fun `board 2 - done, earlier runs named, pointing to the source All, no link`() {
        assertEquals(ScopeCaption.Caption("37 shown · 48 210 in this run · earlier runs: All", earlier = true), ScopeCaption.of(off, 37, filtered = true, FindState.DONE))
        assertEquals(ScopeCaption.Caption("37 shown · 48 210 in this run"), ScopeCaption.of(off.copy(outside = null), 37, filtered = true, FindState.DONE))
    }

    @Test
    fun `board 3 - all history, and its separators`() {
        assertEquals(ScopeCaption.Caption("Searching all history · 12 shown · 1 251 760 in the history", Action.STOP, searching = true), ScopeCaption.of(on, 12, filtered = true, FindState.SEARCHING))
        assertEquals("Earlier run · started 21 Nov 18:02 · 31 406 entries", ScopeCaption.earlierRun(ScopeRun("r8", 31_406, startedMs = at(2026, 11, 21, 18, 2)), zone))
        assertEquals("Archived history · cleared 20 Nov 09:14 · 1 172 144 entries", ScopeCaption.archived(ScopeEpoch("e1", 1_172_144, closedMs = at(2026, 11, 20, 9, 14), reason = EpochCloseReason.CLEARED), zone))
    }

    @Test
    fun `board 4 - the app restarted, the previous run named`() {
        val previous = ScopeRun("r8", 8_412, startedMs = at(2026, 11, 21, 18, 2), endedMs = at(2026, 11, 21, 23, 59, 51), ended = RunEnd.PROCESS_ENDED)
        assertEquals("The app restarted at 00:00:03. Previous run: 8 412 entries, ended 23:59:51 (process-ended).", ScopeCaption.restarted(run, previous, zone))
        assertEquals(ScopeCaption.Caption("5 entries in this run · earlier runs: All", earlier = true), ScopeCaption.of(off.copy(entries = 5), 5, filtered = false))
    }

    @Test
    fun `board 5 - run all with the switch off`() {
        assertEquals("run:all reads only this run while Archive is off.", ScopeCaption.RUN_ALL_HINT)
        assertEquals("run:all reads only this run. Earlier runs: source All.", ScopeCaption.RUN_ALL_HINT_TAB)
        assertEquals(ScopeCaption.Caption("3 shown · 48 210 in this run · earlier runs: All", earlier = true), ScopeCaption.of(off, 3, filtered = true))
    }

    @Test
    fun `the footer words stay short beside the source list (D-106)`() {
        val longest = ScopeCaption.of(off, 37_000, filtered = true, FindState.DONE, saved = io.yamsergey.dta.protocol.network.SavedFiltersApplied(listOf("-url:/health")))
        assertEquals("37 000 shown · 48 210 in this run · earlier runs: All · 1 saved filter on", longest.text)
        assertEquals(true, ScopeCaption.of(off, 3, filtered = true).text.length < 50)
    }

    @Test
    fun `there is no Include archive action any more (D-106)`() {
        assertEquals(listOf(Action.STOP), Action.values().toList())
    }

    @Test
    fun `a stopped search says stopped, never done`() {
        val c = ScopeCaption.of(off, 9, filtered = true, FindState.STOPPED)
        assertEquals("Search stopped · 9 shown · 48 210 in this run", c.text)
        assertEquals(false, c.searching)
    }

    @Test
    fun `the CLI names its switch where the tab points to the source All`() {
        assertEquals("37 shown · 48 210 in this run · earlier runs are in the archive (--archive includes them)", ScopeCaption.cli(ScopeCaption.of(off, 37, filtered = true, FindState.DONE)))
        assertEquals("No caption action", ScopeCaption.cli(ScopeCaption.Caption("No caption action")))
    }

    @Test
    fun `whole history without filters, and saved filters said`() {
        assertEquals("1 251 760 entries in the history", ScopeCaption.of(on, 500, filtered = false).text)
        assertEquals("9 shown · 48 210 in this run · earlier runs: All · 1 saved filter on",
            ScopeCaption.of(off, 9, filtered = false, saved = io.yamsergey.dta.protocol.network.SavedFiltersApplied(listOf("-url:/health"))).text)
    }
}
