package io.yamsergey.dta.studio.agents

import io.yamsergey.dta.mcp.PortInUseException
import io.yamsergey.dta.present.agents.AgentServerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** T050: on/off, port and credential, with a stub server. */
class AgentServerControllerTest {

    private class Prefs(override var enabled: Boolean = false, override var port: Int = AgentServerPrefs.DEFAULT_PORT) : AgentServerPrefs

    private class Store(var value: String? = null) : CredentialStore {
        override fun get() = value
        override fun set(value: String) { this.value = value }
    }

    private class Stub(val port: Int, val credential: String, val fail: Exception?) : AgentServer {
        var running = false
        override val url = "http://127.0.0.1:$port/mcp"
        override fun start() { fail?.let { throw it }; running = true }
        override fun stop() { running = false }
    }

    private val created = mutableListOf<Stub>()
    private var failNext: Exception? = null
    private val prefs = Prefs()
    private val store = Store()

    private fun controller() = AgentServerController(prefs, store, factory = { port, credential ->
        Stub(port, credential, failNext.also { failNext = null }).also { created += it }
    }, run = Runnable::run)

    @Test
    fun `off by default on port 12321, nothing started`() {
        val c = controller()
        c.startIfEnabled()
        assertEquals(AgentServerState.Off, c.state.value)
        assertEquals(12321, prefs.port)
        assertTrue(created.isEmpty())
    }

    @Test
    fun `turning it on starts the server with the stored credential and keeps the choice`() {
        val c = controller()
        c.setEnabled(true)
        assertEquals(AgentServerState.On("http://127.0.0.1:12321/mcp"), c.state.value)
        assertTrue(prefs.enabled)
        val credential = store.value!!
        assertTrue(credential.length >= 32, credential)
        assertEquals(credential, created.single().credential)
        c.setEnabled(false)
        assertEquals(AgentServerState.Off, c.state.value)
        assertTrue(!created.single().running)
    }

    @Test
    fun `the credential is generated once and kept`() {
        controller().setEnabled(true)
        val first = store.value
        controller().apply { setEnabled(false); setEnabled(true) }
        assertEquals(first, store.value)
    }

    @Test
    fun `regenerating gives a new credential and restarts a running server with it`() {
        val c = controller()
        c.setEnabled(true)
        val old = store.value
        c.regenerate()
        assertNotEquals(old, store.value)
        assertEquals(store.value, created.last().credential)
        assertTrue(!created.first().running && created.last().running)
    }

    @Test
    fun `a taken port fails with the port and the action, and another port fixes it`() {
        val c = controller()
        failNext = PortInUseException(12321)
        c.setEnabled(true)
        assertEquals(AgentServerState.Failed.portInUse(12321), c.state.value)
        assertEquals("Use another port", c.state.value.action)
        c.setPort(12400)
        assertEquals(AgentServerState.On("http://127.0.0.1:12400/mcp"), c.state.value)
        assertEquals(12400, prefs.port)
    }

    @Test
    fun `any other failure states its cause`() {
        val c = controller()
        failNext = IllegalStateException("no servlet")
        c.setEnabled(true)
        assertEquals(AgentServerState.Failed("no servlet"), c.state.value)
    }

    @Test
    fun `an invalid port is refused without touching the server`() {
        val c = controller()
        c.setEnabled(true)
        c.setPort(70000)
        assertEquals(AgentServerState.Failed("port 70000 is not between 1024 and 65535"), c.state.value)
        assertEquals(12321, prefs.port)
    }

    @Test
    fun `enabled from before starts on startIfEnabled, and dispose stops it`() {
        prefs.enabled = true
        val c = controller()
        c.startIfEnabled()
        assertTrue(created.single().running)
        c.dispose()
        assertTrue(!created.single().running)
    }
}
