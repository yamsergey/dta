package io.yamsergey.dta.studio

import io.yamsergey.dta.present.context.Connection
import io.yamsergey.dta.present.context.ContextState
import io.yamsergey.dta.present.context.DaemonPhase
import io.yamsergey.dta.protocol.ops.AppConnectionEvent
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.ops.DeviceInfo
import io.yamsergey.dta.testing.TestDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ContextControllerTest {
    private val daemon = TestDaemon()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = DaemonSession(connect = { daemon.connect(ClientKind.IDE) }, scope = scope)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        daemon.close()
    }

    private suspend fun ContextController.await(check: (ContextState) -> Boolean): ContextState =
        withTimeout(5_000) { state.first(check) }

    @Test
    fun `connects, picks the only device and the foreground app, then follows its connection`() = runBlocking<Unit> {
        val controller = ContextController(session.state, scope).also { session.start() }
        val ready = controller.await { it.app != null }
        assertEquals(DaemonPhase.CONNECTED, ready.daemon.phase)
        assertEquals("emulator-5554", ready.device)
        assertEquals("Pixel 8", ready.deviceModel)
        assertEquals("com.example.shop", ready.app)
        withTimeout(5_000) { controller.watching.first { it } }
        daemon.ops.appEventFlow.emit(AppConnectionEvent("emulator-5554", "com.example.shop", "connected"))
        controller.await { it.connection == Connection.CONNECTED }
        daemon.ops.appEventFlow.emit(AppConnectionEvent("emulator-5554", "com.example.shop", "ended"))
        controller.await { it.connection == Connection.ENDED }
    }

    @Test
    fun `after the daemon is started again, the apps are watched on it again`() = runBlocking<Unit> {
        var current = TestDaemon()
        val own = DaemonSession(connect = { current.connect(ClientKind.IDE) }, scope = scope)
        val controller = ContextController(own.state, scope).also { own.start() }
        controller.await { it.app != null }
        withTimeout(5_000) { controller.watching.first { it } }
        current.close()
        controller.await { it.daemon.phase == DaemonPhase.FAILED }
        withTimeout(5_000) { controller.watching.first { !it } }
        current = TestDaemon()
        try {
            own.retry()
            controller.await { it.daemon.phase == DaemonPhase.CONNECTED }
            withTimeout(5_000) { controller.watching.first { it } }
            current.ops.appEventFlow.emit(AppConnectionEvent("emulator-5554", "com.example.shop", "connected"))
            controller.await { it.connection == Connection.CONNECTED }
        } finally {
            current.close()
        }
    }

    @Test
    fun `choosing a device and an app is the person's choice`() = runBlocking<Unit> {
        daemon.ops.deviceList = listOf(DeviceInfo("emulator-5554", "online", "Pixel 8"), DeviceInfo("emulator-5556", "online", "Pixel Fold"))
        val controller = ContextController(session.state, scope).also { session.start() }
        val waiting = controller.await { it.devices != null }
        assertEquals(null, waiting.device)
        controller.chooseDevice("emulator-5556")
        val chosen = controller.await { it.device == "emulator-5556" && it.app != null }
        assertEquals("Pixel Fold", chosen.deviceModel)
        controller.chooseApp("com.example.other")
        val app = controller.await { it.app == "com.example.other" && it.connection == Connection.NO_DTA }
        assertEquals(io.yamsergey.dta.protocol.ops.AppRule.NAMED, app.appRule)
    }

    @Test
    fun `daemon problems reach the context`() = runBlocking<Unit> {
        val failing = DaemonSession(connect = { throw io.yamsergey.dta.client.DaemonStartException("did not start; see daemon.log") }, scope = scope)
        val controller = ContextController(failing.state, scope).also { failing.start() }
        assertEquals("did not start; see daemon.log", controller.await { it.daemon.phase == DaemonPhase.FAILED }.daemon.message)
    }

    @Test
    fun `several projects share the IDE's one daemon connection, each with its own context`() = runBlocking<Unit> {
        val first = ContextController(session.state, scope)
        val second = ContextController(session.state, scope)
        session.start()
        first.await { it.app != null }
        second.await { it.app != null }
        second.chooseApp("com.example.other")
        assertEquals("com.example.shop", first.state.value.app, "one project's choice is not another's")
        val status = first.client!!.call("daemon.status").result as kotlinx.serialization.json.JsonObject
        val kinds = (status["clients"] as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonObject)["kind"].toString().trim('"') }
        assertEquals(listOf("ide"), kinds, "one connection for the whole IDE")
    }

    /**
     * D-038: the daemon connected while the emulator read offline (it hung, or adb was restarting), so the
     * context chose no device. When the device is back and its app connects, the context must find it
     * without a daemon restart.
     */
    @Test
    fun `a device offline when the daemon connected is chosen once it is back and its app connects`() = runBlocking<Unit> {
        daemon.ops.deviceList = listOf(DeviceInfo("emulator-5554", "offline", null))
        val controller = ContextController(session.state, scope).also { session.start() }
        val none = controller.await { it.devices != null }
        assertEquals(null, none.device)
        daemon.ops.deviceList = listOf(DeviceInfo("emulator-5554", "online", "Pixel 8"))
        // The app connects; told again until the context has a watch to hear it (events are not replayed).
        val back = withTimeout(5_000) {
            while (controller.state.value.let { it.device == null || it.app == null }) {
                daemon.ops.appEventFlow.emit(AppConnectionEvent("emulator-5554", "com.example.shop", "connected"))
                kotlinx.coroutines.delay(100)
            }
            controller.state.value
        }
        assertEquals("emulator-5554", back.device)
        assertEquals("com.example.shop", back.app)
        assertEquals("Pixel 8", back.deviceModel)
        controller.await { it.connection == Connection.CONNECTED }
    }
}
