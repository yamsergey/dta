package io.yamsergey.dta.daemon.connection

import io.yamsergey.dta.protocol.rpc.DiagnosticMethods
import io.yamsergey.dta.protocol.rpc.Event
import io.yamsergey.dta.protocol.rpc.MethodRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class EventDeliveryTest {

    private fun diagnosticsRegistry() = MethodRegistry().also { DiagnosticMethods.install(it) }

    @Test
    fun `events arrive without any request outstanding`() = runBlocking {
        TestWsServer(diagnosticsRegistry()).use { server ->
            val connection = SidekickConnection.connect(URI("ws://127.0.0.1:${server.port}/"))

            // Trigger emission only once the collector is subscribed; the events
            // themselves arrive push-only — no request is outstanding for them.
            val events = withTimeout(5.seconds) {
                connection.events
                    .onSubscription {
                        launch {
                            connection.request(
                                "emit_event",
                                buildJsonObject { put("name", "fixture_tick"); put("count", 3) },
                            )
                        }
                    }
                    .take(3)
                    .toList()
            }

            assertEquals(listOf(1L, 2L, 3L), events.map(Event::seq), "ordered, gap-free")
            assertEquals(listOf(1, 2, 3), events.map { it.data!!.jsonObject["n"]!!.jsonPrimitive.int })
            connection.close()
        }
    }

    @Test
    fun `events and responses are routed independently under load`() = runBlocking {
        TestWsServer(diagnosticsRegistry()).use { server ->
            val connection = SidekickConnection.connect(URI("ws://127.0.0.1:${server.port}/"))

            val received = withTimeout(10.seconds) {
                connection.events
                    .onSubscription {
                        launch {
                            val echoes = (1..30).map { n ->
                                async { n to connection.request("echo", buildJsonObject { put("n", n) }) }
                            }
                            val emits = (1..4).map {
                                async {
                                    connection.request(
                                        "emit_event",
                                        buildJsonObject { put("name", "fixture_tick"); put("count", 5) },
                                    )
                                }
                            }
                            (echoes + emits).awaitAll()
                            echoes.forEach { deferred ->
                                val (n, result) = deferred.getCompleted()
                                assertEquals(n, result.jsonObject["n"]!!.jsonPrimitive.int, "echo cross-matched")
                            }
                        }
                    }
                    .take(20)
                    .toList()
            }

            assertEquals(20, received.size)
            val seqs = received.map(Event::seq)
            assertEquals(seqs.sorted(), seqs, "order preserved")
            assertTrue(seqs.zipWithNext().all { (a, b) -> b == a + 1 }, "gap-free: $seqs")
            connection.close()
        }
    }
}
