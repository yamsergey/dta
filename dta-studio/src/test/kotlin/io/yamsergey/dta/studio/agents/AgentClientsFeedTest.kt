package io.yamsergey.dta.studio.agents

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.protocol.ops.ClientInfo
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.studio.DaemonSession
import io.yamsergey.dta.testing.TestDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Feature 073 B1: the Agents tab's list follows the daemon's `daemon.clients` and says when there is no daemon. */
class AgentClientsFeedTest {
    private val daemon = TestDaemon()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        daemon.close()
    }

    private suspend fun AgentClientsFeed.await(match: (List<ClientInfo>?) -> Boolean): List<ClientInfo>? = withTimeout(5_000) { clients.first(match) }

    @Test
    fun `follows the daemon's clients as they connect and leave, and is null without a daemon`() = runBlocking<Unit> {
        val ide = daemon.connect(ClientKind.IDE).client
        val session = MutableStateFlow<DaemonSession.State>(DaemonSession.State.Starting)
        val feed = AgentClientsFeed(session, scope)
        assertNull(feed.clients.value, "no daemon yet")

        session.value = DaemonSession.State.Connected(ide, "0.1.0", emptyList())
        feed.await { it != null && it.any { c -> c.kind == ClientKind.IDE } }

        val mcp = daemon.connect(ClientKind.MCP).client
        mcp.request("client.name", buildJsonObject { put("name", "claude-code") })
        val named = feed.await { list -> list?.any { it.name == "claude-code" } == true }!!
        assertEquals(ClientKind.MCP, named.single { it.name == "claude-code" }.kind)

        mcp.close()
        feed.await { list -> list != null && list.none { it.kind == ClientKind.MCP } }

        session.value = DaemonSession.State.Failed("The DTA daemon stopped: test.")
        feed.await { it == null }
    }
}
