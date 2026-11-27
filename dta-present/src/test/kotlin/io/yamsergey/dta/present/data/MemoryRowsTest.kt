package io.yamsergey.dta.present.data

import io.yamsergey.dta.protocol.data.HeapChange
import io.yamsergey.dta.protocol.data.HeapComparison
import io.yamsergey.dta.protocol.data.HeapComparisonRow
import io.yamsergey.dta.protocol.data.HeapFilter
import io.yamsergey.dta.protocol.data.HeapInstance
import io.yamsergey.dta.protocol.data.HeapInstancesAnswer
import io.yamsergey.dta.protocol.data.HeapLeftOut
import io.yamsergey.dta.protocol.data.HeapLink
import io.yamsergey.dta.protocol.data.HeapOrigin
import io.yamsergey.dta.protocol.data.HeapSnapshotInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 074 (D1): what the Memory view shows, as data (Runtime › Memory, boards Memory / MemoryNarrow / MemoryEmpty). */
class MemoryRowsTest {
    private fun row(name: String, a: Long, b: Long, ba: Long, bb: Long, origin: HeapOrigin = HeapOrigin.APP) =
        HeapComparisonRow(name, null, origin, a, b, ba, bb, if (b >= a) HeapChange.GREW else HeapChange.SHRANK)

    private fun cmp(vararg r: HeapComparisonRow, left: HeapLeftOut = HeapLeftOut()) =
        HeapComparison("s1", "s2", "r", r.toList(), HeapFilter(), left, 3, 0, 0)

    @Test fun longNamesAreElidedInTheMiddleSoTheSimpleNameStays() {
        val full = "io.yamsergey.dta.apps.broker.ui.OrderActivity"
        assertEquals(full, MemoryRows.elide(full, full.length))
        assertEquals("io.yamsergey…OrderActivity", MemoryRows.elide(full, 26))
        assertEquals("…OrderActivity", MemoryRows.elide(full, 14))
        assertEquals("byte[]", MemoryRows.elide("byte[]", 10))
        assertEquals("OrderActivi…", MemoryRows.elide("a.b.OrderActivityXL", 12))
        assertEquals("a.b.C\$Inner", MemoryRows.elide("a.b.C\$Inner", 20))
    }

    @Test fun comparisonColumnsFollowTheWidth() {
        assertEquals(listOf("Class", "A", "B", "Δ count", "Δ size"), MemoryRows.columns(narrow = false, histogram = false))
        assertEquals(listOf("Class", "Δ count", "Δ size"), MemoryRows.columns(narrow = true, histogram = false))
        assertEquals(listOf("Class", "Count", "Size"), MemoryRows.columns(narrow = false, histogram = true))
    }

    @Test fun comparisonRowsShowSignedDeltasAndGrowingClassesStayFirst() {
        val rows = MemoryRows.rows(cmp(row("com.x.broker.ui.OrderActivity", 1, 4, 400, 1600), row("com.x.broker.ui.MarketsViewModel", 1, 1, 96, 96)), narrow = false, histogram = false)
        assertEquals(listOf("com.x.broker.ui.OrderActivity", "1", "4", "+3", "+1.2 KB"), rows[0].cells)
        assertEquals(listOf("com.x.broker.ui.MarketsViewModel", "1", "1", "0", "0 B"), rows[1].cells)
        assertTrue(rows[0].grew)
        assertFalse(rows[1].grew)
        assertEquals("com.x.broker.ui.OrderActivity", rows[0].className)
        assertEquals(listOf("com.x.broker.ui.OrderActivity", "+3", "+1.2 KB"), MemoryRows.rows(cmp(row("com.x.broker.ui.OrderActivity", 1, 4, 400, 1600)), narrow = true, histogram = false)[0].cells)
    }

    @Test fun shrinkingClassesShowMinus() {
        val r = MemoryRows.rows(cmp(row("a.b.C", 5, 2, 500, 200)), narrow = false, histogram = false)[0]
        assertEquals(listOf("a.b.C", "5", "2", "-3", "-300 B"), r.cells)
    }

    @Test fun aHistogramShowsCountAndSizeOfTheOneSnapshot() {
        val r = MemoryRows.rows(cmp(row("a.b.C", 0, 12, 0, 2048)), narrow = false, histogram = true)[0]
        assertEquals(listOf("a.b.C", "12", "2.0 KB"), r.cells)
    }

