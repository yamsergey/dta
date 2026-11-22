package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 071 (FR-005, FR-008, SC-003): no time cuts a pattern; a stop reaches into a match. */
class PatternStopTest {
    private fun body() = BodyInfo(BodyPart.RESPONSE, storedBytes = 10, contentType = "text/plain", completeness = Completeness.COMPLETE)
    private fun http(n: Int) = HttpEntry("r1:$n", "r1", n * 100L, "okhttp3", method = "GET", url = "https://api.example.test/$n", state = HttpState.COMPLETE, status = 200, responseBody = body())

    private fun view(entries: List<HttpEntry>, bodies: Map<String, String>, stop: () -> PatternStop = { PatternStop.NONE }) = NetworkHistoryView(
        { entries }, { id -> entries.firstOrNull { it.id == id } },
        { HistoryContext("r1", Coverage(searched = 0, stored = entries.size, persisted = true), CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE)) },
        bodyBytes = { e, _ -> bodies[e.id]?.let { NetworkFilterEval.BodyBytes(it.toByteArray(), true) } },
        patternStop = stop,
    )

    @Test
    fun `a slow pattern is matched to the end on every body, and nothing is said about time`() {
        // Over 100 ms per body for (.*a){6}x on 50 a's: the 053 budget (50 ms a body) skipped each of them.
        val entries = (1..4).map(::http)
        val bodies = entries.associate { e -> e.id to if (e.id == "r1:3") "a".repeat(40) + "x" else "a".repeat(50) }
        val v = view(entries, bodies)
        val started = System.nanoTime()
        val page = v.list(NetworkQuery(q = "body:/(.*a){6}x/"))
        assertEquals(listOf("r1:3"), page.entries.map { it.id })
        assertTrue((System.nanoTime() - started) / 1_000_000 > 150, "the bodies were slow enough that a budget would have cut them")
        assertTrue(page.coverage.notSearched.orEmpty().none { "slow" in it || "skipped" in it }, page.coverage.notSearched.toString())
        val search = v.search(NetworkQuery(q = "body:/(.*a){6}x/"))
        assertEquals(listOf("r1:3"), search.entries.map { it.id })
        assertNull(search.notSearched)
    }

    @Test
    fun `the hits are those a plain regex finds`() {
        val entries = (1..50).map(::http)
        val bodies = entries.associate { e -> e.id to (if (e.timeMs % 300 == 0L) "id=${e.timeMs} ok" else "nothing ${e.timeMs}") }
        val p = java.util.regex.Pattern.compile("id=\\d+", java.util.regex.Pattern.CASE_INSENSITIVE)
        val expected = entries.filter { p.matcher(bodies.getValue(it.id)).find() }.map { it.id }.reversed()
        assertEquals(expected, view(entries, bodies).search(NetworkQuery(q = "body:/id=\\d+/", limit = 1000)).entries.map { it.id })
    }

    @Test
    fun `a stop ends a runaway match on a 1 MB body within 200 ms`() {
        val stopped = AtomicBoolean(false)
        val v = view(listOf(http(1)), mapOf("r1:1" to "x".repeat(1 shl 20))) { PatternStop { stopped.get() } }
        var tookMs = -1L
        var at = 0L
        val t = thread {
            Thread.sleep(300)
            at = System.nanoTime()
            stopped.set(true)
        }
        assertFailsWith<SearchStoppedException> {
            try {
                v.search(NetworkQuery(q = "body:/(x+x+)+y/"))
            } finally {
                tookMs = (System.nanoTime() - at) / 1_000_000
            }
        }
        t.join()
        assertTrue(tookMs in 0..200, "stopped after $tookMs ms")
    }

    @Test
    fun `a stopped search is a cancellation, not an error`() {
        assertTrue(SearchStoppedException() is java.util.concurrent.CancellationException)
    }
}
