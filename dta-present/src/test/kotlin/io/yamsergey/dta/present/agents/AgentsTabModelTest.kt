package io.yamsergey.dta.present.agents

import io.yamsergey.dta.protocol.ops.ClientInfo
import io.yamsergey.dta.protocol.ops.ClientKind
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 073 B1: what the Agents tab says, from the daemon's client list and the server and redaction states. Deviceless. */
class AgentsTabModelTest {
    private var now = 0L
    private val model = AgentsTabModel(clock = { now }, zone = ZoneOffset.UTC)
    private val server = AgentServerState.On("http://127.0.0.1:7341/mcp")

    private fun at(h: Int, m: Int) = (h * 3600L + m * 60L) * 1000L
    private fun running(redaction: Boolean? = true, state: AgentServerState = server) = assertIs<AgentsView.Running>(model.view(state, redaction))

    private val http = ClientInfo(ClientKind.MCP_HTTP, "0.10.0", at(10, 12), "claude-code", "2.1.330", "http://127.0.0.1:7341/mcp")
    private val stdio = ClientInfo(ClientKind.MCP, "0.10.0", at(9, 58), "claude-code", "2.1.330")
    private val ide = ClientInfo(ClientKind.IDE, "0.10.0", at(9, 0))
    private fun cli(connectedAt: Long) = ClientInfo(ClientKind.CLI, "0.10.0-SNAPSHOT.136", connectedAt, "dta CLI")

    @Test
    fun `a daemon that is not running is the only thing said`() {
        model.daemon(null)
        assertEquals(AgentsView.DaemonNotRunning, model.view(server, true))
        assertEquals(AgentsView.DaemonNotRunning, model.view(AgentServerState.Off, null))
    }

    @Test
    fun `the plugin's own connection is left out and each row says how it is connected`() {
        now = at(10, 20)
        model.daemon(listOf(ide, http, stdio, cli(at(10, 14))))
        now = at(10, 20) + 5_000
        val rows = running().rows
        assertEquals(listOf("claude-code", "claude-code", "dta CLI"), rows.map { it.name })
        assertEquals(listOf("HTTP · this IDE's server", "stdio · dta mcp", "CLI"), rows.map { it.how })
        assertEquals(listOf("HTTP · this IDE", "stdio · dta mcp", "CLI"), rows.map { it.howShort })
        assertEquals(listOf("2.1.330", "2.1.330", "0.10.0-SNAPSHOT.136"), rows.map { it.version })
        assertEquals(listOf("10:12", "09:58", "10:14"), rows.map { it.since })
    }

    @Test
    fun `an HTTP agent of another IDE's server is not marked this IDE, and nor is any when this server is off`() {
        now = at(11, 0)
        val other = http.copy(server = "http://127.0.0.1:9999/mcp", name = "cursor")
        model.daemon(listOf(http, other))
        assertEquals(listOf("HTTP · this IDE's server", "HTTP · another IDE's server"), running().rows.map { it.how })
        assertEquals(listOf("HTTP · this IDE", "HTTP · another IDE"), running().rows.map { it.howShort })
        assertEquals(listOf("HTTP · another IDE's server", "HTTP · another IDE's server"), running(state = AgentServerState.Off).rows.map { it.how })
    }

    @Test
    fun `a client that did not say its name is an unnamed client, with no version of its own`() {
        now = at(11, 0)
        model.daemon(listOf(ClientInfo(ClientKind.MCP, "0.10.0", at(10, 0))))
        val row = running().rows.single()
        assertEquals("unnamed client", row.name)
        assertEquals("—", row.version)
    }

    @Test
    fun `the server line says serving and where, or what is wrong`() {
        now = at(11, 0)
        model.daemon(emptyList())
        assertEquals(ServerLine(Tone.LIVE, "Serving", "http://127.0.0.1:7341/mcp", null), running().server)
        assertEquals(ServerLine(Tone.NEUTRAL, "Off", null, "Turn on in Settings…"), running(state = AgentServerState.Off).server)
        assertEquals(ServerLine(Tone.NEUTRAL, "Starting", null, null), running(state = AgentServerState.Starting(7341)).server)
        assertEquals(ServerLine(Tone.FAILED, "Failed", "port 7341 is in use", "Use another port in Settings…"), running(state = AgentServerState.Failed.portInUse(7341)).server)
        assertEquals(ServerLine(Tone.FAILED, "Failed", "no daemon", "Open Settings…"), running(state = AgentServerState.Failed("no daemon")).server)
    }

    @Test
    fun `no agents connected is an empty list, which the tab says once`() {
        now = at(11, 0)
        model.daemon(listOf(ide))
        assertEquals(emptyList(), running().rows)
        assertEquals(0, running().cliCalls)
    }

    @Test
    fun `a CLI call shorter than a second is folded into a count and never listed`() {
        now = 10_000
        model.daemon(listOf(cli(10_000)))
        now = 10_500
        assertEquals(emptyList(), running().rows)
        model.daemon(emptyList())
        assertEquals(1, running().cliCalls)
        now = 30_000
        assertEquals(1, running().cliCalls)
        now = 10_500 + 60_001
        assertEquals(0, running().cliCalls, "only the last minute")
    }

    @Test
    fun `a CLI connection that lasted is listed while connected, then gone without a count`() {
        now = 10_000
        model.daemon(listOf(cli(10_000)))
        now = 11_200
        assertEquals(listOf("dta CLI"), running().rows.map { it.name })
        now = 12_000
        model.daemon(emptyList())
        assertEquals(emptyList(), running().rows)
        assertEquals(0, running().cliCalls)
    }

    @Test
    fun `a CLI call that came and went between two looks still counts`() {
        now = 10_000
        model.daemon(listOf(cli(10_000)))
        now = 11_100
        model.daemon(emptyList())
        assertEquals(1, running().cliCalls)
    }

    @Test
    fun `three short calls are three`() {
        now = 1_000
        model.daemon(listOf(cli(1_000), cli(1_100), cli(1_200)))
        now = 1_300
        model.daemon(emptyList())
        assertEquals(3, running().cliCalls)
    }

    @Test
    fun `the redaction line follows the setting and is absent until the daemon has said`() {
        now = at(11, 0)
        model.daemon(emptyList())
        val on = running(redaction = true).redaction!!
        assertEquals("Agents see secrets masked, with fingerprints: ", on.text)
        assertTrue(on.sample!!.startsWith("[redacted len="))
        val off = running(redaction = false).redaction!!
        assertEquals("Agents see secrets as sent.", off.text)
        assertNull(off.sample)
        assertNull(running(redaction = null).redaction)
    }

    @Test
    fun `the shown command masks the credential and the copied one holds it`() {
        val credential = "s3cr3t-credential-value"
        for (agent in Agent.entries) {
            val shown = AgentsTabModel.shown(agent, "http://127.0.0.1:7341/mcp")
            assertFalse(credential in shown, agent.title)
            assertTrue(AgentsTabModel.MASK in shown, agent.title)
            val copied = AgentsTabModel.toCopy(agent, "http://127.0.0.1:7341/mcp", credential)
            assertTrue(credential in copied, agent.title)
            assertEquals(shown, copied.replace(credential, AgentsTabModel.MASK), agent.title)
        }
    }
}
