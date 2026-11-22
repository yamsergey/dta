package io.yamsergey.dta.present.network

import io.yamsergey.dta.protocol.network.EpochCloseReason
import io.yamsergey.dta.protocol.network.HistoryScope
import io.yamsergey.dta.protocol.network.ScopeEpoch
import io.yamsergey.dta.protocol.network.ScopeRun
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** Feature 071 (board 3): separators above each earlier run's and archived epoch's rows. */
class RunSeparatorsTest {
    private val zone = ZoneId.of("UTC")
    private val scope = HistoryScope(archive = true, entries = 6, run = ScopeRun("r9", 2),
        runs = listOf(ScopeRun("r9", 2), ScopeRun("r8", 2, startedMs = 0)), epochs = listOf(ScopeEpoch("e1", 2, closedMs = 0, reason = EpochCloseReason.CLEARED)))
    // Oldest to newest: two archived, two of the earlier run, two of the current one.
    private val rows = listOf("a1" to "e1", "a2" to "e1", "p1" to "r8", "p2" to "r8", "c1" to "r9", "c2" to "r9")

    private fun ids(newestFirst: Boolean) = RunSeparators.insert(rows, { it.second }, scope, newestFirst, zone) { it.text to "-" }.map { it.first }

    @Test
    fun `newest first, each group's separator shows above it`() {
        // The model keeps oldest to newest; shown reversed, the separator is above its group.
        assertEquals(listOf("c2", "c1", "Earlier run · started 1 Jan 00:00 · 2 entries", "p2", "p1", "Archived history · cleared 1 Jan 00:00 · 2 entries", "a2", "a1"), ids(true).reversed())
    }

    @Test
    fun `oldest first, the separator comes before its group`() {
        assertEquals(listOf("Archived history · cleared 1 Jan 00:00 · 2 entries", "a1", "a2", "Earlier run · started 1 Jan 00:00 · 2 entries", "p1", "p2", "c1", "c2"), ids(false))
    }

    @Test
    fun `without the switch, no separators`() {
        assertEquals(rows.map { it.first }, RunSeparators.insert(rows, { it.second }, scope.copy(archive = false), true, zone) { it.text to "-" }.map { it.first })
    }
}
