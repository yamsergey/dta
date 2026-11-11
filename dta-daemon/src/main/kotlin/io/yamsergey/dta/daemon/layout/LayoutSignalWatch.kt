package io.yamsergey.dta.daemon.layout

import io.yamsergey.dta.daemon.connection.AppConnection
import io.yamsergey.dta.daemon.connection.SidekickRpcException
import io.yamsergey.dta.protocol.layout.LayoutChanged
import io.yamsergey.dta.protocol.layout.ScreenSignal
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.ErrorCodes
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Feature 057: keeps one app's screen signal leased for as long as [watch] is collected — the
 * lease is renewed every [renewMs] and ended when collection stops, so a client that goes away
 * leaves nothing running in the app — and forwards its notices.
 */
class LayoutSignalWatch(
    private val connect: suspend () -> AppConnection,
    private val renewMs: Long = 5_000,
    private val leaseMs: Long = 10_000,
    private val retryMs: Long = 1_000,
) {
    sealed interface Update {
        data class Changed(val change: LayoutChanged) : Update
        data class State(val state: String, val reason: String? = null) : Update
    }

    fun watch(): Flow<Update> = channelFlow {
        var current: AppConnection? = null
        try {
            while (isActive) {
                val app = runCatching { connect() }.getOrNull()
                if (app == null || !app.isOpen) {
                    send(Update.State(ScreenSignal.RECONNECTING, "the app is not reachable"))
                    delay(retryMs)
                    continue
                }
                current = app
                coroutineScope {
                    // Listening before the lease: the first notice after it is not missed.
                    val forward = launch {
                        app.events.filter { it.name == ScreenSignal.APP_EVENT }.collect { e ->
                            e.data?.let { runCatching { DtaJson.decodeFromJsonElement(LayoutChanged.serializer(), it) }.getOrNull() }
                                ?.let { send(Update.Changed(it)) }
                        }
                    }
                    val first = runCatching { lease(app, leaseMs) }
                    val error = first.exceptionOrNull()
                    if (error is SidekickRpcException && error.code == ErrorCodes.METHOD_NOT_FOUND) {
                        forward.cancel()
                        send(Update.State(ScreenSignal.UNSUPPORTED, ScreenSignal.UNSUPPORTED_REASON))
                        awaitCancellation()
                    }
                    if (error == null) {
                        send(Update.State(ScreenSignal.WATCHING))
                        val renew = launch { while (true) { delay(renewMs); runCatching { lease(app, leaseMs) } } }
                        while (app.isOpen) delay(500)
                        renew.cancel()
                    }
                    forward.cancel()
                }
                current = null
                send(Update.State(ScreenSignal.RECONNECTING, "the app is not reachable"))
                delay(retryMs)
            }
        } finally {
            current?.let { app -> withContext(NonCancellable) { runCatching { lease(app, 0) } } }
        }
        awaitClose()
    }

    private suspend fun lease(app: AppConnection, ms: Long) =
        app.request(ScreenSignal.METHOD, buildJsonObject { put("leaseMs", JsonPrimitive(ms)) })
}
