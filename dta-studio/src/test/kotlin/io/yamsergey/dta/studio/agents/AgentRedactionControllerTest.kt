package io.yamsergey.dta.studio.agents

import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.testing.TestDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 072 C1: the Secrets setting of Settings > DTA > Agents is the daemon's `agents.redaction`, read and changed over the plugin's connection. */
class AgentRedactionControllerTest {
    private val daemon = TestDaemon()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun tearDown() { scope.cancel(); daemon.close() }

    private val ide = lazy { runBlocking { daemon.connect(ClientKind.IDE).client } }
    private suspend fun daemonSays(): Boolean = daemon.connect(ClientKind.CLI).client.call("agents.redaction").result.jsonObject["redaction"]!!.jsonPrimitive.boolean

    private suspend fun until(check: () -> Boolean) = withTimeout(5_000) { while (!check()) kotlinx.coroutines.delay(20) }

    @Test
    fun `it shows what the daemon holds, on by default`() = runBlocking<Unit> {
        val c = AgentRedactionController({ ide.value }, scope)
        assertNull(c.state.value.on, "nothing known before the first read")
        c.refresh()
        until { c.state.value.on != null }
        assertEquals(true, c.state.value.on)
        assertNull(c.state.value.error)
    }

    @Test
    fun `turning it off and on changes the daemon's setting and shows it`() = runBlocking<Unit> {
        val c = AgentRedactionController({ ide.value }, scope)
        c.set(false)
        until { c.state.value.on == false }
        assertEquals(false, daemonSays())
        c.set(true)
        until { c.state.value.on == true }
        assertEquals(true, daemonSays())
        assertNull(c.state.value.error)
    }

    @Test
    fun `with no daemon it says so and keeps what it showed`() = runBlocking<Unit> {
        val c = AgentRedactionController({ null }, scope, waitMs = 0)
        c.set(false)
        until { c.state.value.error != null }
        assertTrue("daemon" in c.state.value.error!!.lowercase(), c.state.value.error)
        assertNull(c.state.value.on)
    }

    /** D-121: the tab's controller is not the Settings page's; a change made there shows in the tab without reopening it. */
    @Test
    fun `a change made by another client shows while it is watched, and stops showing when the watch ends`() = runBlocking<Unit> {
        val tab = AgentRedactionController({ ide.value }, scope)
        val watch = tab.watch(50)
        until { tab.state.value.on == true }
        val settingsPage = AgentRedactionController({ ide.value }, scope)
        settingsPage.set(false)
        until { tab.state.value.on == false }
        watch.cancel()
        settingsPage.set(true)
        until { settingsPage.state.value.on == true }
        kotlinx.coroutines.delay(300)
        assertEquals(false, tab.state.value.on, "no watch, no read")
    }
}
