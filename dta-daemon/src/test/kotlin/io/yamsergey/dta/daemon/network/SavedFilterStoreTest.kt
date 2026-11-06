package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.RecentFilter
import io.yamsergey.dta.protocol.network.SavedFiltersApplied
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedFilterStoreTest {
    private val dir = Files.createTempDirectory("filters").toFile()
    private var now = 1000L
    private fun store() = SavedFilterStore(dir) { now }

    @Test
    fun `added words are saved in order, once`() {
        val s = store()
        s.add("app", "-host:a.example.test status:2xx")
        s.add("app", "-host:a.example.test")
        assertEquals(listOf("-host:a.example.test", "status:2xx"), s.state("app").saved)
    }

    @Test
    fun `saved filters survive a restart and belong to one app`() {
        store().add("app", "-url:/health")
        assertEquals(listOf("-url:/health"), store().state("app").saved)
        assertEquals(emptyList(), store().state("other").saved)
    }

    @Test
    fun `search words, problems and session filters are refused, naming them`() {
        val s = store()
        assertTrue(assertFailsWith<InvalidParamsException> { s.add("app", "token") }.message!!.contains("token"))
        assertTrue(assertFailsWith<InvalidParamsException> { s.add("app", "statsu:4xx") }.message!!.contains("statsu:4xx"))
        assertTrue(assertFailsWith<InvalidParamsException> { s.add("app", "since:5m") }.message!!.contains("since:5m"))
        assertEquals(emptyList(), s.state("app").saved)
    }

    @Test
    fun `removing moves it to recent, adding takes it back`() {
        val s = store()
        s.add("app", "-url:/health")
        now = 2000
        assertTrue(s.remove("app", "-url:/health"))
        assertEquals(emptyList(), s.state("app").saved)
        assertEquals(listOf(RecentFilter("-url:/health", 2000)), s.state("app").recent)
        s.add("app", "-url:/health")
        assertEquals(emptyList(), s.state("app").recent)
        assertEquals(false, s.remove("app", "-url:/other"))
    }

    @Test
    fun `a new filter of a key replaces the saved one, which becomes recent`() {
        val s = store()
        s.add("app", "status:2xx -host:a.example.test")
        s.add("app", "status:4xx")
        assertEquals(listOf("-host:a.example.test", "status:4xx"), s.state("app").saved)
        assertEquals(listOf("status:2xx"), s.state("app").recent.map { it.word })
    }

    @Test
    fun `recent keeps the last twenty, newest first`() {
        val s = store()
        repeat(25) { i -> now = 1000L + i; s.add("app", "-url:/p$i"); s.remove("app", "-url:/p$i") }
        val recent = s.state("app").recent
        assertEquals(20, recent.size)
        assertEquals("-url:/p24", recent.first().word)
    }

    @Test
    fun `an unreadable file starts empty, says so, and is kept aside`() {
        val f = File(dir, "filters/app.json").apply { parentFile.mkdirs(); writeText("{not json") }
        val state = store().state("app")
        assertEquals(emptyList(), state.saved)
        assertNotNull(state.problem)
        assertTrue(File(dir, "filters/app.json.bad").isFile)
        assertTrue(!f.exists())
    }

    private data class Page(val total: Int, val saved: SavedFiltersApplied? = null, val q: String? = null)

    private fun run(s: SavedFilterStore, query: NetworkQuery, totals: (String?) -> Int) = runBlocking {
        s.applied("app", query, run = { q -> Page(totals(q.q), q = q.q) }, total = { it.total }, mark = { p, a -> p.copy(saved = a) })
    }

    @Test
    fun `a call runs with the saved words and reports what they hid`() {
        val s = store()
        s.add("app", "-host:t.example.test")
        val page = run(s, NetworkQuery(q = "ACME")) { q -> if (q == "ACME") 10 else 3 }
        assertEquals("-host:t.example.test ACME", page.q)
        assertEquals(SavedFiltersApplied(listOf("-host:t.example.test"), hidden = 7), page.saved)
    }

    @Test
    fun `later pages do not count again`() {
        val s = store()
        s.add("app", "-host:t.example.test")
        val page = run(s, NetworkQuery(before = "cursor")) { 3 }
        assertNull(page.saved!!.hidden)
    }

    @Test
    fun `ignoring lists everything, says so and changes nothing`() {
        val s = store()
        s.add("app", "-host:t.example.test")
        val page = run(s, NetworkQuery(q = "ACME", ignoreSavedFilters = true)) { 10 }
        assertEquals("ACME", page.q)
        assertEquals(SavedFiltersApplied(listOf("-host:t.example.test"), ignored = true), page.saved)
        assertEquals(listOf("-host:t.example.test"), s.state("app").saved)
    }

    @Test
    fun `nothing saved, nothing said`() {
        val page = run(store(), NetworkQuery(q = "ACME")) { 10 }
        assertNull(page.saved)
        assertEquals("ACME", page.q)
    }
}
