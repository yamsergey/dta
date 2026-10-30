package io.yamsergey.dta.mcp

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.client.DaemonGoneException
import io.yamsergey.dta.client.DaemonLauncher
import io.yamsergey.dta.client.DaemonStartException
import io.yamsergey.dta.client.VersionConflictException
import io.yamsergey.dta.protocol.ops.Selected
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** One daemon connection shared by all tool calls, opened lazily and re-established when it goes away. */
class DaemonConnection(private val connect: suspend () -> DaemonLauncher.Connected, private val log: (String) -> Unit) {
    private val mutex = Mutex()
    private var client: DaemonClient? = null

    /** Set when the daemon cannot be used by this version at all. */
    @Volatile
    var fatal: String? = null
        private set

    suspend fun call(operation: String, params: JsonObject): Selected {
        val current = mutex.withLock {
            client?.takeIf { it.goneReason == null } ?: try {
                connect().also { c -> c.statements.forEach(log) }.client.also { client = it }
            } catch (e: VersionConflictException) {
                fatal = e.message
                throw DaemonGoneException(e.message!!)
            } catch (e: DaemonStartException) {
                throw DaemonGoneException(e.message!!)
            }
        }
        return current.call(operation, params)
    }

    suspend fun close() = mutex.withLock { client?.let { runCatching { it.close() } }; client = null }
}