    @Test fun pickersNameTheSnapshotByIdNameAndTime() {
        val i = HeapSnapshotInfo("s2", "after 3 rotations", takenAtMs = 0, pauseMs = 38, objects = 10, bytes = 20)
        assertEquals("s2 · after 3 rotations · 11:59:40", MemoryRows.pickerLabel(i) { "11:59:40" })
        assertEquals("s3 · 12:00:01", MemoryRows.pickerLabel(HeapSnapshotInfo("s3", null, 0, 5, 1, 1)) { "12:00:01" })
    }

    @Test fun thePauseIsStatedNextToTheSnapshotItBelongsTo() {
        val b = HeapSnapshotInfo("s2", "after", 0, 38, 1, 1, gcBefore = false)
        val s = MemoryRows.statement("B", b, allOrigins = false)
        assertEquals("B paused the app 38 ms · no garbage collection before B: objects no longer reachable may still be counted", s)
        assertTrue(MemoryRows.statement("B", b.copy(gcBefore = true), false).contains("after a garbage collection"))
    }

    @Test fun aPauseAbove100MsIsWarnedAbout() {
        val s = MemoryRows.statement("B", HeapSnapshotInfo("s2", null, 0, 150, 1, 1), false)
        assertTrue(s.contains("150 ms") && s.contains("longer than 100 ms"))
        assertFalse(MemoryRows.statement("B", HeapSnapshotInfo("s2", null, 0, 100, 1, 1), false).contains("longer than"))
    }

    @Test fun allOriginsSayBootImageObjectsAreCounted() {
        val b = HeapSnapshotInfo("s2", null, 0, 10, 1, 1)
        assertTrue(MemoryRows.statement("B", b, allOrigins = true).contains("boot image"))
        assertFalse(MemoryRows.statement("B", b, allOrigins = false).contains("boot image"))
    }

    @Test fun leftOutIsStatedWhenTheFiltersCutRows() {
        assertNull(MemoryRows.leftOut(cmp()))
        assertEquals("3 changed classes left out (3 by origin), together +99 bytes", MemoryRows.leftOut(cmp(left = HeapLeftOut(byOrigin = 3, bytesDelta = 99)))!!.trimEnd('.'))
    }

    @Test fun theEmptyStateIsOneSentenceAndTheLimitIsAlwaysStated() {
        assertTrue(MemoryRows.EMPTY.startsWith("No snapshot in this run. Take one"))
        assertTrue(MemoryRows.NOT_KNOWN.contains("heap dump"))
    }

    @Test fun instancesCountStatesAndLinkToTheRuntimeViews() {
        val a = HeapInstancesAnswer(
            "com.x.broker.ui.OrderActivity", 3, 0,
            listOf(
                HeapInstance(4294967297, "com.x.broker.ui.OrderActivity", 400, HeapLink("activity", "7a3c01"), "resumed"),
                HeapInstance(4294967298, "com.x.broker.ui.OrderActivity", 400, HeapLink("activity", "5e12f4"), "destroyed"),
                HeapInstance(4294967299, "com.x.broker.ui.OrderActivity", 400, HeapLink("viewmodel", "41b0a9")),
            ),
            "now",
        )
        assertEquals("OrderActivity · 3 alive now", MemoryRows.instanceHeader(a))
        assertEquals("1 resumed, 1 destroyed; listed now, not as they were in a snapshot", MemoryRows.instanceSummary(a))
        val rows = MemoryRows.instanceRows(a)
        assertEquals("@100000001", rows[0].label)
        assertEquals("resumed", rows[0].state)
        assertEquals("Activities ›", rows[0].linkText)
        assertEquals(MemoryRows.Target.ACTIVITIES, rows[0].target)
        assertEquals("7a3c01", rows[0].ref)
        assertEquals(MemoryRows.Target.VIEWMODELS, rows[2].target)
        assertEquals("", rows[2].state)
        assertEquals(MemoryRows.Target.NAVIGATION, MemoryRows.instanceRows(a.copy(instances = listOf(HeapInstance(1, "x", 1, HeapLink("navcontroller", "c")))))[0].target)
        assertNull(MemoryRows.instanceRows(a.copy(instances = listOf(HeapInstance(1, "x", 1))))[0].target)
    }

    @Test fun serverMessagesThatMustBeShownWholeAreLeftAsTheyAre() {
        val msg = "too soon: one heap walk per 2000 ms per app; next allowed in 1442 ms (at 1795699995416)"
        assertEquals(msg, MemoryRows.refusal(RuntimeException(msg)))
        assertEquals("RuntimeException", MemoryRows.refusal(RuntimeException()))
    }
}
