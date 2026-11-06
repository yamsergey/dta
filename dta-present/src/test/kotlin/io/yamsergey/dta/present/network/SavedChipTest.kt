package io.yamsergey.dta.present.network

import io.yamsergey.dta.protocol.network.SavedFiltersApplied
import kotlin.test.Test
import kotlin.test.assertEquals

class SavedChipTest {
    @Test
    fun `a chip splits key and value`() {
        assertEquals(SavedChip("-host:t.example.test", "-host:", "t.example.test"), SavedChip.of("-host:t.example.test"))
        assertEquals(SavedChip("POST", "", "POST"), SavedChip.of("POST"))
    }

    @Test
    fun `age reads as a person says it`() {
        val min = 60_000L
        assertEquals("just now", SavedChip.age(0, 10))
        assertEquals("5 min ago", SavedChip.age(0, 5 * min))
        assertEquals("2 h ago", SavedChip.age(0, 125 * min))
        assertEquals("yesterday", SavedChip.age(0, 30 * 60 * min))
        assertEquals("3 days ago", SavedChip.age(0, 3 * 24 * 60 * min))
    }

    @Test
    fun `the caption says what saved filters hid`() {
        assertEquals("3 of 3 shown", SavedChip.caption(3, 3, null))
        assertEquals("3 shown · 26 hidden by 2 saved filters", SavedChip.caption(3, 3, SavedFiltersApplied(listOf("a:1", "b:2"), hidden = 26)))
        assertEquals("3 of 9 shown · 1 hidden by 1 saved filter", SavedChip.caption(3, 9, SavedFiltersApplied(listOf("a:1"), hidden = 1)))
        assertEquals("3 of 3 shown", SavedChip.caption(3, 3, SavedFiltersApplied(listOf("a:1"), hidden = 0)))
        assertEquals("3 of 3 shown", SavedChip.caption(3, 3, SavedFiltersApplied(listOf("a:1"), ignored = true)))
    }
}
