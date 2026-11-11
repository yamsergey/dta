package io.yamsergey.dta.sidekick.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 057: the capture's main-thread block is timed where it runs, not with the wait for it. */
class MainThreadTimeTest {
    @Test
    fun `times the block and keeps its result`() {
        val (value, ms) = MainThreadTime.timed { Thread.sleep(30); "tree" }
        assertEquals("tree", value)
        assertTrue(ms in 30..1_000, "$ms")
    }

    @Test
    fun `a block that throws still throws`() {
        val failure = runCatching { MainThreadTime.timed { error("boom") } }.exceptionOrNull()
        assertEquals("boom", failure?.message)
    }
}
