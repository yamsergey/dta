package io.yamsergey.dta.sidekick.network

import okio.Timeout
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 023: the close wait is OkHttp's own discard wait, set and restored on the body's timeout. */
class CloseWaitsTest {

    @Test
    fun `HTTP-1 waits at most OkHttp's discard time and restores the deadline`() {
        val timeout = Timeout()
        val wait = assertNotNull(CloseWaits.okio(timeout, "http/1.1"))
        val restore = assertNotNull(wait.begin())
        val after = System.nanoTime()
        assertTrue(timeout.hasDeadline())
        assertTrue(timeout.deadlineNanoTime() - after <= TimeUnit.MILLISECONDS.toNanos(CloseWaits.DISCARD_MILLIS))
        restore.close()
        assertTrue(!timeout.hasDeadline())
    }

    @Test
    fun `an earlier deadline of the app stays`() {
        val timeout = Timeout().deadlineNanoTime(System.nanoTime() + 1_000)
        val before = timeout.deadlineNanoTime()
        CloseWaits.okio(timeout, "http/1.1")!!.begin()!!.use { assertEquals(before, timeout.deadlineNanoTime()) }
        assertEquals(before, timeout.deadlineNanoTime())
    }

    @Test
    fun `HTTP-2 and unknown protocols do not wait`() {
        assertNull(CloseWaits.okio(Timeout(), "h2"))
        assertNull(CloseWaits.okio(Timeout(), null))
    }
}
