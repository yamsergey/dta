package io.yamsergey.dta.present.agents

import io.yamsergey.dta.protocol.ops.ClientInfo
import io.yamsergey.dta.protocol.ops.ClientKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Feature 073: the Agents tab as the person reads it. IDE-free; the panel only draws this. */
sealed interface AgentsView {
    /** The daemon is not running: this is all the tab says. */
    data object DaemonNotRunning : AgentsView

    data class Running(
        val server: ServerLine,
        val rows: List<AgentRow>,
        /** CLI calls shorter than a second in the last minute, folded out of [rows]. */
        val cliCalls: Int,
        /** Null while the daemon has not said. */
        val redaction: RedactionLine?,
    ) : AgentsView
}

enum class Tone { LIVE, NEUTRAL, FAILED }

/** The agent server: [word] ("Serving"), where ([address]) and what fixes it ([action], a settings link). */
data class ServerLine(val tone: Tone, val word: String, val address: String?, val action: String?)

data class AgentRow(val name: String, val how: String, val howShort: String, val version: String, val since: String)

/** [sample] is in mono: what a masked value looks like. */
data class RedactionLine(val text: String, val sample: String?)

class AgentsTabModel(
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private data class Key(val connectedAtMs: Long, val n: Int)

    private var running = false
    private var clients: List<ClientInfo> = emptyList()
    private var cli: Map<Key, ClientInfo> = emptyMap()
    /** CLI connections the tab has listed (one that was is not counted when it ends). */
    private val listed = HashSet<Key>()
    private val folded = ArrayList<Long>()

    /** The daemon's client list now; null when the daemon is not running. */
    @Synchronized
    fun daemon(now: List<ClientInfo>?) {
        if (now == null) {
            running = false; clients = emptyList(); cli = emptyMap(); listed.clear(); folded.clear()
            return
        }
        running = true
        clients = now
        val next = LinkedHashMap<Key, ClientInfo>()
        val seen = HashMap<Long, Int>()
        now.filter { it.kind == ClientKind.CLI }.forEach { c ->
            val n = seen.merge(c.connectedAtMs, 1, Int::plus)!! - 1
            next[Key(c.connectedAtMs, n)] = c
        }
        // A CLI call that ended without ever being listed was shorter than the fold's second: a count.
        cli.keys.filter { it !in next && it !in listed }.forEach { folded += clock() }
        listed.retainAll(next.keys)
        cli = next
    }

    @Synchronized
    fun view(server: AgentServerState, redaction: Boolean?): AgentsView {
        if (!running) return AgentsView.DaemonNotRunning
        val now = clock()
        val here = (server as? AgentServerState.On)?.url
        val agents = clients.filter { it.kind != ClientKind.IDE && it.kind != ClientKind.CLI }
        val shownCli = cli.filter { (_, c) -> now - c.connectedAtMs >= FOLD_BELOW_MS }.also { listed += it.keys }.values
        val rows = (agents + shownCli).sortedWith(compareBy({ order(it.kind) }, { it.connectedAtMs })).map { row(it, here) }
        folded.removeAll { now - it >= FOLD_WINDOW_MS }
        return AgentsView.Running(serverLine(server), rows, folded.size, redactionLine(redaction))
    }

    private fun order(kind: ClientKind) = when (kind) { ClientKind.MCP_HTTP -> 0; ClientKind.MCP -> 1; else -> 2 }

    private fun row(c: ClientInfo, here: String?): AgentRow {
        val (how, short) = when (c.kind) {
            ClientKind.MCP_HTTP -> if (here != null && c.server == here) "HTTP · this IDE's server" to "HTTP · this IDE" else "HTTP · another IDE's server" to "HTTP · another IDE"
            ClientKind.MCP -> "stdio · dta mcp" to "stdio · dta mcp"
            else -> "CLI" to "CLI"
        }
        val version = if (c.kind == ClientKind.CLI) c.version else c.agentVersion ?: NONE
        val since = Instant.ofEpochMilli(c.connectedAtMs).atZone(zone).toLocalTime().format(HOUR_MINUTE)
        return AgentRow(c.name ?: UNNAMED, how, short, version, since)
    }

    private fun serverLine(state: AgentServerState): ServerLine = when (state) {
        is AgentServerState.On -> ServerLine(Tone.LIVE, "Serving", state.url, null)
        AgentServerState.Off -> ServerLine(Tone.NEUTRAL, "Off", null, "Turn on in Settings…")
        is AgentServerState.Starting -> ServerLine(Tone.NEUTRAL, "Starting", null, null)
        is AgentServerState.Failed -> ServerLine(Tone.FAILED, "Failed", state.reason, if (state.portInUse != null) "Use another port in Settings…" else "Open Settings…")
    }

    private fun redactionLine(on: Boolean?): RedactionLine? = when (on) {
        true -> RedactionLine("Agents see secrets masked, with fingerprints: ", "[redacted len=212 #3f9a2c81d04e]")
        false -> RedactionLine("Agents see secrets as sent.", null)
        null -> null
    }

    companion object {
        const val MASK = "••••••••••••••••"
        const val UNNAMED = "unnamed client"
        const val NONE = "—"
        const val FOLD_BELOW_MS = 1_000L
        const val FOLD_WINDOW_MS = 60_000L
        private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")

        /** The command or configuration as the screen shows it: the credential masked. */
        fun shown(agent: Agent, url: String): String = AgentConfigs.render(agent, url, MASK).text

        /** What Copy command puts in the clipboard. */
        fun toCopy(agent: Agent, url: String, credential: String): String = AgentConfigs.render(agent, url, credential).text
    }
}
