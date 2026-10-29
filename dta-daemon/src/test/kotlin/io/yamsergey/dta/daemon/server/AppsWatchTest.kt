package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.protocol.ops.AdbInfo
import io.yamsergey.dta.protocol.ops.AppConnectionEvent
import io.yamsergey.dta.protocol.ops.Catalog
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.ops.DaemonStatus
import io.yamsergey.dta.protocol.ops.DeviceInfo
import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.protocol.ops.VersionIdentity
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `apps.watch` and `app.connection` (feature 009, contracts/daemon-protocol.md). */
class AppsWatchTest {
    private val identity = VersionIdentity("0.10.0", "2026-10-29T10:00:00Z", 1)
    private val home = Files.createTempDirectory("dta-apps-watch").toFile()
    private val state = StateFile(java.io.File(home, "daemon"))
    private val fake = FakeOperations().apply {
        deviceList = listOf(DeviceInfo("emulator-5554", "online"), DeviceInfo("emulator-5556", "online"))
    }
    private val selector = Selector(fake::devices, fake::apps)
    private val server = DaemonServer(
        identity, state, OperationDispatcher(fake, selector), fake, selector,
        status = { clients -> DaemonStatus(identity, 1, 5, home.path, AdbInfo("/fake/adb", true), clients, fake.devices(), emptyList()) },
        onStop = {},
    ).also { it.start() }

    @AfterTest
    fun tearDown() = server.stop(null)

    private fun connect() = runBlocking { DaemonClient.connect(server.port, state.token()!!, ClientKind.CLI, identity) }

    private fun event(device: String, app: String, state: String, reason: String? = null) = AppConnectionEvent(device, app, state, reason)

    private fun decode(data: JsonObject) = DtaJson.decodeFromJsonElement(AppConnectionEvent.serializer(), JsonObject(data - "subscription"))

    @Test
    fun `apps watch is a streaming catalog operation, not an agent tool`() {
        val spec = Catalog["apps.watch"]
        assertTrue(spec.streaming && spec.cliOnly)
        assertEquals(listOf("apps", "watch"), spec.cliPath)
        assertTrue(Catalog.agentTools.none { it.name == "apps.watch" })
    }

    @Test
    fun `first the current apps list, then only the watched device's events`() = runBlocking<Unit> {
        val client = connect()
        val received = async { withTimeout(5_000) { client.appEvents(buildJsonObject { put("device", "emulator-5556") }).take(3).toList() } }
        delay(300)
        fake.appEventFlow.emit(event("emulator-5554", "com.example.shop", "connected"))
        fake.appEventFlow.emit(event("emulator-5556", "com.example.shop", "connected"))
        fake.appEventFlow.emit(event("emulator-5556", "com.example.shop", "lost", "the app's sidekick stopped answering"))
        val all = received.await()
        val first = DtaJson.decodeFromJsonElement(Selected.serializer(), all[0]["selection"]!!)
        val lists = first.result.jsonObject["apps"]!!.jsonArray
        assertEquals(listOf("emulator-5556"), lists.map { it.jsonObject["device"]!!.jsonPrimitive.content })
        assertEquals(
            listOf(event("emulator-5556", "com.example.shop", "connected"), event("emulator-5556", "com.example.shop", "lost", "the app's sidekick stopped answering")),
            all.drop(1).map(::decode),
        )
        client.close()
    }

    @Test
    fun `without a device every device is watched, and an app filter narrows it`() = runBlocking<Unit> {
        val client = connect()
        val everything = async { withTimeout(5_000) { client.appEvents(JsonObject(emptyMap())).take(3).toList() } }
        val oneApp = async { withTimeout(5_000) { client.appEvents(buildJsonObject { put("app", "com.example.other") }).take(2).toList() } }
        delay(300)
        fake.appEventFlow.emit(event("emulator-5554", "com.example.shop", "connected"))
        fake.appEventFlow.emit(event("emulator-5556", "com.example.other", "refused", "sidekick 0.9.40 speaks protocol 0"))
        val all = everything.await()
        assertEquals(2, DtaJson.decodeFromJsonElement(Selected.serializer(), all[0]["selection"]!!).result.jsonObject["apps"]!!.jsonArray.size)
        assertEquals(listOf("emulator-5554", "emulator-5556"), all.drop(1).map { decode(it).device })
        assertEquals(listOf("refused"), oneApp.await().drop(1).map { decode(it).state })
        client.close()
    }
}
