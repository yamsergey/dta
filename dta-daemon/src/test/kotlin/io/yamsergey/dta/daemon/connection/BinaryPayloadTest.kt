package io.yamsergey.dta.daemon.connection

import io.yamsergey.dta.protocol.rpc.DiagnosticMethods
import io.yamsergey.dta.protocol.rpc.MethodRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BinaryPayloadTest {

    private fun diagnosticsRegistry() = MethodRegistry().also { DiagnosticMethods.install(it) }

    @Test
    fun `blob arrives complete, in order, with announced sizes`() = runBlocking {
        TestWsServer(diagnosticsRegistry()).use { server ->
            val connection = SidekickConnection.connect(URI("ws://127.0.0.1:${server.port}/"))

            val size = 1_000_000
            val full = connection.requestFull("blob", buildJsonObject { put("bytes", size) })

            val combined = full.binaryPayloads.fold(ByteArray(0)) { acc, part -> acc + part }
            assertEquals(size, combined.size)
            assertTrue(full.binaryPayloads.size > 1, "1MB must arrive in multiple chunks")
            // Verify the deterministic pattern end to end (no corruption, order kept).
            combined.forEachIndexed { i, byte ->
                if (byte != (i % 251).toByte()) {
                    throw AssertionError("corrupted at offset $i")
                }
            }
            connection.close()
        }
    }

    @Test
    fun `binary transfer does not corrupt or starve concurrent text traffic`() = runBlocking {
        TestWsServer(diagnosticsRegistry()).use { server ->
            val connection = SidekickConnection.connect(URI("ws://127.0.0.1:${server.port}/"))

            val blob = async { connection.requestFull("blob", buildJsonObject { put("bytes", 2_000_000) }) }
            val echoes = (1..50).map { n ->
                async { n to connection.request("echo", buildJsonObject { put("n", n) }) }
            }

            echoes.awaitAll().forEach { (n, result) ->
                assertEquals(n, result.jsonObject["n"]!!.jsonPrimitive.int, "echo $n corrupted during blob")
            }
            assertEquals(2_000_000L, blob.await().binaryPayloads.sumOf { it.size.toLong() })
            connection.close()
        }
    }

    @Test
    fun `plain requests have no binary payloads`() = runBlocking {
        TestWsServer(diagnosticsRegistry()).use { server ->
            val connection = SidekickConnection.connect(URI("ws://127.0.0.1:${server.port}/"))
            val full = connection.requestFull("echo", buildJsonObject { put("x", 1) })
            assertTrue(full.binaryPayloads.isEmpty())
            assertEquals(1, full.result.jsonObject["x"]!!.jsonPrimitive.int)
            connection.close()
        }
    }
}
