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
    /** D-069: where a new connect error is said (the daemon's log). */
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    private class Entry(val connection: AppConnection, val port: Int)
    /** A failed dial: when, why, how many in a row ([streak]) and on which socket of the app. */
    private class Failure(val at: Long, val error: Exception, val streak: Int = 1, val socket: String? = null)

    private val locks = ConcurrentHashMap<ConnectionKey, Mutex>()
    private val entries = ConcurrentHashMap<ConnectionKey, Entry>()
    private val failures = ConcurrentHashMap<ConnectionKey, Failure>()
    private val states = ConcurrentHashMap<ConnectionKey, String>()
    private val lastErrors = ConcurrentHashMap<ConnectionKey, String>()
    private val changeFlow = MutableSharedFlow<AppConnectionEvent>(extraBufferCapacity = 256)

    /** `connected`, `lost`, `refused`, `ended` — each published once per change of state. */
    val changes: SharedFlow<AppConnectionEvent> = changeFlow.asSharedFlow()

    /**
     * [background]: the watcher's connect of an app nobody asked for (D-074); [socket]: its socket's id
     * as discovery saw it.
     */
    suspend fun connect(key: ConnectionKey, background: Boolean = false, socket: String? = null): AppConnection = lockFor(key).withLock {
        entries[key]?.let { entry ->
            if (probe(entry.connection)) return entry.connection
            teardown(key, entry)
            change(key, "lost", "the app's sidekick stopped answering on its channel")
        }

        val previous = failures[key]
        previous?.let { failure ->
            val since = clock() - failure.at
            if (since < cooldown.inWholeMilliseconds) throw failure.error
            // D-074: the watcher's try of an app nobody asked for backs off while the same socket keeps failing.
            val sameSocket = socket == null || failure.socket == null || socket == failure.socket
            if (background && sameSocket && since < backoffMs(failure.streak)) throw failure.error
        }
        fun failure(e: Exception) = Failure(clock(), e, streak = if (previous != null && (socket == null || previous.socket == null || previous.socket == socket)) previous.streak + 1 else 1, socket = socket)

        val port = try {
            forwardOps.forward(key)
        } catch (e: Exception) {
            // "Not running" and "without DTA" are the pre-flight's: cheap to check again, and stale the
            // moment the app starts or its sidekick listens. Cached, a look in the instant between a new
            // process and its socket (the watcher, a view re-subscribing) answered the next query
            // "without DTA" (D-050).
            if (!e.isPreflight()) failures[key] = failure(e) else failures.remove(key)
            failed(key, e)
            throw e
        }

        try {
            val connection = connector(key, port)
            entries[key] = Entry(connection, port)
            failures.remove(key)
            lastErrors.remove(key)
            change(key, "connected", null)
            connection
        } catch (e: Exception) {
            // No half-open state: a failed dial never leaves its forward behind.
            runCatching { forwardOps.remove(key.serial, port) }
            // D-050: a dial its caller cancelled (a subscription gone as the app restarted) says nothing of the
            // app; cached, the next query got "cannot reach sidekick (StandaloneCoroutine was cancelled)".
            if (e !is kotlinx.coroutines.CancellationException) failures[key] = failure(e)
            failed(key, e)
            if (e is VersionIncompatibleException) change(key, "refused", e.message)
            throw e
        }
    }

    /**
     * The connection already held for [key], if it still answers; never dials. A dead link is
     * released (and said so), but no failure is recorded: background work that finds the app
     * gone must not leave behind an error that a query right after the app restarts would get.
     */
    /** Apps connected now, for a watcher that comes after they connected (a client reconnecting to a new daemon). */
    fun connectedKeys(): List<ConnectionKey> = entries.filterValues { it.connection.isOpen }.keys.toList()

    suspend fun held(key: ConnectionKey): AppConnection? = lockFor(key).withLock {
        val entry = entries[key] ?: return null
        if (probe(entry.connection)) return entry.connection
        teardown(key, entry)
        change(key, "lost", "the app's sidekick stopped answering on its channel")
        null
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
        lastErrors.remove(key)
        if (states.containsKey(key)) change(key, "ended", null)
        states.remove(key)
        Unit
    }

    /** D-069: why the last connect to [key] failed; null once connected, or never tried. */
    fun lastError(key: ConnectionKey): String? = lastErrors[key]

    /** D-069: kept for the apps list, and said in the log when it is new (a cached retry is not). */
    private fun failed(key: ConnectionKey, e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) return
        val text = e.message ?: e.javaClass.simpleName
        val before = lastErrors.put(key, text)
        // D-074: each try is on a new forward, so its message names a new port: the same error all the same.
        if (before?.let(::withoutPorts) != withoutPorts(text)) {
            val streak = failures[key]?.streak ?: 1
            runCatching { log("${java.time.Instant.now()} connect ${key.packageName} on ${key.serial} failed: $text" + if (streak > 1) " ($streak in a row)" else "") }
        }
    }

    private fun withoutPorts(text: String) = text.replace(Regex(":\\d+"), ":<port>")

    /** D-074: how long the watcher waits after [streak] failures in a row: 2 s, doubling, at most a minute. */
    private fun backoffMs(streak: Int): Long = (cooldown.inWholeMilliseconds shl (streak - 1).coerceIn(0, 10)).coerceAtMost(MAX_BACKOFF_MS)

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

    /** Not running, or running without a sidekick socket (AppWithoutDtaException is one of these). */
    private fun Exception.isPreflight() = this is io.yamsergey.dta.daemon.adb.AppNotRunningException

    private companion object {
        const val MAX_BACKOFF_MS = 60_000L
    }

    private fun lockFor(key: ConnectionKey): Mutex = locks.computeIfAbsent(key) { Mutex() }
}
