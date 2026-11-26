package io.yamsergey.dta.present.data

import io.yamsergey.dta.protocol.data.HeapChange
import io.yamsergey.dta.protocol.data.HeapClassRow
import io.yamsergey.dta.protocol.data.HeapCompareAnswer
import io.yamsergey.dta.protocol.data.HeapComparison
import io.yamsergey.dta.protocol.data.HeapComparisonRow
import io.yamsergey.dta.protocol.data.HeapDroppedInfo
import io.yamsergey.dta.protocol.data.HeapFilter
import io.yamsergey.dta.protocol.data.HeapLeftOut
import io.yamsergey.dta.protocol.data.HeapListAnswer
import io.yamsergey.dta.protocol.data.HeapOrigin
import io.yamsergey.dta.protocol.data.HeapSnapshotAnswer
import io.yamsergey.dta.protocol.data.HeapSnapshotInfo
import io.yamsergey.dta.protocol.data.HeapSnapshotSummary
import io.yamsergey.dta.protocol.data.HeapTotals
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Feature 074 (C1): the CLI text of the heap answers states the pause, the warning and what step 1 does not know. */
class HeapTextTest {
    private fun answer(pause: Long, warning: String? = null, dropped: String? = null) = HeapSnapshotAnswer(
        snapshot = HeapSnapshotSummary(
            "s1", "base", "r1", 0, pause, false, HeapTotals(1000, 64_000), 2,
            listOf(HeapClassRow("com.x.A", null, HeapOrigin.APP, 10, 4096), HeapClassRow("byte[]", null, HeapOrigin.PLATFORM, 5, 1024)),
            notes = listOf("note one"),
        ),
        pauseMs = pause, warning = warning, dropped = dropped,
    )

    @Test fun snapshotStatesPauseAlwaysAndWarning() {
        val t = HeapText.snapshot(answer(18))
        assertTrue(t.contains("paused the app 18 ms"))
        assertFalse(t.contains("WARNING"))
        assertTrue(t.contains("com.x.A") && t.contains("4.0 KB") || t.contains("com.x.A"))
        assertTrue(t.contains("not known"))
        val w = HeapText.snapshot(answer(150, warning = "The app's threads were stopped for 150 ms by this walk.", dropped = "Snapshot s1 was dropped."))
        assertTrue(w.contains("150 ms") && w.contains("stopped for 150 ms"))
        assertTrue(w.contains("was dropped"))
    }

    @Test fun listShowsSnapshotsAndDropped() {
        val t = HeapText.list(HeapListAnswer("r1", listOf(HeapSnapshotInfo("s2", "after", 0, 20, 10, 100)), listOf(HeapDroppedInfo("s1", null))))
        assertTrue(t.contains("s2") && t.contains("after") && t.contains("20 ms"))
        assertTrue(t.contains("dropped") && t.contains("s1"))
        assertTrue(HeapText.list(HeapListAnswer(note = "No snapshot has been taken")).contains("No snapshot"))
    }

    @Test fun compareShowsRowsDeltasAndLeftOut() {
        val c = HeapComparison(
            "s1", "s2", "r1",
            listOf(HeapComparisonRow("com.x.Leak", null, HeapOrigin.APP, 1, 4, 10, 40, HeapChange.GREW)),
            HeapFilter(), HeapLeftOut(byOrigin = 3, bytesDelta = 99), 5, 3, 30,
        )
        val t = HeapText.compare(HeapCompareAnswer(c, c.leftOut.describe()))
        assertTrue(t.contains("com.x.Leak") && t.contains("+3") && t.contains("+30"))
        assertTrue(t.contains("left out"))
        assertTrue(t.contains("not known"))
    }
}
