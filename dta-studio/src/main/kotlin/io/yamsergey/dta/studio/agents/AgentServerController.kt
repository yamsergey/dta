package io.yamsergey.dta.studio.agents

import io.yamsergey.dta.mcp.PortInUseException
import io.yamsergey.dta.present.agents.AgentServerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executor

/** The agent server's choices kept across restarts: off by default, on port 12321 (the original's). */
interface AgentServerPrefs {
    var enabled: Boolean
    var port: Int

    companion object {
        const val DEFAULT_PORT = 12321
    }
}

/** Where the credential lives (the IDE's password safe). */
interface CredentialStore {
    fun get(): String?
    fun set(value: String)
}

/** A running agent server, as [AgentServerController] drives it. */
interface AgentServer {
    val url: String
    fun start()
    fun stop()
}

/**
 * Turns the agent server on and off (FR-018/FR-019): its port, its credential (generated once,
 * regenerable), and the state the tool window shows. Starts and stops run on [run], one at a time.
 */
class AgentServerController(
    private val prefs: AgentServerPrefs,
    private val credentials: CredentialStore,
    private val factory: (port: Int, credential: String) -> AgentServer,
    private val run: Executor,
) {
    private val mutableState = MutableStateFlow<AgentServerState>(AgentServerState.Off)
    val state: StateFlow<AgentServerState> = mutableState.asStateFlow()

    private val lock = Any()
    private var server: AgentServer? = null

    /** The credential agents send, generated the first time it is needed. */
    fun credential(): String = credentials.get() ?: newCredential().also(credentials::set)

    /** At IDE start: on again if it was on. */
    fun startIfEnabled() {
        if (prefs.enabled) restart()
    }

    fun setEnabled(on: Boolean) {
        prefs.enabled = on
        if (on) restart() else run.execute { synchronized(lock) { stopServer(); mutableState.value = AgentServerState.Off } }
    }

    fun setPort(port: Int) {
        if (port !in MIN_PORT..65535) {
            mutableState.value = AgentServerState.Failed("port $port is not between $MIN_PORT and 65535")
            return
        }
        prefs.port = port
        if (prefs.enabled) restart()
    }

    /** A new credential; a running server restarts with it, so the old one stops working. */
    fun regenerate() {
        credentials.set(newCredential())
        if (prefs.enabled) restart()
    }

    fun dispose() = synchronized(lock) { stopServer() }

    private fun restart() {
        val port = prefs.port
        val credential = credential()
        run.execute {
            synchronized(lock) {
                stopServer()
                mutableState.value = AgentServerState.Starting(port)
                val next = factory(port, credential)
                mutableState.value = try {
                    next.start()
                    server = next
                    AgentServerState.On(next.url)
                } catch (e: PortInUseException) {
                    AgentServerState.Failed.portInUse(e.port)
                } catch (e: Exception) {
                    AgentServerState.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private fun stopServer() {
        server?.let { runCatching { it.stop() } }
        server = null
    }

    private fun newCredential(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    companion object {
        /** Ports below are privileged on macOS and Linux. */
        const val MIN_PORT = 1024
    }
}
