package io.yamsergey.dta.mcp

import io.yamsergey.dta.client.DaemonCallException
import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 013 T006: the stdio server takes its working folder's schemas once, when a body needs them. */
class AutoSchemaSyncTest {
    private val calls = mutableListOf<Pair<String, JsonObject>>()

    private fun tools(folder: File?) = McpTools(
        call = { op, params ->
            calls += op to params
            when (op) {
                "network.body" -> throw DaemonCallException(-32602, "binary body (application/x-protobuf, 12 B) — fetch the bytes with the output parameter, or add its protobuf schema (network.schema.add, then network.mapping.add) to read it as JSON")
                "network.schema.sync" -> Selected(null, DtaJson.parseToJsonElement("""{"set":{"name":"folder-ab","source":"proto-sources","files":["a.proto"],"types":["shop.v1.Order"],"addedAtMs":1},"unmappedTypes":["shop.v1.Order"]}"""))
                else -> error(op)
            }
        },
        schemaFolder = folder,
    )

    private val args = JsonObject(mapOf("id" to JsonPrimitive("r1-1:1"), "app" to JsonPrimitive("io.example")))

    @Test
    fun `the first binary body syncs the folder once, and says what to map`() = runBlocking<Unit> {
        val t = tools(File("/work/shop"))
        val first = t.invoke("network_body", args)
        assertTrue(first.isError)
        assertTrue("synced the .proto files of /work/shop" in first.text && "shop.v1.Order" in first.text && "network_mapping_add" in first.text, first.text)
        val sync = calls.single { it.first == "network.schema.sync" }.second
        assertEquals("/work/shop", (sync["folder"] as JsonPrimitive).content)
        assertEquals("io.example", (sync["app"] as JsonPrimitive).content)
        t.invoke("network_body", args)
        assertEquals(1, calls.count { it.first == "network.schema.sync" }, "once per session")
    }

    @Test
    fun `without a folder nothing is synced`() = runBlocking<Unit> {
        tools(null).invoke("network_body", args)
        assertEquals(0, calls.count { it.first == "network.schema.sync" })
    }
}
