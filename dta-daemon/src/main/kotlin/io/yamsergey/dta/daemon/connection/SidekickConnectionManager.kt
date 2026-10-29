package io.yamsergey.dta.daemon.connection

import io.yamsergey.dta.daemon.discovery.ConnectionKey
import io.yamsergey.dta.protocol.ops.AppConnectionEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The two forward operations the manager owns, injectable for tests. */
class ForwardOps(
    val forward: (ConnectionKey) -> Int,
    val remove: (serial: String, port: Int) -> Unit,
)

/**
 * THE single owner of connection lifecycles (constitution II, FR-010):
 * discovery pre-flight, forward, dial, hello, probe-on-reuse, teardown.
 * Nobody else creates, caches, or destroys connections or forwards.
 * Every state change of a connection is published on [changes] (feature 009, FR-017).
 */
class SidekickConnectionManager(
    private val forwardOps: ForwardOps,
    private val connector: suspend (ConnectionKey, Int) -> AppConnection,
    private val probe: suspend (AppConnection) -> Boolean,
    private val cooldown: Duration = 2.seconds,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val connection: AppConnection, val port: Int)
    private class Failure(val at: Long, val error: Exception)

    private val locks = ConcurrentHashMap<ConnectionKey, Mutex>()
    private val entries = ConcurrentHashMap<ConnectionKey, Entry>()
    private val failures = ConcurrentHashMap<ConnectionKey, Failure>()
    private val states = ConcurrentHashMap<ConnectionKey, String>()
    private val changeFlow = MutableSharedFlow<AppConnectionEvent>(extraBufferCapacity = 256)

    /** `connected`, `lost`, `refused`, `ended` — each published once per change of state. */
    val changes: SharedFlow<AppConnectionEvent> = changeFlow.asSharedFlow()

    suspend fun connect(key: ConnectionKey): AppConnection = lockFor(key).withLock {
        entries[key]?.let { entry ->
            if (probe(entry.connection)) return entry.connection
            teardown(key, entry)
            change(key, "lost", "the app's sidekick stopped answering on its channel")
        }

        failures[key]?.let { failure ->
            if (clock() - failure.at < cooldown.inWholeMilliseconds) throw failure.error
            failures.remove(key)
        }

        val port = try {
            forwardOps.forward(key)
        } catch (e: Exception) {
            failures[key] = Failure(clock(), e)
            throw e
        }

        try {
            val connection = connector(key, port)
            entries[key] = Entry(connection, port)
            change(key, "connected", null)
            connection
        } catch (e: Exception) {
            // No half-open state: a failed dial never leaves its forward behind.
            runCatching { forwardOps.remove(key.serial, port) }
            failures[key] = Failure(clock(), e)
            if (e is VersionIncompatibleException) change(key, "refused", e.message)
            throw e
        }
    }

    suspend fun close(key: ConnectionKey) = lockFor(key).withLock {
        entries.remove(key)?.let { entry ->
            runCatching { entry.connection.close() }
            runCatching { forwardOps.remove(key.serial, entry.port) }
        }
        Unit
    }

    /** The app's process ended: release its link and say so once. */
    suspend fun ended(key: ConnectionKey) = lockFor(key).withLock {
        entries.remove(key)?.let { entry ->
            runCatching { entry.connection.close() }
            runCatching { forwardOps.remove(key.serial, entry.port) }
        }
        failures.remove(key)
        if (states.containsKey(key)) change(key, "ended", null)
        states.remove(key)
        Unit
    }

    /** Keys of the connections currently held open. */
    fun openKeys(): List<ConnectionKey> = entries.filterValues { it.connection.isOpen }.keys.toList()

    suspend fun shutdown() {
        entries.keys.toList().forEach { close(it) }
    }

    private suspend fun teardown(key: ConnectionKey, entry: Entry) {
        entries.remove(key)
        runCatching { entry.connection.close() }
        runCatching { forwardOps.remove(key.serial, entry.port) }
    }

    private fun change(key: ConnectionKey, state: String, reason: String?) {
        if (states.put(key, state) == state) return
        changeFlow.tryEmit(AppConnectionEvent(key.serial, key.packageName, state, reason))
    }

    private fun lockFor(key: ConnectionKey): Mutex = locks.computeIfAbsent(key) { Mutex() }
}
