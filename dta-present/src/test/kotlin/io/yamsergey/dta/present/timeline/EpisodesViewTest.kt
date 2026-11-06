package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.protocol.timeline.Episode
import io.yamsergey.dta.protocol.timeline.EpisodeLane
import io.yamsergey.dta.protocol.timeline.EpisodeStep
import io.yamsergey.dta.protocol.timeline.EpisodesPage
import io.yamsergey.dta.protocol.timeline.SocketCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpisodesViewTest {
    private fun ep(id: String, ms: Long) = Episode(id, ms, ms, "E$id", steps = listOf(EpisodeStep(ms, "user", "app", "tap")))
    private fun page(vararg e: Episode) = EpisodesPage(e.toList(), e.size, listOf(EpisodeLane("user", "You"), EpisodeLane("app", "App"), EpisodeLane("host:a", "a"), EpisodeLane("socket", "socket")), 26, 1_000)

    @Test
    fun `the newest is open, and a new one takes over unless the user chose`() {
        val two = EpisodesView(page(ep("a", 1_000), ep("b", 2_000)))
        assertFalse(two.isOpen("a"))
        assertTrue(two.isOpen("b"))
        val kept = two.toggle("a").copy(page = page(ep("a", 1_000), ep("b", 2_000), ep("c", 3_000)))
        assertEquals(listOf(true, false, true), listOf("a", "b", "c").map(kept::isOpen))
        assertFalse(two.toggle("b").isOpen("b"))
    }

    @Test
    fun `rows hold steps only under open episodes`() {
        val rows = EpisodesView(page(ep("a", 1_000), ep("b", 7_700))).rows
        assertEquals(3, rows.size)
        assertEquals("0:06.7", (rows[1] as EpisodeRow.Head).time)
        assertEquals("6.70", (rows[2] as EpisodeRow.Step).time)
    }

    @Test
    fun `lanes follow the agreed places, more hosts share the right side`() {
        val p = EpisodesView(page()).positions()
        assertEquals(listOf(0.06, 0.32, 0.70, 0.94), p.values.map { Math.round(it * 100) / 100.0 })
    }

    @Test
    fun `what a line says`() {
        val e = Episode("a", 0, 0, "T", requests = 2, failures = 1, socketOpened = true, socketSent = 1, screenChanged = true, socketCounts = listOf(SocketCount("tick", 3), SocketCount("q", 1), SocketCount("r", 1)))
        assertEquals("1 failed · 2 requests · socket opened · socket message sent · screen changed", EpisodeText.meta(e))
        assertEquals("tick ×3, q ×1, +1", EpisodeText.stream(e))
        assertEquals("⤷ Home", EpisodeText.label(EpisodeStep(0, "app", label = "→ Home", linked = true)))
        assertEquals("→ Home", EpisodeText.label(EpisodeStep(0, "app", label = "→ Home")))
        assertEquals("⤷ GET /a", EpisodeText.label(EpisodeStep(0, "app", "host:a", "GET /a", linked = true)))
    }
}
