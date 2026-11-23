package io.yamsergey.dta.studio

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.client.DaemonLauncher
import io.yamsergey.dta.client.DaemonStartException
import io.yamsergey.dta.client.VersionConflictException
import io.yamsergey.dta.protocol.ops.DaemonEvents
import io.yamsergey.dta.protocol.ops.DaemonReplacedEvent
import io.yamsergey.dta.protocol.ops.DaemonStoppingEvent
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The IDE's one connection to the shared daemon (research R4, FR-015/016). It starts or joins
 * the daemon, follows a replacement by a newer one, and states every other outcome; it never
 * stops the daemon and never restarts one somebody stopped (constitution II).
 */
class DaemonSession(
    private val connect: suspend () -> DaemonLauncher.Connected,
    private val scope: CoroutineScope,
    /** D-065: a connection to the running daemon whatever its version (null when none runs), to follow one this IDE conflicts with. */
    private val conflicting: (suspend () -> DaemonClient?)? = null,
    /**
     * Whether a daemon's process still runs (D-065: a replaced one is not joined again). The IDE passes
     * the process check; in-process test daemons share the test's pid, so none is waited for by default.
     */
    private val isAlive: (Long) -> Boolean = { false },
) {
    sealed interface State {
        data object Starting : State
        data class Connected(val client: DaemonClient, val version: String, val statements: List<String>) : State
        data class Replaced(val by: String) : State
        data class Conflict(val message: String) : State
        data class Failed(val message: String, val action: String = "Retry") : State
    }

    private val mutable = MutableStateFlow<State>(State.Starting)
    val state: StateFlow<State> = mutable.asStateFlow()
    private var job: Job? = null

    fun start() = start(note = null)

    private fun start(note: String?) {
        job?.cancel()
        if (note == null) mutable.value = State.Starting
        job = scope.launch { run(note) }
    }

    fun retry() = start()

    /** [note] says why this is a reconnection, so the connected state still tells it. */
    private suspend fun run(note: String?) {
        val connected = try {
            connect()
        } catch (e: VersionConflictException) {
            mutable.value = State.Conflict(e.message!!)
            followConflict()
            return
        } catch (e: DaemonStartException) {
            mutable.value = State.Failed(e.message!!)
            return
        } catch (e: Exception) {
            mutable.value = State.Failed("Could not reach the DTA daemon: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        val client = connected.client
        mutable.value = State.Connected(client, client.daemon.identity.display, listOfNotNull(note) + connected.statements)
        ended(client)
    }

    /**
     * D-065: the conflict lasts only while the newer daemon runs. It is followed as any connection is
     * (without using it), so its end is stated: stopped (Start, never restarted behind the person's
     * back), replaced (connected again), or gone (Retry). Without it the conflict banner would outlive it.
     */
    private suspend fun followConflict() {
        val follow = conflicting ?: return
        val client = runCatching { follow() }.getOrNull()
        if (client == null) {
            mutable.value = State.Failed("Lost the connection to the DTA daemon: it is no longer running.")
            return
        }
        // A retry or dispose ends the follow: the newer daemon keeps no connection of this IDE.
        try {
            ended(client)
        } finally {
            withContext(NonCancellable) { runCatching { client.close() } }
        }
    }

    /** One place decides what the end of a connection means: replaced, stopped, or lost. */
    private suspend fun ended(client: DaemonClient) {
        client.awaitGone()
        val ended = client.endedBy
        when (ended?.name) {
            DaemonEvents.REPLACED -> {
                val by = runCatching { DtaJson.decodeFromJsonElement(DaemonReplacedEvent.serializer(), ended.data!!).by.display }.getOrDefault("a newer version")
                mutable.value = State.Replaced(by)
                // The replaced daemon answers until it stops (~100 ms later): joined again, its stop would read "stopped".
                val deadline = System.currentTimeMillis() + REPLACED_EXIT_MS
                while (isAlive(client.daemon.pid) && System.currentTimeMillis() < deadline) delay(50)
                start(note = "the DTA daemon was replaced by $by; reconnected")
            }
            DaemonEvents.STOPPING -> {
                val reason = runCatching { DtaJson.decodeFromJsonElement(DaemonStoppingEvent.serializer(), ended.data!!).reason }.getOrDefault("no reason given")
                mutable.value = State.Failed("The DTA daemon stopped: $reason.", action = "Start")
            }
            else -> mutable.value = State.Failed("Lost the connection to the DTA daemon: ${client.goneReason}.")
        }
    }

    /** Closes this IDE's connection; the daemon keeps running for its other clients. */
    fun dispose() {
        val current = mutable.value
        job?.cancel()
        if (current is State.Connected) runBlocking { runCatching { current.client.close() } }
    }

    private companion object {
        /** Past it the launcher stops a replaced daemon that still runs (DaemonLauncher.retire). */
        const val REPLACED_EXIT_MS = 10_000L
    }
}
