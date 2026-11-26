package io.yamsergey.dta.daemon.runtime

import io.yamsergey.dta.protocol.data.HeapClassRow
import io.yamsergey.dta.protocol.data.HeapOrigin
import io.yamsergey.dta.protocol.data.HeapCompareResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 074 (A2): per-run store, bound 20, named kept, rate limit, one walk at a time. */
class HeapSnapshotStoreTest {
    private var now = 1_000_000L
    private val store = HeapSnapshotStore(clock = { now })
    private val app = "com.example"

    private fun rows(n: Long = 1) = listOf(HeapClassRow("A", null, HeapOrigin.APP, n, n * 10))

    /** Takes one snapshot through the full begin/complete cycle and advances the clock past the rate limit. */
    private fun take(name: String? = null, run: String = "r1", gap: Long = 2_000): HeapSnapshotStore.Completed {
        val begin = store.begin(app, run, name)
        val ticket = assertIs<HeapSnapshotStore.Begin.Allowed>(begin).ticket
        val done = assertIs<HeapSnapshotStore.Completion.Stored>(store.complete(ticket, 12, false, rows(), emptyList()))
        now += gap
        return done.result
    }

    @Test fun assignsSequentialIdsAndListsThem() {
        take(); take("keep")
        val l = store.list(app)!!
        assertEquals("r1", l.run)
        assertEquals(listOf("s1", "s2"), l.snapshots.map { it.id })
        assertEquals("keep", l.snapshots[1].name)
        assertEquals(12L, l.snapshots[0].pauseMs)
    }

    @Test fun boundIsTwentyAndOldestUnnamedIsDroppedAndReported() {
        take("n1")
        repeat(19) { take() }
        assertEquals(20, store.list(app)!!.snapshots.size)
        val r = take()
        val dropped = r.dropped!!
        assertEquals("s2", dropped.id) // s1 is named and kept
        val l = store.list(app)!!
        assertEquals(20, l.snapshots.size)
        assertTrue(l.snapshots.any { it.name == "n1" })
        assertEquals(listOf("s2"), l.dropped.map { it.id })
    }

    @Test fun refusesWhenAllTwentyAreNamed() {
        repeat(20) { take("n$it") }
        val b = assertIs<HeapSnapshotStore.Begin.Refused>(store.begin(app, "r1", "extra"))
        assertTrue(b.reason.contains("20") && b.reason.contains("named"))
        assertEquals(20, store.list(app)!!.snapshots.size)
    }

    @Test fun namesAreUniqueRefusedBeforeTheWalk() {
        take("same")
        val b = assertIs<HeapSnapshotStore.Begin.Refused>(store.begin(app, "r1", "same"))
        assertTrue(b.reason.contains("same"))
    }

    @Test fun rateLimitOnePerTwoSecondsSaysWhenNext() {
        take(gap = 500)
        val b = assertIs<HeapSnapshotStore.Begin.Refused>(store.begin(app, "r1", null))
        assertEquals(now + 1_500, b.nextAllowedAtMs)
        assertTrue(b.reason.contains("1500") || b.reason.contains("1.5"))
        now += 1_500
        assertIs<HeapSnapshotStore.Begin.Allowed>(store.begin(app, "r1", null))
    }

    @Test fun rateLimitIsPerApp() {
        take(gap = 0)
        assertIs<HeapSnapshotStore.Begin.Allowed>(store.begin("other.app", "x", null))
    }

    @Test fun onlyOneWalkInFlightPerApp() {
        val t = assertIs<HeapSnapshotStore.Begin.Allowed>(store.begin(app, "r1", null)).ticket
        now += 5_000
        val b = assertIs<HeapSnapshotStore.Begin.Refused>(store.begin(app, "r1", null))
        assertTrue(b.reason.contains("in progress"))
        store.abort(t)
        assertIs<HeapSnapshotStore.Begin.Allowed>(store.begin(app, "r1", null))
    }

    @Test fun abortedWalkStoresNothingButStillCountsForTheRateLimit() {
        val t = assertIs<HeapSnapshotStore.Begin.Allowed>(store.begin(app, "r1", null)).ticket
        store.abort(t)
        assertNull(store.list(app)?.snapshots?.firstOrNull())
        assertIs<HeapSnapshotStore.Begin.Refused>(store.begin(app, "r1", null))
    }

    @Test fun newRunReplacesEarlierRunsSnapshots() {
        take(); take()
        assertEquals("r1", store.list(app)!!.run)
        take(run = "r2")
        val l = store.list(app)!!
        assertEquals("r2", l.run)
        assertEquals(listOf("s1"), l.snapshots.map { it.id })
        assertTrue(l.dropped.isEmpty())
    }

    @Test fun earlierRunStaysListableUntilReplaced() {
        take()
        store.runStarted(app, "r2")
        // run r2 is known but nothing was taken yet: r1's snapshots stay listable for r1
        val l = store.list(app)!!
        assertEquals("r1", l.run)
        assertEquals(1, l.snapshots.size)
    }

    @Test fun compareResolvesByIdOrNameAndExplainsUnknown() {
        take("before"); take("after")
        val ok = assertIs<HeapCompareResult.Ok>(store.compare(app, "before", "s2"))
        assertEquals("s1", ok.comparison.a)
        val bad = assertIs<HeapCompareResult.Refused>(store.compare(app, "before", "nope"))
        assertTrue(bad.reason.contains("nope"))
        val none = assertIs<HeapCompareResult.Refused>(store.compare("unknown.app", "a", "b"))
        assertTrue(none.reason.contains("no snapshot"))
    }
}
