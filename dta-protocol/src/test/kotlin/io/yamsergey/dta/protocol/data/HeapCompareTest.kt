package io.yamsergey.dta.protocol.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Feature 074 (A1): comparison of two snapshots of one run. */
class HeapCompareTest {
    private fun row(name: String, count: Long, bytes: Long, origin: HeapOrigin = HeapOrigin.APP, loader: String? = null) =
        HeapClassRow(name, loader, origin, count, bytes)

    private fun snap(id: String, rows: List<HeapClassRow>, run: String = "r1") =
        HeapSnapshot(id = id, name = null, run = run, takenAtMs = 0, pauseMs = 10, gcBefore = false, classes = rows,
            totals = HeapTotals(rows.sumOf { it.count }, rows.sumOf { it.bytes }))

    private fun ok(r: HeapCompareResult): HeapComparison = assertIs<HeapCompareResult.Ok>(r).comparison

    @Test fun classifiesAppearedGoneGrewShrank() {
        val a = snap("s1", listOf(row("A", 1, 10), row("Gone", 2, 20), row("Shrink", 5, 50), row("Same", 1, 1)))
        val b = snap("s2", listOf(row("A", 3, 30), row("New", 1, 100), row("Shrink", 2, 20), row("Same", 1, 1)))
        val c = ok(compareHeap(a, b, HeapFilter(origin = null)))
        val by = c.rows.associateBy { it.className }
        assertEquals(HeapChange.GREW, by.getValue("A").change)
        assertEquals(HeapChange.APPEARED, by.getValue("New").change)
        assertEquals(HeapChange.GONE, by.getValue("Gone").change)
        assertEquals(HeapChange.SHRANK, by.getValue("Shrink").change)
        assertTrue("Same" !in by)
        assertEquals(1, c.unchangedClasses)
        val g = by.getValue("A")
        assertEquals(1L, g.countA); assertEquals(3L, g.countB); assertEquals(2L, g.deltaCount)
        assertEquals(10L, g.bytesA); assertEquals(30L, g.bytesB); assertEquals(20L, g.deltaBytes)
        assertEquals(0L, by.getValue("Gone").countB)
    }

    @Test fun sortsByAbsBytesThenAbsCount() {
        val a = snap("s1", listOf(row("X", 10, 100), row("Y", 1, 10), row("Z", 1, 10)))
        val b = snap("s2", listOf(row("X", 0 + 1, 10), row("Y", 5, 110), row("Z", 2, 110)))
        // deltas: X -90 bytes/-9, Y +100/+4, Z +100/+1
        val c = ok(compareHeap(a, b, HeapFilter(origin = null)))
        assertEquals(listOf("Y", "Z", "X"), c.rows.map { it.className })
    }

    @Test fun defaultFilterIsAppAndSaysWhatItLeftOut() {
        val a = snap("s1", emptyList())
        val b = snap("s2", listOf(row("app.A", 1, 10), row("lib.L", 1, 10, HeapOrigin.LIBRARY), row("java.P", 4, 40, HeapOrigin.PLATFORM)))
        val c = ok(compareHeap(a, b))
        assertEquals(listOf("app.A"), c.rows.map { it.className })
        assertEquals(HeapOrigin.APP, c.filters.origin)
        assertEquals(2, c.leftOut.byOrigin)
        assertEquals(50L, c.leftOut.bytesDelta)
        assertTrue(c.leftOut.describe().contains("origin"))
    }

    @Test fun packagePrefixAndTop() {
        val a = snap("s1", emptyList())
        val b = snap("s2", listOf(row("com.x.A", 1, 10), row("com.x.B", 1, 20), row("com.y.C", 1, 30)))
        val c = ok(compareHeap(a, b, HeapFilter(origin = null, packagePrefix = "com.x", top = 1)))
        assertEquals(listOf("com.x.B"), c.rows.map { it.className })
        assertEquals(1, c.leftOut.byPackage)
        assertEquals(1, c.leftOut.byTop)
        assertEquals(40L, c.leftOut.bytesDelta)
    }

    @Test fun sameNameDifferentLoadersAreDifferentRows() {
        val a = snap("s1", listOf(row("A", 1, 10, loader = "l1"), row("A", 1, 10, loader = "l2")))
        val b = snap("s2", listOf(row("A", 2, 20, loader = "l1"), row("A", 1, 10, loader = "l2")))
        val c = ok(compareHeap(a, b, HeapFilter(origin = null)))
        assertEquals(1, c.rows.size)
        assertEquals("l1", c.rows[0].loader)
    }

    @Test fun refusesDifferentRuns() {
        val r = compareHeap(snap("s1", emptyList(), "r1"), snap("s2", emptyList(), "r2"))
        val refused = assertIs<HeapCompareResult.Refused>(r)
        assertTrue(refused.reason.contains("r1") && refused.reason.contains("r2"))
        assertTrue(refused.reason.contains("run"))
    }

    @Test fun totalsDeltaIsReported() {
        val a = snap("s1", listOf(row("A", 1, 10)))
        val b = snap("s2", listOf(row("A", 4, 40)))
        val c = ok(compareHeap(a, b))
        assertEquals(30L, c.totalsDeltaBytes)
        assertEquals(3L, c.totalsDeltaObjects)
    }
}
