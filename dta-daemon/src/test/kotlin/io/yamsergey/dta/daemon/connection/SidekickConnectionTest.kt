package io.yamsergey.dta.daemon.connection

import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.IdentityReport
import io.yamsergey.dta.protocol.rpc.MethodRegistry
import io.yamsergey.dta.protocol.rpc.PROTOCOL_VERSION
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SidekickConnectionTest {

    private fun registryWithDiagnostics() = MethodRegistry().apply {
        register("echo") { params -> params ?: JsonPrimitive("empty") }
        register("identity") {
            DtaJson.encodeToJsonElement(
                IdentityReport.serializer(),
                IdentityReport(
                    protocolVersion = PROTOCOL_VERSION,
                    sidekickVersion = "0.1.0",
                    packageName = "com.example.fixture",
                    capabilities = listOf(
                        io.yamsergey.dta.protocol.rpc.CapabilityStatus(
                            "channel",
                            io.yamsergey.dta.protocol.rpc.CapabilityStatus.Status.AVAILABLE,
                        ),
                    ),
                ),
            )
        }
        register("hang") { Thread.sleep(1_000); JsonPrimitive("late") }
    }

    @Test
    fun `connects, completes hello, and fetches identity`() = runBlocking {
        TestWsServer(registryWithDiagnostics()).use { server ->
            val connection = SidekickConnection.connect(
                uri = URI("ws://127.0.0.1:${server.port}/"),
                expectedPackage = "com.example.fixture",
            )
            assertEquals("com.example.fixture", connection.hello.packageName)
            assertEquals(PROTOCOL_VERSION, connection.hello.protocolVersion)

            val identity = DtaJson.decodeFromJsonElement(
                IdentityReport.serializer(),
                connection.request("identity"),
            )
            assertEquals("com.example.fixture", identity.packageName)
            assertEquals("channel", identity.capabilities.single().name)
            connection.close()
        }
    }

    @Test
    fun `100 concurrent requests each get their own response`() = runBlocking {
        TestWsServer(registryWithDiagnostics()).use { server ->
            val connection = SidekickConnection.connect(URI("ws://127.0.0.1:${server.port}/"))
            val results = (1..100).map { n ->
                async {
                    n to connection.request("echo", buildJsonObject { put("n", n) })
                }
            }.awaitAll()

            results.forEach { (n, result) ->
                assertEquals(n, result.jsonObject["n"]!!.jsonPrimitive.int, "response cross-matched for $n")
            }
            connection.close()
        }
    }

    @Test
    fun `incompatible server version fails at connect naming both versions with remedy`() = runBlocking {
        TestWsServer(registryWithDiagnostics(), protocolVersion = 99).use { server ->
            val e = assertFailsWith<VersionIncompatibleException> {
                SidekickConnection.connect(URI("ws://127.0.0.1:${server.port}/"))
            }
            assertTrue("$PROTOCOL_VERSION" in e.message!!, e.message)
            assertTrue("99" in e.message!!, e.message)
            assertTrue("rebuild" in e.message!!.lowercase(), "remedy expected: ${e.message}")
        }
    }

    @Test
    fun `wrong package in hello result is a wrong-socket connect failure`() = runBlocking {
        TestWsServer(registryWithDiagnostics(), packageName = "com.somebody.else").use { server ->
            val e = assertFailsWith<ConnectException> {
                SidekickConnection.connect(
                    URI("ws://127.0.0.1:${server.port}/"),
                    expectedPackage = "com.example.fixture",
                )
            }
            assertTrue("com.somebody.else" in e.message!!)
        }
    }

    @Test
    fun `slow request times out with an actionable error and the connection stays usable`() = runBlocking {
        TestWsServer(registryWithDiagnostics()).use { server ->
            val connection = SidekickConnection.connect(URI("ws://127.0.0.1:${server.port}/"))

            assertFailsWith<RequestTimeoutException> {
                connection.request("hang", timeout = 200.milliseconds)
            }
            // The late response for "hang" must be ignored, not crash anything,
            // and subsequent requests keep working.
            val result = connection.request("echo", buildJsonObject { put("ok", 1) }, timeout = 5.seconds)
            assertEquals(1, result.jsonObject["ok"]!!.jsonPrimitive.int)
            connection.close()
        }
    }

    @Test
    fun `sidekick error responses surface code and message`() = runBlocking {
        TestWsServer(registryWithDiagnostics()).use { server ->
            val connection = SidekickConnection.connect(URI("ws://127.0.0.1:${server.port}/"))
            val e = assertFailsWith<SidekickRpcException> {
                connection.request("no_such_method")
            }
            assertTrue("no_such_method" in e.message!!)
            connection.close()
        }
    }
}
