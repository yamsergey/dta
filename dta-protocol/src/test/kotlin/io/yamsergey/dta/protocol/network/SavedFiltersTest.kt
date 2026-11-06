package io.yamsergey.dta.protocol.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SavedFiltersTest {
    @Test
    fun `saved words go before the line`() {
        val m = SavedFilters.merge(listOf("-host:t.example.test", "status:2xx"), "ACME")
        assertEquals(listOf("-host:t.example.test", "status:2xx"), m.applied)
        assertEquals("-host:t.example.test status:2xx ACME", m.q)
    }

    @Test
    fun `nothing saved leaves the line as it is`() {
        val m = SavedFilters.merge(emptyList(), "ACME")
        assertEquals(emptyList(), m.applied)
        assertEquals("ACME", m.q)
        assertNull(SavedFilters.merge(emptyList(), null).q)
    }

    @Test
    fun `a filter typed in the line wins over a saved one of the same key`() {
        val m = SavedFilters.merge(listOf("status:2xx", "-host:t.example.test"), "status:4xx")
        assertEquals(listOf("-host:t.example.test"), m.applied)
        assertEquals("-host:t.example.test status:4xx", m.q)
        assertEquals(emptyList(), QueryLine.parse(m.q!!).problems)
    }

    @Test
    fun `bare words count as their key`() {
        assertEquals(listOf("-url:/a"), SavedFilters.merge(listOf("method:POST", "-url:/a"), "GET").applied)
        assertEquals(emptyList(), SavedFilters.merge(listOf("kind:ws"), "http").applied)
    }

    @Test
    fun `exclusions never conflict`() {
        val m = SavedFilters.merge(listOf("-host:a.example.test"), "-host:b.example.test")
        assertEquals(listOf("-host:a.example.test"), m.applied)
    }

    @Test
    fun `the key a word sets, for replacing`() {
        assertEquals("status", SavedFilters.positiveKey("status:2xx"))
        assertEquals("method", SavedFilters.positiveKey("POST"))
        assertNull(SavedFilters.positiveKey("-status:2xx"))
    }
}
