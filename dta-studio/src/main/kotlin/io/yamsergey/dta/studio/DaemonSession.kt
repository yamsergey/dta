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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The IDE's one connection to the shared daemon (research R4, FR-015/016). It starts or joins
 * the daemon, follows a replacement by a newer one, and states every other outcome; it never
 * stops the daemon and never restarts one somebody stopped (constitution II).
 */
class DaemonSession(
    private val connect: suspend () -> DaemonLauncher.Connected,
    private val scope: CoroutineScope,
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

    fun start() {
        job?.cancel()
        mutable.value = State.Starting
        job = scope.launch { run() }
    }

    fun retry() = start()

    private suspend fun run() {
        val connected = try {
            connect()
        } catch (e: VersionConflictException) {
            mutable.value = State.Conflict(e.message!!)
            return
        } catch (e: DaemonStartException) {
            mutable.value = State.Failed(e.message!!)
            return
        } catch (e: Exception) {
            mutable.value = State.Failed("Could not reach the DTA daemon: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        val client = connected.client
        mutable.value = State.Connected(client, client.daemon.identity.display, connected.statements)
        val gone = scope.launch {
            client.allEvents.collect { event ->
                when (event.name) {
                    DaemonEvents.REPLACED -> {
                        val by = runCatching { DtaJson.decodeFromJsonElement(DaemonReplacedEvent.serializer(), event.data!!).by.display }.getOrDefault("a newer version")
                        mutable.value = State.Replaced(by)
                        start()
                    }
                    DaemonEvents.STOPPING -> {
                        val reason = runCatching { DtaJson.decodeFromJsonElement(DaemonStoppingEvent.serializer(), event.data!!).reason }.getOrDefault("no reason given")
                        mutable.value = State.Failed("The DTA daemon stopped: $reason.", action = "Start")
                    }
                }
            }
        }
        gone.join()
    }

    /** Closes this IDE's connection; the daemon keeps running for its other clients. */
    fun dispose() {
        val current = mutable.value
        job?.cancel()
        if (current is State.Connected) runBlocking { runCatching { current.client.close() } }
    }
}
