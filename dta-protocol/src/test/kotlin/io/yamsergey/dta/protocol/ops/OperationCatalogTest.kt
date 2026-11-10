package io.yamsergey.dta.protocol.ops

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OperationCatalogTest {

    @Test
    fun `every entry is well formed and unique`() {
        val names = Catalog.all.map { it.name }
        assertEquals(names.distinct(), names)
        Catalog.all.forEach { op ->
            assertTrue(Regex("""[a-z]+(\.[a-z]+)+""").matches(op.name), op.name)
            assertTrue(op.description.length > 20, "${op.name} needs a real description")
            op.params.forEach { p ->
                assertTrue(p.description.isNotBlank(), "${op.name}.${p.name}")
                if (p.type == ParamType.ENUM || p.type == ParamType.ENUM_LIST) assertTrue(p.enumValues.isNotEmpty(), "${op.name}.${p.name}")
            }
            assertEquals(op.params.map { it.name }.distinct(), op.params.map { it.name }, op.name)
            assertTrue(op.params.none { it.name == "device" || it.name == "app" }, "device/app come from the scope, not params: ${op.name}")
        }
    }

    @Test
    fun `the 008 operation set plus apps watch (009) and the archive (011) and app data (027)`() {
        assertEquals(
            setOf(
                "devices.list", "apps.list", "apps.watch", "capability.report", "layout.tree",
                "network.list", "network.search", "network.entry", "network.body", "network.clear", "network.export", "network.watch",
                "network.script.set", "network.script.clear", "network.script.status", "network.script.logs",
                "network.ws.push",
                "network.schema.add", "network.schema.list", "network.schema.remove", "network.schema.sync",
                "network.mapping.add", "network.mapping.list", "network.mapping.remove",
                "network.format.add", "network.format.test", "network.format.list", "network.format.remove",
                "network.filters.list", "network.filters.add", "network.filters.remove",
                "links.list", "links.entry", "links.open", "links.verification",
                "archive.list", "archive.delete",
                "timeline.events", "timeline.watch", "timeline.causes", "timeline.sequence",
                "timeline.flow.start", "timeline.enabled", "timeline.flow.end", "timeline.flow.list", "timeline.flow.rename", "timeline.flow.remove",
                "state.current", "state.graph", "state.path",
                "daemon.status", "daemon.stop",
                "data.list", "data.db.schema", "data.db.query", "data.db.exec",
                "data.prefs.read", "data.prefs.set", "data.prefs.remove", "data.proto.read", "data.proto.write",
                "data.files.list", "data.files.read", "data.files.write", "data.files.mkdir", "data.files.delete",
                "runtime.activities", "runtime.navigation", "runtime.navigate", "runtime.back", "runtime.viewmodels",
                "runtime.vm.set", "runtime.saved.set", "runtime.memory", "runtime.threads", "runtime.di",
                "runtime.functions", "runtime.invoke",
            ),
            Catalog.all.map { it.name }.toSet(),
        )
    }

    @Test
    fun `tool names and cli paths`() {
        assertEquals("network_script_set", Catalog["network.script.set"].toolName)
        assertEquals(listOf("network", "script", "set"), Catalog["network.script.set"].cliPath)
        assertEquals(listOf("devices"), Catalog["devices.list"].cliPath)
        assertEquals(listOf("layout"), Catalog["layout.tree"].cliPath)
        assertEquals(listOf("capability"), Catalog["capability.report"].cliPath)
        assertEquals(listOf("network", "list"), Catalog["network.list"].cliPath)
        assertEquals("timeline_events", Catalog["timeline.events"].toolName)
        assertEquals(listOf("timeline", "events"), Catalog["timeline.events"].cliPath)
        assertEquals(listOf("timeline", "watch"), Catalog["timeline.watch"].cliPath)
        assertEquals(listOf("timeline", "causes"), Catalog["timeline.causes"].cliPath)
        assertEquals(listOf("state"), Catalog["state.current"].cliPath)
        assertEquals(listOf("state", "graph"), Catalog["state.graph"].cliPath)
        assertEquals("state_graph", Catalog["state.graph"].toolName)
        assertEquals("network_ws_push", Catalog["network.ws.push"].toolName)
        assertEquals(listOf("network", "ws", "push"), Catalog["network.ws.push"].cliPath)
        val push = Catalog["network.ws.push"]
        assertEquals(listOf("connection", "text", "base64", "waitMs"), push.params.map { it.name })
        assertTrue(push.param("text")!!.positional)
    }

    @Test
    fun `agent tools exclude stop and the watches`() {
        val tools = Catalog.agentTools.map { it.name }
        assertTrue("daemon.stop" !in tools && "network.watch" !in tools && "apps.watch" !in tools && "timeline.watch" !in tools)
        assertEquals(Catalog.all.size - 4, tools.size)
    }

    @Test
    fun `json schema per param type and scope`() {
        val schema = Catalog["network.list"].inputSchema()
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        val props = schema["properties"]!!.jsonObject
        assertEquals("string", props["url"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("integer", props["limit"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("boolean", props["redact"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val kinds = props["kinds"]!!.jsonObject
        assertEquals("array", kinds["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("http", "ws-connection", "ws-message"), kinds["items"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue("device" in props && "app" in props, "app-scoped tools take device and app")

        val entry = Catalog["network.entry"].inputSchema()
        assertEquals(listOf("id"), entry["required"]!!.jsonArray.map { it.jsonPrimitive.content })

        val devices = Catalog["devices.list"].inputSchema()["properties"]!!.jsonObject
        assertTrue("device" !in devices && "app" !in devices)
        val apps = Catalog["apps.list"].inputSchema()["properties"]!!.jsonObject
        assertTrue("device" in apps && "app" !in apps)

        val sources = Catalog["network.schema.add"].inputSchema()["properties"]!!.jsonObject["sources"]!!.jsonObject
        assertEquals("object", sources["type"]!!.jsonPrimitive.content)
        assertEquals("string", sources["additionalProperties"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `selected envelope round trips`() {
        val json = Json.encodeToString(Selected.serializer(), Selected(SelectionStatement("emulator-5554", DeviceRule.ONLY_DEVICE, "com.example", AppRule.FOREGROUND), JsonObject(emptyMap())))
        assertTrue("\"deviceRule\":\"only-device\"" in json && "\"appRule\":\"foreground\"" in json, json)
    }
}
