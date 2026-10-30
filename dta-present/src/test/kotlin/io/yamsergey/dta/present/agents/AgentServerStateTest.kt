package io.yamsergey.dta.present.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The agent server's state line (contract agent-server.md §States). */
class AgentServerStateTest {

    @Test
    fun `each state reads as the contract says`() {
        assertEquals("off", AgentServerState.Off.line)
        assertEquals("starting", AgentServerState.Starting(12321).line)
        assertEquals("on — http://127.0.0.1:12321/mcp", AgentServerState.On("http://127.0.0.1:12321/mcp").line)
        assertEquals("failed — port 12321 is in use", AgentServerState.Failed.portInUse(12321).line)
        assertEquals("failed — no daemon", AgentServerState.Failed("no daemon").line)
    }

    @Test
    fun `only a taken port offers another port`() {
        assertEquals("Use another port", AgentServerState.Failed.portInUse(12321).action)
        assertNull(AgentServerState.Failed("no daemon").action)
        assertNull(AgentServerState.On("u").action)
    }
}
