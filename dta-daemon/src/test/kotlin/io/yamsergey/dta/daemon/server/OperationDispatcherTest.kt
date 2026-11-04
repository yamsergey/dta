package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.daemon.connection.ConnectException
import io.yamsergey.dta.daemon.connection.SidekickRpcException
import io.yamsergey.dta.daemon.network.BodyNotDecodedException
import io.yamsergey.dta.daemon.network.BodyOutput
import io.yamsergey.dta.daemon.network.BodyResult
import io.yamsergey.dta.daemon.network.proto.BodyMapping
import io.yamsergey.dta.daemon.network.proto.BodyMappingSpec
import io.yamsergey.dta.daemon.network.proto.MappingPart
import io.yamsergey.dta.daemon.network.proto.SchemaInput
import io.yamsergey.dta.daemon.network.proto.SchemaSet
import io.yamsergey.dta.daemon.network.proto.SchemaSource
import io.yamsergey.dta.protocol.layout.CaptureSource
import io.yamsergey.dta.protocol.layout.DetailLevel
import io.yamsergey.dta.protocol.layout.LayoutCapture
import io.yamsergey.dta.protocol.layout.LayoutFilter
import io.yamsergey.dta.protocol.layout.Screen
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.ClearResult
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.DecodingInfo
import io.yamsergey.dta.protocol.network.DecodingOutcome
import io.yamsergey.dta.protocol.network.EntryKind
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkEntryDetail
import io.yamsergey.dta.protocol.network.NetworkEntryEvent
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.Order
import io.yamsergey.dta.protocol.network.ScriptLogPage
import io.yamsergey.dta.protocol.network.ScriptStatus
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.ops.AppInfo
import io.yamsergey.dta.protocol.ops.AppList
import io.yamsergey.dta.protocol.ops.Catalog
import io.yamsergey.dta.protocol.ops.ClientErrorCodes
import io.yamsergey.dta.protocol.ops.DeviceInfo
import io.yamsergey.dta.protocol.rpc.ErrorCodes
import io.yamsergey.dta.protocol.rpc.IdentityReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OperationDispatcherTest {

    private val fake = FakeOperations()
    private val dispatcher = OperationDispatcher(fake, Selector(fake::devices, fake::apps))

    private fun call(method: String, params: JsonObject? = null) = runBlocking { dispatcher.dispatch(method, params) }

    @Test
    fun `results carry the selection`() {
        val selected = call("network.clear")
        assertEquals("emulator-5554", selected.selection!!.device)
        assertEquals("com.example.shop", selected.selection!!.app)
        assertEquals(3, selected.result.jsonObject["clearedEntries"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `flat list params become the library query`() {
        call("network.list", buildJsonObject {
            put("url", "auth"); put("status", "4xx"); put("limit", 5); put("order", "oldest-first"); put("redact", true)
            putJsonArray("kinds") { add("http") }
        })
        val q = fake.lastQuery!!
        assertEquals("auth", q.filter!!.url)
        assertEquals("4xx", q.filter!!.status)
        assertEquals(listOf(EntryKind.HTTP), q.filter!!.kinds)
        assertEquals(5, q.limit)
        assertEquals(Order.OLDEST_FIRST, q.order)
        assertTrue(q.redact)
    }

    @Test
    fun `invalid values are refused naming the parameter`() {
        val e = assertFailsWith<ClientException> { call("network.list", buildJsonObject { put("status", "teapot") }) }
        assertEquals(ErrorCodes.INVALID_PARAMS, e.code)
        assertTrue("status" in e.message!!, e.message)
        val unknown = assertFailsWith<ClientException> { call("network.list", buildJsonObject { put("colour", "red") }) }
        assertTrue("colour" in unknown.message!! && "url" in unknown.message!!, unknown.message)
        val type = assertFailsWith<ClientException> { call("network.list", buildJsonObject { put("limit", "many") }) }
        assertTrue("limit" in type.message!!, type.message)
        val missing = assertFailsWith<ClientException> { call("network.entry") }
        assertTrue("id" in missing.message!!, missing.message)
    }

    @Test
    fun `unknown method`() {
        assertEquals(ErrorCodes.METHOD_NOT_FOUND, assertFailsWith<ClientException> { call("network.teleport") }.code)
    }

    @Test
    fun `layout maps detail and filters, and falls back without an app`() {
        call("layout.tree", buildJsonObject { put("detail", "composition"); put("text", "Pay") })
        assertEquals("com.example.shop", fake.lastLayout!!.first)
        assertEquals(LayoutFilter(text = "Pay"), fake.lastLayout!!.second)
        assertEquals(DetailLevel.COMPOSITION, fake.lastLayout!!.third)
    }

    @Test
    fun `the timeline is read with a cursor, filters and a limit`() {
        call("timeline.events", buildJsonObject {
            put("since", "s:3"); put("sinceMs", 5L); put("limit", 20)
            put("sources", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("ui")) })
            put("kinds", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("ui/toast")) })
        })
        assertEquals(listOf("s:3", 5L, setOf("ui"), setOf("ui/toast"), 20), fake.lastTimeline)
        call("timeline.events")
        assertEquals(listOf(null, null, emptySet<String>(), emptySet<String>(), 100), fake.lastTimeline)
    }

    @Test
    fun `causes take a direction, depth and inferred, and an unknown event is not found`() {
        call("timeline.causes", buildJsonObject { put("event", "7:6"); put("direction", "effects"); put("depth", 3); put("inferred", true) })
        assertEquals(listOf("7:6", io.yamsergey.dta.protocol.timeline.ChainDirection.EFFECTS, 3, true), fake.lastTimeline)
        call("timeline.causes", buildJsonObject { put("event", "7:6") })
        assertEquals(listOf("7:6", io.yamsergey.dta.protocol.timeline.ChainDirection.CAUSES, 8, false), fake.lastTimeline)
        val failure = runCatching { call("timeline.causes", buildJsonObject { put("event", "gone") }) }.exceptionOrNull()
        assertTrue(failure != null, "not found")
    }

    @Test
    fun `state graph takes facets and a minimum hold`() {
        call("state.graph", buildJsonObject {
            put("facets", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("app.cart")) })
            put("minHoldMs", 200L)
        })
        assertEquals(listOf(listOf("app.cart"), 200L, false), fake.lastTimeline)
        call("state.current")
        call("state.path", buildJsonObject { put("from", "items") })
        assertTrue("stateCurrent" in fake.calls && "statePath" in fake.calls)
    }

    @Test
    fun `layout takes the screen only when asked, inline or to a file`() {
        call("layout.tree")
        assertEquals(null, fake.lastScreen)
        call("layout.tree", buildJsonObject { put("screen", true) })
        assertEquals(io.yamsergey.dta.daemon.layout.ScreenRequest(null), fake.lastScreen)
        call("layout.tree", buildJsonObject { put("screenOutput", "/tmp/s.png") })
        assertEquals(io.yamsergey.dta.daemon.layout.ScreenRequest("/tmp/s.png"), fake.lastScreen)
    }

    @Test
    fun `body part defaults by entry kind and output goes to a file`() {
        call("network.body", buildJsonObject { put("id", "r1-1:3") })
        assertEquals(BodyPart.RESPONSE, fake.lastBody!!.first)
        fake.entryKind = EntryKind.WS_MESSAGE
        call("network.body", buildJsonObject { put("id", "r1-1:4"); put("output", "/tmp/b.bin"); put("raw", true) })
        assertEquals(BodyPart.MESSAGE, fake.lastBody!!.first)
        assertEquals(BodyOutput.File("/tmp/b.bin", decoded = false, structured = false), fake.lastBody!!.second)
    }

    @Test
    fun `library failures become client errors with the next step`() {
        fake.entryFailure = SidekickRpcException(ErrorCodes.INVALID_PARAMS, "unknown entry id r1-1:99; it may have been evicted")
        assertEquals(ClientErrorCodes.NOT_FOUND, assertFailsWith<ClientException> { call("network.entry", buildJsonObject { put("id", "r1-1:99") }) }.code)

        fake.bodyFailure = BodyNotDecodedException(DecodingInfo(DecodingOutcome.NOT_DECODED, reason = "no schema maps it"))
        val notDecoded = assertFailsWith<ClientException> { call("network.body", buildJsonObject { put("id", "r1-1:1"); put("part", "response") }) }
        assertEquals(ClientErrorCodes.NOT_AVAILABLE, notDecoded.code)

        fake.bodyFailure = io.yamsergey.dta.daemon.adb.AppNotRunningException("com.android.settings is not running on emulator-5554 — launch it first")
        assertEquals(ClientErrorCodes.NOT_AVAILABLE, assertFailsWith<ClientException> { call("network.body", buildJsonObject { put("id", "r1-1:1"); put("part", "response") }) }.code)

        fake.bodyFailure = ConnectException("cannot reach sidekick")
        val noDta = assertFailsWith<ClientException> { call("network.body", buildJsonObject { put("id", "r1-1:1"); put("part", "response") }) }
        assertEquals(ClientErrorCodes.NOT_AVAILABLE, noDta.code)
        assertTrue("DTA" in noDta.message!!, noDta.message)
    }

    @Test
    fun `a websocket push forwards its params and returns the delivery`() {
        val result = call("network.ws.push", buildJsonObject { put("connection", "r1-1:4"); put("text", "hi"); put("waitMs", 250) })
        assertTrue("push:r1-1:4|hi|null|250" in fake.calls, fake.calls.toString())
        assertEquals("reading-thread", (result.result as kotlinx.serialization.json.JsonObject)["delivery"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        call("network.ws.push", buildJsonObject { put("base64", "AQ==") })
        assertTrue("push:null|null|AQ==|null" in fake.calls, fake.calls.toString())
    }

    @Test
    fun `script and schema inputs are read from paths on this machine`() {
        val dir = Files.createTempDirectory("dta-ops").toFile()
        val script = File(dir, "s.js").apply { writeText("function onRequest(r) { return r }") }
        call("network.script.set", buildJsonObject { put("path", script.absolutePath) })
        assertTrue("script:function onRequest(r) { return r }" in fake.calls)

        File(dir, "proto/fixture/v1").mkdirs()
        File(dir, "proto/order.proto").writeText("syntax = \"proto3\";")
        File(dir, "proto/fixture/v1/common.proto").writeText("syntax = \"proto3\";")
        call("network.schema.add", buildJsonObject {
            put("name", "shop")
            putJsonArray("paths") { add(File(dir, "proto").absolutePath) }
        })
        val sources = (fake.lastSchemaInput as SchemaInput.ProtoSources).files
        assertEquals(setOf("order.proto", "fixture/v1/common.proto"), sources.keys)

        val desc = File(dir, "set.pb").apply { writeBytes(byteArrayOf(10, 2, 1, 2)) }
        call("network.schema.add", buildJsonObject { put("name", "bin"); putJsonArray("paths") { add(desc.absolutePath) } })
        assertTrue(fake.lastSchemaInput is SchemaInput.DescriptorSet)

        val e = assertFailsWith<ClientException> { call("network.script.set", buildJsonObject { }) }
        assertTrue("source" in e.message!! && "path" in e.message!!, e.message)
    }

    @Test
    fun `mapping params`() {
        call("network.mapping.add", buildJsonObject {
            put("urlPattern", "*/proto/order"); put("part", "request"); put("schema", "shop"); put("type", "fixture.v1.Order"); put("method", "POST")
        })
        assertEquals(BodyMappingSpec("*/proto/order", MappingPart.REQUEST, "shop", "fixture.v1.Order", method = "POST"), fake.lastMapping)
        assertEquals(ClientErrorCodes.NOT_FOUND, assertFailsWith<ClientException> { call("network.mapping.remove", buildJsonObject { put("id", "nope") }) }.code)
    }

    @Test
    fun `format rule params (039)`() {
        val added = call("network.format.add", buildJsonObject {
            put("urlPattern", "wss://x/*"); put("name", "orders"); put("pattern", "^(?<kind>\\w+)"); put("idPath", "h.rid"); put("direction", "in"); put("payloadFormat", "text")
        })
        assertEquals("f1", (added.result as kotlinx.serialization.json.JsonObject)["id"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals(ErrorCodes.INVALID_PARAMS, assertFailsWith<ClientException> { call("network.format.add", buildJsonObject { put("urlPattern", "x"); put("direction", "up") }) }.code)
        assertEquals(ClientErrorCodes.NOT_FOUND, assertFailsWith<ClientException> { call("network.format.remove", buildJsonObject { put("id", "nope") }) }.code)
    }

    @Test
    fun `every catalog operation except the server's own has a handler`() {
        val serverOwned = setOf("daemon.status", "daemon.stop", "network.watch", "apps.watch", "timeline.watch")
        assertEquals(Catalog.all.map { it.name }.toSet() - serverOwned, dispatcher.handled)
    }
}
