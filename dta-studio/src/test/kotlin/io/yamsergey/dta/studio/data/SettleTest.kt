package io.yamsergey.dta.studio.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** D-122: after `connected` the first answers are partial (the app's screen is not up yet); the tab shows each and stops when two in a row agree. */
class SettleTest {
    @Test
    fun `it reads again until two answers in a row are the same, showing each`() = runBlocking<Unit> {
        // 2026-11-27 measurement: [] , [SavedStateHandlesVM], then the full list twice.
        val answers = ArrayDeque(listOf(emptyList(), listOf("SavedStateHandlesVM"), listOf("SavedStateHandlesVM", "CartViewModel"), listOf("SavedStateHandlesVM", "CartViewModel"), listOf("never")))
        val shown = mutableListOf<List<String>>()
        settle({ answers.removeFirst() }, { shown += it }, everyMs = 1, maxReads = 10)
        assertEquals(4, shown.size)
        assertEquals(listOf("SavedStateHandlesVM", "CartViewModel"), shown.last())
    }

    @Test
    fun `it stops at the cap when the answer keeps changing`() = runBlocking<Unit> {
        var n = 0
        val shown = mutableListOf<Int>()
        settle({ ++n }, { shown += it }, everyMs = 1, maxReads = 5)
        assertEquals(listOf(1, 2, 3, 4, 5), shown)
    }

    @Test
    fun `an answer that is already complete is confirmed once and the reading ends`() = runBlocking<Unit> {
        var n = 0
        val shown = mutableListOf<String>()
        settle({ n++; "full" }, { shown += it }, everyMs = 1, maxReads = 5)
        assertEquals(2, n)
        assertEquals(listOf("full", "full"), shown)
    }
}
// recompile
