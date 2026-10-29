package io.yamsergey.dta.present.context

import io.yamsergey.dta.present.StatementKind
import io.yamsergey.dta.protocol.ops.AppConnectionEvent
import io.yamsergey.dta.protocol.ops.AppInfo
import io.yamsergey.dta.protocol.ops.AppList
import io.yamsergey.dta.protocol.ops.AppRule
import io.yamsergey.dta.protocol.ops.DeviceInfo
import io.yamsergey.dta.protocol.ops.DeviceRule
import io.yamsergey.dta.protocol.ops.SelectionStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextStateTest {

    private val pixel = DeviceInfo("emulator-5554", "online", "Pixel 8")
    private val shop = AppList("emulator-5554", "com.example.shop", listOf(AppInfo("com.example.shop", true)))

    private fun ready(vararg events: ContextEvent) =
        (listOf(ContextEvent.DaemonConnected("0.10.0 (build 2026-10-29T10:00:00Z)"), ContextEvent.Devices(listOf(pixel)), ContextEvent.Apps(shop)) + events)
            .fold(ContextState()) { s, e -> s.reduce(e) }

    @Test
    fun `one device and the foreground app are chosen automatically and stated`() {
        val state = ready()
        assertEquals("emulator-5554", state.device)
        assertEquals("com.example.shop", state.app)
        assertEquals(listOf(StatementKind.AUTO, StatementKind.AUTO), state.selection.map { it.kind })
        assertEquals(Connection.UNKNOWN, state.connection)
    }

    @Test
    fun `a named choice drops the auto statements`() {
        val state = ready(ContextEvent.AppChosen("com.example.shop"))
        assertEquals(listOf(StatementKind.AUTO), state.selection.map { it.kind }, "the device is still the only one")
        val result = ready(ContextEvent.Selected(SelectionStatement("emulator-5554", DeviceRule.NAMED, "com.example.shop", AppRule.NAMED)))
        assertEquals(emptyList(), result.selection)
    }

    @Test
    fun `app connection events drive the connection, for the chosen app only`() {
        var state = ready(ContextEvent.AppConnection(AppConnectionEvent("emulator-5554", "com.example.shop", "connected")))
        assertEquals(Connection.CONNECTED, state.connection)
        assertNull(state.banner)
        state = state.reduce(ContextEvent.AppConnection(AppConnectionEvent("emulator-5554", "com.example.other", "ended")))
        assertEquals(Connection.CONNECTED, state.connection)
        state = state.reduce(ContextEvent.AppConnection(AppConnectionEvent("emulator-5554", "com.example.shop", "refused", "sidekick 0.9.40 speaks protocol 0, this daemon 1")))
        assertEquals(Connection.REFUSED, state.connection)
        assertTrue("protocol 0" in state.banner!!.text, state.banner!!.text)
        state = state.reduce(ContextEvent.AppConnection(AppConnectionEvent("emulator-5554", "com.example.shop", "ended")))
        assertEquals(Connection.ENDED, state.connection)
        assertTrue("ended" in state.banner!!.text)
    }

    @Test
    fun `a foreground app without DTA names the injection outcome and the next step`() {
        val plain = AppList("emulator-5554", "com.example.plain", emptyList())
        var state = ready(ContextEvent.Apps(plain))
        assertEquals("com.example.plain", state.app)
        assertEquals(Connection.NO_DTA, state.connection)
        assertTrue("Run the app's debug build from Android Studio" in state.banner!!.text, state.banner!!.text)

        state = state.reduce(ContextEvent.Injection(InjectionOutcome.OFF, null))
        assertTrue("Injection is off" in state.banner!!.text, state.banner!!.text)
        state = state.reduce(ContextEvent.Injection(InjectionOutcome.UNRESOLVED, "Could not find io.github.yamsergey:dta-sidekick:0.10.0"))
        assertTrue("Could not find" in state.banner!!.text, state.banner!!.text)
        assertEquals(StatementKind.DEGRADED, state.banner!!.kind)
    }

    @Test
    fun `daemon replacement, conflict and start failure surface with cause and next step`() {
        var state = ready().reduce(ContextEvent.DaemonReplaced("0.10.1 (build 2026-11-01T10:00:00Z)"))
        assertEquals(DaemonPhase.REPLACED, state.daemon.phase)
        assertTrue("0.10.1" in state.banner!!.text && "reconnect" in state.banner!!.text.lowercase(), state.banner!!.text)
        state = state.reduce(ContextEvent.DaemonConnected("0.10.1 (build 2026-11-01T10:00:00Z)"))
        assertEquals(DaemonPhase.CONNECTED, state.daemon.phase)
        assertNull(state.banner)

        val conflict = ready().reduce(ContextEvent.DaemonConflict("The running DTA daemon is 0.11.0, newer than this plugin's 0.10.0. Update the plugin, or stop the daemon with 'dta daemon stop'."))
        assertEquals(DaemonPhase.CONFLICT, conflict.daemon.phase)
        assertEquals(StatementKind.FAILED, conflict.banner!!.kind)
        assertTrue("Update the plugin" in conflict.banner!!.text)

        val failed = ContextState().reduce(ContextEvent.DaemonFailed("The DTA daemon did not start; see /home/.dta/daemon/daemon.log"))
        assertTrue("daemon.log" in failed.banner!!.text)
        assertEquals("Retry", failed.banner!!.action)
    }

    @Test
    fun `changing device or app resets what depended on the old one`() {
        val before = ready(
            ContextEvent.AppConnection(AppConnectionEvent("emulator-5554", "com.example.shop", "connected")),
            ContextEvent.RunSeen("r2-1"),
        )
        assertEquals("r2-1", before.run)
        val after = before.reduce(ContextEvent.AppChosen("com.example.other"))
        assertEquals("com.example.other", after.app)
        assertEquals(Connection.UNKNOWN, after.connection)
        assertNull(after.run)
        assertEquals(before.generation + 1, after.generation, "views re-query on a new generation")

        val noDevice = after.reduce(ContextEvent.Devices(emptyList()))
        assertNull(noDevice.device)
        assertNull(noDevice.app)
        assertTrue("No device" in noDevice.banner!!.text, noDevice.banner!!.text)
    }

    @Test
    fun `several devices wait for a choice and say so`() {
        val other = DeviceInfo("emulator-5556", "online", "Pixel Fold")
        val state = ContextState().reduce(ContextEvent.DaemonConnected("0.10.0")).reduce(ContextEvent.Devices(listOf(pixel, other)))
        assertNull(state.device)
        assertTrue("Choose a device" in state.banner!!.text, state.banner!!.text)
        assertEquals("emulator-5556", state.reduce(ContextEvent.DeviceChosen("emulator-5556")).device)
    }
}
