package io.yamsergey.dta.sidekick.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionHubTest {

    private class FakeSession(
        private val gate: CountDownLatch? = null,
        var ready: Boolean = true,
        var failing: Boolean = false,
    ) : SessionHub.Sink {
        val received = CopyOnWriteArrayList<Pair<String, JsonElement?>>()
        override val helloCompleted: Boolean get() = ready
        override fun send(name: String, data: JsonElement?) {
            gate?.await(5, TimeUnit.SECONDS)
            if (failing) throw IOException("peer gone")
            received += name to data
        }
    }

    private val hub = SessionHub(outboxCapacity = 3)

    @AfterTest
    fun stop() = hub.close()

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out: $what" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `an event reaches every live session`() {
        val a = FakeSession()
        val b = FakeSession()
        hub.add(a)
        hub.add(b)
        hub.publish("network.entry", JsonPrimitive(1))
        awaitTrue("both delivered") { a.received.size == 1 && b.received.size == 1 }
    }

    @Test
    fun `removed and failing sessions are skipped, others keep receiving`() {
        val gone = FakeSession()
        val failing = FakeSession(failing = true)
        val live = FakeSession()
        hub.add(gone).close()
        hub.add(failing)
        hub.add(live)
        hub.publish("network.entry", JsonPrimitive(1))
        hub.publish("network.entry", JsonPrimitive(2))
        awaitTrue("live got both") { live.received.size == 2 }
        assertTrue(gone.received.isEmpty())
        assertEquals(1, hub.sessionCount)
    }

    @Test
    fun `sessions before hello get nothing`() {
        val early = FakeSession(ready = false)
        hub.add(early)
        hub.publish("network.entry", JsonPrimitive(1))
        Thread.sleep(100)
        assertTrue(early.received.isEmpty())
    }

    @Test
    fun `a slow session loses overflow, counted and reported when it drains`() {
        val gate = CountDownLatch(1)
        val slow = FakeSession(gate = gate)
        hub.add(slow)
        val start = System.nanoTime()
        repeat(10) { hub.publish("network.entry", JsonPrimitive(it)) }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500, "publish must not block")
        gate.countDown()
        awaitTrue("drop report") { slow.received.any { it.first == "network.events-dropped" } }
        Thread.sleep(100)
        val reported = slow.received.filter { it.first == "network.events-dropped" }
            .sumOf { it.second!!.jsonObject["count"]!!.jsonPrimitive.int }
        val delivered = slow.received.count { it.first == "network.entry" }
        assertEquals(10, delivered + reported, "every event is either delivered or counted as dropped")
        assertTrue(reported > 0)
    }
}
