package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.connection.SidekickConnection
import io.yamsergey.dta.daemon.connection.SidekickRpcException
import io.yamsergey.dta.daemon.connection.TestWsServer
import io.yamsergey.dta.protocol.network.ScriptClearResult
import io.yamsergey.dta.protocol.network.ScriptLogLevel
import io.yamsergey.dta.protocol.network.ScriptLogLine
import io.yamsergey.dta.protocol.network.ScriptLogPage
import io.yamsergey.dta.protocol.network.ScriptStatus
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import io.yamsergey.dta.protocol.rpc.MethodRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NetworkScriptServiceTest {

    private var lastParams: JsonObject? = null

    private fun registry() = MethodRegistry().apply {
        register("network_script_set") { ctx ->
            lastParams = ctx.params
            val source = ctx.params!!["source"]!!.jsonPrimitive.content
            if ("(" in source && ")" !in source) throw InvalidParamsException("script does not compile: missing ) at line 1, column 9")
            DtaJson.encodeToJsonElement(ScriptStatus.serializer(), ScriptStatus(active = true, id = "abc", installedAtMs = 1, persisted = true))
        }
        register("network_script_clear") { DtaJson.encodeToJsonElement(ScriptClearResult.serializer(), ScriptClearResult(true)) }
        register("network_script_status") { DtaJson.encodeToJsonElement(ScriptStatus.serializer(), ScriptStatus(active = true, id = "abc", source = "s")) }
        register("network_script_logs") { ctx ->
            val since = ctx.params!!["since"]!!.jsonPrimitive.long
            DtaJson.encodeToJsonElement(ScriptLogPage.serializer(), ScriptLogPage(listOf(ScriptLogLine(since, 1, ScriptLogLevel.LOG, message = "m")), since + 1))
        }
    }

    private fun <T> withService(block: suspend (NetworkScriptService) -> T): T = runBlocking {
        TestWsServer(registry()).use { server ->
            val connection = SidekickConnection.connect(URI("ws://127.0.0.1:${server.port}/"))
            try {
                block(NetworkScriptService { connection })
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `set sends the source and returns the status`() = withService { service ->
        assertEquals("abc", service.set("function onRequest(r) {}").id)
        assertEquals("function onRequest(r) {}", lastParams!!["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a compile error comes back as the sidekick's message`() = withService { service ->
        val e = assertFailsWith<SidekickRpcException> { service.set("function (") }
        assertTrue("line 1" in e.message!!, e.message)
    }

    @Test
    fun `clear, status and logs round-trip`() = withService { service ->
        assertEquals(true, service.clear())
        assertEquals("s", service.status().source)
        assertEquals(8, service.logs(since = 7).next)
    }
}
