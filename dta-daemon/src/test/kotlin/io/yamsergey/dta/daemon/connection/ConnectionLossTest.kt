package io.yamsergey.dta.daemon.connection

import io.yamsergey.dta.protocol.rpc.MethodRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ConnectionLossTest {

    private fun hangRegistry() = MethodRegistry().apply {
        register("hang") { Thread.sleep(30_000); JsonPrimitive("never") }
        register("ok") { JsonPrimitive("ok") }
    }

    @Test
    fun `severed link fails the in-flight request under 5s with a loss reason`() = runBlocking {
        TestWsServer(hangRegistry()).use { server ->
            val connection = SidekickConnection.connect(URI("ws://127.0.0.1:${server.port}/"))

            supervisorScope {
                val inFlight = async { connection.request("hang", timeout = 60.seconds) }
                delay(200) // request is on the wire and hanging

                val elapsed = measureTimeMillis {
                    server.severConnections()
                    val e = assertFailsWith<ConnectionLostException> { inFlight.await() }
                    assertTrue("disconnected" in e.message!!, "loss must be identified: ${e.message}")
                }
                assertTrue(elapsed < 5_000, "SC-008: took ${elapsed}ms, must be < 5s")
            }
            assertFalse(connection.isOpen)
        }
    }

    @Test
    fun `requests after loss fail immediately with the loss reason, no hang`() = runBlocking {
        TestWsServer(hangRegistry()).use { server ->
            val connection = SidekickConnection.connect(URI("ws://127.0.0.1:${server.port}/"))
            server.severConnections()
            delay(200) // let the client observe the loss

            val elapsed = measureTimeMillis {
                assertFailsWith<ConnectionLostException> { connection.request("ok") }
            }
            assertTrue(elapsed < 1_000, "post-loss request must fail fast, took ${elapsed}ms")
        }
    }

    @Test
    fun `multiple in-flight requests all fail on a single loss`() = runBlocking {
        TestWsServer(hangRegistry()).use { server ->
            val connection = SidekickConnection.connect(URI("ws://127.0.0.1:${server.port}/"))

            supervisorScope {
                val inFlight = (1..10).map { async { connection.request("hang", timeout = 60.seconds) } }
                delay(300)
                server.severConnections()

                inFlight.forEach { deferred ->
                    assertFailsWith<ConnectionLostException> { deferred.await() }
                }
            }
        }
    }
}
