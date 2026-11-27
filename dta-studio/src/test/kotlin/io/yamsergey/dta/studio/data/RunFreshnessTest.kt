package io.yamsergey.dta.studio.data

import io.yamsergey.dta.protocol.ops.AppConnectionEvent
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.studio.ContextController
import io.yamsergey.dta.studio.DaemonSession
import io.yamsergey.dta.testing.TestDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D-122: a Runtime tab read for one run of the app is stale after the app ran again, with the same device and app chosen. */
class RunFreshnessTest {
    private val daemon = TestDaemon()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = DaemonSession(connect = { daemon.connect(ClientKind.IDE) }, scope = scope)
    private val context = ContextController(session.state, scope)

    @AfterTest fun tearDown() { scope.cancel(); daemon.close() }

    private suspend fun until(check: () -> Boolean) = withTimeout(5_000) { while (!check()) kotlinx.coroutines.delay(20) }
    private suspend fun connection(state: String, app: String = "com.example.shop") {
        withTimeout(5_000) { context.watching.first { it } }
        daemon.ops.appEventFlow.emit(AppConnectionEvent("emulator-5554", app, state))
    }

    @Test
    fun `read for the chosen app it is fresh, the app running again makes it stale, another app's run does not`() = runBlocking<Unit> {
        val told = AtomicInteger()
        val f = RunFreshness(context.appConnections, { context.state.value.device to context.state.value.app }, scope) { told.incrementAndGet() }
        session.start()
        until { context.state.value.app != null && context.watching.value }
        val (device, app) = context.state.value.device to context.state.value.app
        assertTrue(f.stale(device, app), "never read")
        f.mark(device, app)
        assertFalse(f.stale(device, app))
        connection("connected", app = "com.example.other")
        kotlinx.coroutines.delay(300)
        assertFalse(f.stale(device, app), "another app's run is not this app's")
        connection("ended", app!!)
        connection("connected", app)
        until { f.stale(device, app) }
        assertEquals(1, told.get(), "told once")
        f.mark(device, app)
        assertFalse(f.stale(device, app))
    }
}

/** D-122: the Data and Runtime tabs read the app once per run: loaded when shown and stale, also when the app ran again while shown. */
class FreshTabTest {
    private val daemon = TestDaemon()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = DaemonSession(connect = { daemon.connect(ClientKind.IDE) }, scope = scope)
    private val context = ContextController(session.state, scope)

    @AfterTest fun tearDown() { scope.cancel(); daemon.close() }

    private suspend fun until(check: () -> Boolean) = withTimeout(5_000) { while (!check()) kotlinx.coroutines.delay(20) }

    @Test
    fun `it loads when shown and stale, not when hidden, and again when the app ran again while shown`() = runBlocking<Unit> {
        val loads = AtomicInteger()
        var shown = false
        val chosen = { context.state.value.device to context.state.value.app }
        lateinit var tab: FreshTab
        val fresh = RunFreshness(context.appConnections, chosen, scope) { tab.refresh() }
        tab = FreshTab(fresh, { shown }, chosen) { loads.incrementAndGet() }
        session.start()
        until { context.state.value.app != null && context.watching.value }
        tab.refresh()
        assertEquals(0, loads.get(), "hidden: nothing is read")
        shown = true
        tab.refresh()
        assertEquals(1, loads.get(), "shown and never read")
        tab.refresh()
        assertEquals(1, loads.get(), "fresh: not again")
        val app = context.state.value.app!!
        daemon.ops.appEventFlow.emit(AppConnectionEvent("emulator-5554", app, "ended"))
        daemon.ops.appEventFlow.emit(AppConnectionEvent("emulator-5554", app, "connected"))
        until { loads.get() == 2 }
        shown = false
        daemon.ops.appEventFlow.emit(AppConnectionEvent("emulator-5554", app, "connected"))
        kotlinx.coroutines.delay(300)
        assertEquals(2, loads.get(), "hidden when it ran again: read when shown")
        shown = true
        tab.refresh()
        assertEquals(3, loads.get())
    }
}
