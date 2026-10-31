package io.yamsergey.dta.run

import io.yamsergey.dta.protocol.ops.AppConnectionEvent
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.ops.DeviceInfo
import io.yamsergey.dta.testing.TestDaemon
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** T020: the daemon as the run sees it, against an in-process daemon. */
class ClientDaemonViewTest {
    private val daemon = TestDaemon()
    private val view = ClientDaemonView(connect = { daemon.connect(ClientKind.CLI) })

    @AfterTest
    fun tearDown() {
        runBlocking { view.disconnect() }
        daemon.close()
    }

    @Test
    fun `the only online device is chosen, a named one must be online`() = runBlocking<Unit> {
        assertEquals(DeviceChoice.Chosen("emulator-5554", "Pixel 8"), view.chooseDevice(null))
        assertEquals(DeviceChoice.Chosen("emulator-5554", "Pixel 8"), view.chooseDevice("emulator-5554"))
        assertEquals(DeviceChoice.NotFound("R5CX", listOf("emulator-5554")), view.chooseDevice("R5CX"))
    }

    @Test
    fun `none or several online devices are stated`() = runBlocking<Unit> {
        daemon.ops.deviceList = listOf(DeviceInfo("R5CX", "unauthorized"))
        assertEquals(DeviceChoice.NoDevice, view.chooseDevice(null))
        daemon.ops.deviceList = listOf(DeviceInfo("emulator-5554", "online"), DeviceInfo("R5CX", "online"))
        assertEquals(DeviceChoice.Several(listOf("emulator-5554", "R5CX")), view.chooseDevice(null))
    }

    @Test
    fun `the watch sees connected and refused for its app only, and times out otherwise`() = runBlocking<Unit> {
        view.watch("emulator-5554", "com.example.shop").use { watch ->
            daemon.ops.appEventFlow.emit(AppConnectionEvent("emulator-5554", "com.other", "connected"))
            daemon.ops.appEventFlow.emit(AppConnectionEvent("emulator-5554", "com.example.shop", "ended"))
            daemon.ops.appEventFlow.emit(AppConnectionEvent("emulator-5554", "com.example.shop", "connected"))
            assertEquals(ConnectionOutcome.Connected, watch.next(5.seconds))
            daemon.ops.appEventFlow.emit(AppConnectionEvent("emulator-5554", "com.example.shop", "refused", "versions differ"))
            assertEquals(ConnectionOutcome.Refused("versions differ"), watch.next(5.seconds))
            assertEquals(ConnectionOutcome.TimedOut, watch.next(200.milliseconds))
        }
    }

    @Test
    fun `whether the sidekick is there, and the daemon's adb`() = runBlocking<Unit> {
        assertTrue(view.sidekickPresent("emulator-5554", "com.example.shop"))
        assertFalse(view.sidekickPresent("emulator-5554", "com.other"))
        assertEquals("/sdk/adb", view.adbPath())
    }
}
