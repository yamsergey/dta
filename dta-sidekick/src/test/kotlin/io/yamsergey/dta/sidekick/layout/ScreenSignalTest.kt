package io.yamsergey.dta.sidekick.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 057: the screen signal's lease and coalescing, deviceless. */
class ScreenSignalTest {
    private var now = 1_000L
    private val core = ScreenSignalCore(clock = { now })

    @Test
    fun `a lease arms the watch, renews it, and ends it`() {
        assertTrue(core.lease(10_000), "the first lease arms it")
        assertTrue(core.leased)
        now += 9_000
        assertFalse(core.lease(10_000), "a renewal does not arm it again")
        now += 9_000
        assertTrue(core.leased, "renewed")
        now += 2_000
        assertFalse(core.leased, "expired")
    }

    @Test
    fun `a lease is capped and zero stops it`() {
        core.lease(600_000)
        assertEquals(now + 30_000, core.until)
        core.lease(0)
        assertFalse(core.leased)
    }

    @Test
    fun `changes coalesce into one notice per half second`() {
        core.lease(10_000)
        core.mark(ScreenSignalCore.Reason.LAYOUT)
        core.mark(ScreenSignalCore.Reason.COMPOSE)
        val first = core.tick()
        assertEquals(listOf("layout", "compose"), first?.reasons)
        assertEquals(now, first?.at)
        core.mark(ScreenSignalCore.Reason.WINDOWS)
        now += 200
        assertNull(core.tick(), "too soon after the last notice")
        now += 300
        assertEquals(listOf("windows"), core.tick()?.reasons, "kept until it may be sent")
        now += 600
        assertNull(core.tick(), "nothing changed")
    }

    @Test
    fun `nothing is marked or sent without a lease`() {
        core.mark(ScreenSignalCore.Reason.LAYOUT)
        core.lease(10_000)
        assertNull(core.tick(), "a mark before the lease is not a change seen while watching")
        now += 11_000
        core.mark(ScreenSignalCore.Reason.LAYOUT)
        assertNull(core.tick(), "expired")
    }
}
