package io.yamsergey.dta.daemon.runtime

import io.yamsergey.dta.protocol.data.HeapClassRow
import io.yamsergey.dta.protocol.data.HeapOrigin
import io.yamsergey.dta.protocol.data.HeapRunInfo
import io.yamsergey.dta.protocol.data.HeapWalk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 074 (C1): the operations over the store and a sidekick; nothing takes a snapshot as a side effect. */
class HeapServiceTest {
    private var now = 1_000_000L
    private val service = HeapService(HeapSnapshotStore(clock = { now }))
    private val app = "com.example"

    private class Fake(var walk: HeapWalk, var run: String = "r1") : HeapSidekick {
        var runCalls = 0
        var walks = 0
        var lastMax: Long? = null
        var lastGc: Boolean? = null
        override suspend fun run() = HeapRunInfo(run, listOf("com.example")).also { runCalls++ }
        override suspend fun walk(maxPauseMs: Long, gc: Boolean): HeapWalk { walks++; lastMax = maxPauseMs; lastGc = gc; return walk.copy(run = run) }
    }

    private fun rows(n: Long) = (1..30).map { HeapClassRow("c.C$it", null, HeapOrigin.APP, n, n * it) }
    private fun ok(pause: Long = 20, n: Long = 1) = HeapWalk(run = "r1", classes = rows(n), pauseMs = pause)

    @Test fun snapshotStatesPauseAndSummarisesTop20() = runBlocking {
        val a = service.snapshot(app, Fake(ok()), "base", false, null)
        assertEquals(20, a.pauseMs)
        assertNull(a.warning)
        assertEquals("base", a.snapshot.name)
        assertEquals(20, a.snapshot.top.size)
        assertEquals(30, a.snapshot.classCount)
        assertEquals("c.C30", a.snapshot.top.first().className)
        assertTrue(a.notKnown.any { it.contains("retained") })
    }

    @Test fun warnsAboveHundredMillis() = runBlocking {
        val a = service.snapshot(app, Fake(ok(pause = 101)), null, false, null)
        assertTrue(a.warning!!.contains("101"))
        now += 5_000
        assertNull(service.snapshot(app, Fake(ok(pause = 100)), null, false, null).warning)
    }

    @Test fun passesBoundAndGcAndDefaultsTheBound() = runBlocking {
        val f = Fake(ok())
        service.snapshot(app, f, null, true, 3000)
        assertEquals(3000L, f.lastMax); assertEquals(true, f.lastGc)
        now += 5_000
        service.snapshot(app, f, null, false, null)
        assertEquals(1000L, f.lastMax)
    }

    @Test fun stoppedWalkIsRefusedWithHowToRaiseAndStoresNothing() = runBlocking {
        val f = Fake(HeapWalk(run = "r1", pauseMs = 1004, stopped = true, maxPauseMs = 1000))
        val e = assertFailsWith<HeapRefusal> { service.snapshot(app, f, null, false, null) }
        assertTrue(e.message!!.contains("1004") && e.message!!.contains("maxPauseMs"))
        assertTrue(service.list(app).snapshots.isEmpty())
    }

    @Test fun sidekickRefusalPassesItsReason() = runBlocking {
        val f = Fake(HeapWalk(run = "r1", refusal = "the app is not debuggable"))
        assertEquals("the app is not debuggable", assertFailsWith<HeapRefusal> { service.snapshot(app, f, null, false, null) }.message)
    }

    @Test fun tooSoonIsRefusedBeforeTheWalk() = runBlocking {
        val f = Fake(ok())
        service.snapshot(app, f, null, false, null)
        val e = assertFailsWith<HeapRefusal> { service.snapshot(app, f, null, false, null) }
        assertTrue(e.message!!.contains("too soon"))
        assertEquals(1, f.walks)
    }

    @Test fun listAndCompareTakeNoSnapshot() = runBlocking {
        val f = Fake(ok(n = 1))
        service.snapshot(app, f, "a", false, null)
        now += 3000
        f.walk = ok(n = 3)
        service.snapshot(app, f, "b", false, null)
        val l = service.list(app)
        assertEquals(listOf("s1", "s2"), l.snapshots.map { it.id })
        val c = service.compare(app, "a", "b", null, null, null)
        assertEquals(30, c.comparison.rows.size)
        assertEquals("c.C30", c.comparison.rows.first().className)
        assertTrue(c.leftOut.contains("no changed class"))
        assertEquals(2, f.walks)
        assertEquals(2, f.runCalls)
    }

    @Test fun compareFiltersAndSaysWhatItLeftOut() = runBlocking {
        val f = Fake(ok(n = 1))
        service.snapshot(app, f, "a", false, null)
        now += 3000
        f.walk = ok(n = 2)
        service.snapshot(app, f, "b", false, null)
        val c = service.compare(app, "a", "b", "platform", null, 5)
        assertEquals(0, c.comparison.rows.size)
        assertTrue(c.leftOut.contains("30") && c.leftOut.contains("origin"))
        val d = service.compare(app, "a", "b", "all", "c", 3)
        assertEquals(3, d.comparison.rows.size)
        assertFailsWith<IllegalArgumentException> { service.compare(app, "a", "b", "nonsense", null, null) }
    }

    @Test fun compareRefusalsCarryTheReason() = runBlocking {
        val e = assertFailsWith<HeapRefusal> { service.compare(app, "x", "y", null, null, null) }
        assertTrue(e.message!!.contains("no snapshot"))
    }

    @Test fun droppedSnapshotIsSaidInTheAnswer() = runBlocking {
        val f = Fake(ok())
        repeat(21) { service.snapshot(app, f, null, false, null); now += 3000 }
        val l = service.list(app)
        assertEquals(20, l.snapshots.size)
        assertEquals(listOf("s1"), l.dropped.map { it.id })
        assertNotNull(l.run)
    }
}
