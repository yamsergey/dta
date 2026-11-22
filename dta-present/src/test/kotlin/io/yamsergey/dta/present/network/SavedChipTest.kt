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
    fun `the caption says what is shown and the history's size, never a count of matches (069)`() {
        assertEquals("125 690 entries", SavedChip.caption(100, 125_690, null, filtered = false))
        assertEquals("12 shown · 125 690 in the history", SavedChip.caption(12, 125_690, null, filtered = true))
        assertEquals("12 shown · 125 690 in the history · 2 saved filters on", SavedChip.caption(12, 125_690, SavedFiltersApplied(listOf("a:1", "b:2")), filtered = false))
        assertEquals("12 shown · 9 in the history · 1 saved filter on", SavedChip.caption(12, 9, SavedFiltersApplied(listOf("a:1")), filtered = true))
        assertEquals("9 entries", SavedChip.caption(9, 9, SavedFiltersApplied(listOf("a:1"), ignored = true), filtered = false))
    }

    @Test
    fun `the caption says what is loaded, the Load older row says more can load (070 and D-022)`() {
        assertEquals("500 shown · 650 312 in the history", SavedChip.caption(500, 650_312, null, filtered = true, more = true))
        assertEquals("500 shown · 650 312 in the history · 1 saved filter on", SavedChip.caption(500, 650_312, SavedFiltersApplied(listOf("a:1")), filtered = true, more = true))
        assertEquals("12 shown · 650 312 in the history", SavedChip.caption(12, 650_312, null, filtered = true, more = false))
        // No filter: the history's size says it all.
        assertEquals("650 312 entries", SavedChip.caption(500, 650_312, null, filtered = false, more = true))
    }

    @Test
    fun `the Load older row counts the history beyond the rows only when nothing filters (D-022 and 069)`() {
        assertEquals("701 older in the history", SavedChip.older(500, 1_201, filtered = false))
        assertEquals("older matches in the history", SavedChip.older(500, 1_201, filtered = true))
    }
}
