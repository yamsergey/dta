package io.yamsergey.dta.client

import io.yamsergey.dta.protocol.rpc.Event
import io.yamsergey.dta.protocol.rpc.Response
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D-048: a collector more than 1,024 events behind loses none silently; responses never pass through the buffer. */
class DaemonClientEventsTest {

    private fun event(n: Int) = Event("network.entry", n.toLong(), JsonObject(mapOf("n" to JsonPrimitive(n))))

    private fun counted(e: Event): Int =
        if (e.name == DROPPED_EVENT_NAME) ((e.data as JsonObject)["count"] as JsonPrimitive).content.toInt() else 1

    @Test
    fun `a slow collector sees every event or a stated drop count that adds up`() = runBlocking {
        val hub = EventHub(capacity = 1024)
        val seen = ArrayList<Event>()
        val total = 5000
        val job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            hub.flow.collect {
                delay(1) // slow: far behind from the first event
                synchronized(seen) { seen.add(it) }
            }
        }
        for (i in 1..total) hub.publish(event(i))
        withTimeout(30_000) { while (synchronized(seen) { seen.sumOf { counted(it) } } < total) delay(20) }
        job.cancel()
        assertEquals(total, synchronized(seen) { seen.sumOf { counted(it) } }, "every event is seen or counted by a marker")
        assertTrue(synchronized(seen) { seen.any { it.name == DROPPED_EVENT_NAME } }, "5000 behind a 1024 buffer must say it dropped")
    }

    @Test
    fun `a response is delivered while a subscriber's event buffer is full`() = runBlocking {
        val client = DaemonClient.forTest()
        val stuck = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            client.allEvents.collect { delay(60_000) }
        }
        val waiting = CompletableDeferred<Response>()
        client.pendingForTest[7L] = waiting
        repeat(3000) { client.deliver(event(it)) }
        client.deliver(Response(7L, JsonObject(emptyMap())))
        assertEquals(7L, withTimeout(2_000) { waiting.await() }.id)
        stuck.cancel()
    }
}
