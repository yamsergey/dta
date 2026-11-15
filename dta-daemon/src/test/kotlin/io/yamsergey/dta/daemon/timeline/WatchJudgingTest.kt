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
    fun `network rows the filters hide are left out and counted, others stay`() = runBlocking {
        val t = tap()
        val batch = watch.judge(listOf(t, req("beacon1"), req("order"), req("beacon1")))
        assertEquals(listOf(t.id, "7:3"), batch.events.map { it.id })
        assertEquals(2, batch.saved?.hidden)
        assertEquals(words, batch.saved?.words)
    }

    @Test
    fun `a batch without network rows asks nothing and says nothing`() = runBlocking {
        val batch = watch.judge(listOf(tap(), tap()))
        assertEquals(0, judged)
        assertNull(batch.saved)
    }

    @Test
    fun `an entry judged once is not judged again`() = runBlocking {
        watch.judge(listOf(req("beacon1"), req("order")))
        val batch = watch.judge(listOf(req("beacon1"), req("order"), tap()))
        assertEquals(1, judged)
        assertEquals(1, batch.saved?.hidden)
    }

    @Test
    fun `a new entry asks again, and changed filters judge afresh`() = runBlocking {
        watch.judge(listOf(req("beacon1")))
        watch.judge(listOf(req("beacon2")))
        assertEquals(2, judged)
        words = listOf("method:POST")
        passing = NetworkHistoryView.Passing(emptySet(), setOf("order", "beacon1", "beacon2"))
        watch.judge(listOf(req("order")))
        val again = watch.judge(listOf(req("beacon1")))
        assertEquals(1, again.saved?.hidden, "judged with the words in force now")
    }

    @Test
    fun `an entry the history does not hold is shown and counted as not judged`() = runBlocking {
        val batch = watch.judge(listOf(req("unknown")))
        assertEquals(1, batch.events.size)
        assertEquals(1, batch.saved?.notJudged)
    }

    @Test
    fun `with nothing saved everything passes and nothing is said`() = runBlocking {
        words = emptyList()
        val batch = watch.judge(listOf(req("beacon1")))
        assertEquals(1, batch.events.size)
        assertNull(batch.saved)
    }
}
