package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.daemon.network.Judgement
import io.yamsergey.dta.protocol.network.NetworkHistoryView
import io.yamsergey.dta.protocol.timeline.InputAction
import io.yamsergey.dta.protocol.timeline.InputPayload
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.NetworkPayload
import io.yamsergey.dta.protocol.timeline.NetworkPhase
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Feature 065: `timeline watch` hides what Network's saved filters hide, batch by batch, and says so. */
class WatchJudgingTest {
    private var seq = 1L
    private fun tap() = TimelineEvent("7:${seq}", seq++, 1_000, 1_000, Sources.INPUT, Kinds.CLICK, input = InputPayload(InputAction.CLICK, text = "Buy", via = "compose"))
    private fun req(entry: String) = TimelineEvent("7:${seq}", seq++, 1_000, 1_000, Sources.NETWORK, Kinds.NETWORK_REQUEST, network = NetworkPayload(entry, NetworkPhase.REQUEST, "GET", "h", "/"))

    private var judged = 0
    private var words = listOf("-host:beacon")
    private var passing = NetworkHistoryView.Passing(setOf("order"), setOf("order", "beacon1", "beacon2"))
    private val watch = WatchJudging({ words }) {
        judged++
        Judgement(words, passing = passing)
    }

    @Test
    fun `a watch says at its start which filters apply`() = runBlocking {
        assertEquals(words, watch.start()?.words)
        assertEquals(0, judged, "naming them reads no history")
    }

    @Test
    fun `network rows the filters hide are left out, others stay, and nothing more is said`() = runBlocking {
        watch.start()
        val t = tap()
        val batch = watch.judge(listOf(t, req("beacon1"), req("order"), req("beacon1")))
        assertEquals(listOf(t.id, "7:3"), batch.events.map { it.id })
        assertNull(batch.saved)
    }

    @Test
    fun `a batch without network rows asks nothing`() = runBlocking {
        watch.start()
        val batch = watch.judge(listOf(tap(), tap()))
        assertEquals(0, judged)
        assertNull(batch.saved)
    }

    @Test
    fun `an entry judged once is not judged again`() = runBlocking {
        watch.start()
        watch.judge(listOf(req("beacon1"), req("order")))
        val batch = watch.judge(listOf(req("beacon1"), req("order"), tap()))
        assertEquals(1, judged)
        assertEquals(2, batch.events.size, "the order and the tap")
    }

    @Test
    fun `changed filters judge afresh and are said once`() = runBlocking {
        watch.start()
        watch.judge(listOf(req("beacon1")))
        words = listOf("method:POST")
        passing = NetworkHistoryView.Passing(emptySet(), setOf("order", "beacon1", "beacon2"))
        val changed = watch.judge(listOf(req("order")))
        assertEquals(listOf("method:POST"), changed.saved?.words)
        assertEquals(0, changed.events.size, "judged with the words in force now")
        assertNull(watch.judge(listOf(req("beacon2"))).saved)
    }

    @Test
    fun `filters removed during a watch are said, and everything passes`() = runBlocking {
        watch.start()
        words = emptyList()
        val batch = watch.judge(listOf(req("beacon1")))
        assertEquals(1, batch.events.size)
        assertEquals(emptyList(), batch.saved?.words)
    }

    @Test
    fun `an entry the history does not hold yet is shown and asked again`() = runBlocking {
        watch.start()
        assertEquals(1, watch.judge(listOf(req("unknown"))).events.size)
        watch.judge(listOf(req("unknown")))
        assertEquals(2, judged)
    }

    @Test
    fun `with nothing saved nothing is said or asked`() = runBlocking {
        words = emptyList()
        assertNull(watch.start())
        val batch = watch.judge(listOf(req("beacon1")))
        assertEquals(1, batch.events.size)
        assertNull(batch.saved)
        assertEquals(0, judged)
    }
}
