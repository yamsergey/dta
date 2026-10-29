package io.yamsergey.dta.present.network

import io.yamsergey.dta.present.network.LiveEvent.Arrived
import io.yamsergey.dta.present.network.LiveEvent.Cleared
import io.yamsergey.dta.present.network.LiveEvent.JumpToEnd
import io.yamsergey.dta.present.network.LiveEvent.Loaded
import io.yamsergey.dta.present.network.LiveEvent.Older
import io.yamsergey.dta.present.network.LiveEvent.Pause
import io.yamsergey.dta.present.network.LiveEvent.Resume
import io.yamsergey.dta.present.network.LiveEvent.ScrolledAway
import io.yamsergey.dta.present.network.LiveEvent.Selected
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveListTest {

    private fun entry(seq: Int, state: String = "complete", status: Int? = 200) = buildJsonObject {
        put("type", "http"); put("id", "r1-1:$seq"); put("runId", "r1-1"); put("timeMs", 1_700_000_000_000 + seq * 1000L)
        put("method", "GET"); put("url", "https://api.example.com/$seq"); put("state", state)
        status?.let { put("status", it) }
    }

    /** A network.list page: newest first, as the daemon returns it. */
    private fun page(vararg seqs: Int, total: Int = seqs.size, next: String? = null) = buildJsonObject {
        put("entries", buildJsonArray { seqs.sortedDescending().forEach { add(entry(it)) } })
        put("total", total)
        next?.let { put("next", it) }
        put("coverage", buildJsonObject { put("searched", total); put("stored", total); put("persisted", true) })
    }

    private fun ids(list: LiveList) = list.rows.map { it.id }

    private val loaded = LiveList().reduce(Loaded(page(1, 2, 3, total = 10, next = "c1")))

    @Test
    fun `a page loads oldest to newest and following, with the total and the older cursor`() {
        assertEquals(listOf("r1-1:1", "r1-1:2", "r1-1:3"), ids(loaded))
        assertTrue(loaded.following)
        assertEquals(3 to 10, loaded.shown to loaded.total)
        assertEquals("c1", loaded.older)
    }

    @Test
    fun `arrivals append while following, and count as new once the person looks away`() {
        val following = loaded.reduce(Arrived(entry(4)))
        assertEquals("r1-1:4", following.rows.last().id)
        assertEquals(0, following.newCount)

        val away = following.reduce(ScrolledAway).reduce(Arrived(entry(5))).reduce(Arrived(entry(6)))
        assertFalse(away.following)
        assertEquals(2, away.newCount)
        assertEquals(listOf("r1-1:1", "r1-1:2", "r1-1:3", "r1-1:4", "r1-1:5", "r1-1:6"), ids(away), "rows still arrive; only the view stays put")

        val back = away.reduce(JumpToEnd)
        assertTrue(back.following)
        assertEquals(0, back.newCount)
    }

    @Test
    fun `selecting a row stops following`() {
        val selected = loaded.reduce(Selected("r1-1:2"))
        assertFalse(selected.following)
        assertEquals("r1-1:2", selected.selectedId)
        assertEquals(1, selected.reduce(Arrived(entry(4))).newCount)
    }

    @Test
    fun `an update replaces the row in place without moving it or counting it`() {
        val pending = loaded.reduce(Arrived(entry(4, state = "pending", status = null))).reduce(Selected("r1-1:4"))
        val updated = pending.reduce(LiveEvent.Updated(entry(4)))
        assertEquals(ids(pending), ids(updated))
        assertEquals("complete", updated.rows.last().entry["state"].toString().trim('"'))
        assertEquals(0, updated.newCount)
        assertEquals("r1-1:4", updated.selectedId)
    }

    @Test
    fun `paused applies nothing, says so, and resume applies the backlog`() {
        val paused = loaded.reduce(Pause).reduce(Arrived(entry(4))).reduce(LiveEvent.Updated(entry(2, status = 500)))
        assertTrue(paused.paused)
        assertEquals(ids(loaded), ids(paused))
        assertEquals(2, paused.backlog)
        val resumed = paused.reduce(Resume)
        assertFalse(resumed.paused)
        assertEquals("r1-1:4", resumed.rows.last().id)
        assertEquals("500", resumed.rows[1].entry["status"].toString())
        assertEquals(0, resumed.backlog)
    }

    @Test
    fun `the selection survives a re-query, or is marked gone when it left the list`() {
        val selected = loaded.reduce(Selected("r1-1:2"))
        val kept = selected.reduce(Loaded(page(2, 3)))
        assertEquals("r1-1:2", kept.selectedId)
        assertFalse(kept.selectedGone)
        val gone = selected.reduce(Loaded(page(3)))
        assertEquals("r1-1:2", gone.selectedId)
        assertTrue(gone.selectedGone)
    }

    @Test
    fun `older pages prepend, and page or event overlap never duplicates`() {
        val older = loaded.reduce(Older(page(0, 1, total = 10)))
        assertEquals(listOf("r1-1:0", "r1-1:1", "r1-1:2", "r1-1:3"), ids(older))
        assertEquals(null, older.older)
        val dup = older.reduce(Arrived(entry(3)))
        assertEquals(ids(older), ids(dup))
    }

    @Test
    fun `clear empties the list and the selection`() {
        val cleared = loaded.reduce(Selected("r1-1:2")).reduce(Cleared)
        assertEquals(emptyList(), cleared.rows)
        assertEquals(null, cleared.selectedId)
        assertTrue(cleared.following)
    }

    @Test
    fun `one arrival on 5,000 rows takes under 5 ms`() {
        val big = LiveList().reduce(Loaded(buildJsonObject {
            put("entries", buildJsonArray { (5000 downTo 1).forEach { add(entry(it)) } })
            put("total", 5000)
            put("coverage", buildJsonObject { put("searched", 5000); put("stored", 5000); put("persisted", true) })
        }))
        var list = big
        repeat(50) { list = list.reduce(Arrived(entry(6000 + it))) } // warm up
        val start = System.nanoTime()
        repeat(100) { list = list.reduce(Arrived(entry(7000 + it))) }
        val perArrival = (System.nanoTime() - start) / 100 / 1_000_000.0
        assertTrue(perArrival < 5.0, "$perArrival ms per arrival")
        assertEquals(5150, list.rows.size)
    }

}
