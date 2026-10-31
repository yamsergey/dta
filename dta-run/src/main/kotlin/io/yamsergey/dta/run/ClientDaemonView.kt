package io.yamsergey.dta.run

import io.yamsergey.dta.client.DaemonCallException
import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.client.DaemonGoneException
import io.yamsergey.dta.client.DaemonLauncher
import io.yamsergey.dta.client.DaemonStartException
import io.yamsergey.dta.client.VersionConflictException
import io.yamsergey.dta.protocol.ops.AppConnectionEvent
import io.yamsergey.dta.protocol.ops.AppList
import io.yamsergey.dta.protocol.ops.DaemonStatus
import io.yamsergey.dta.protocol.ops.DeviceInfo
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The run's view of the shared daemon (research R4), one connection for the whole run:
 * devices by the daemon's rule, the app's connection through `apps.watch`, whether its
 * sidekick is there, and the daemon's adb (the one adb DTA uses, constitution VI).
 */
class ClientDaemonView(private val connect: suspend () -> DaemonLauncher.Connected) : DaemonView, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var client: DaemonClient? = null

    private suspend fun client(): DaemonClient = lock.withLock {
        client ?: try {
            connect().client.also { client = it }
        } catch (e: VersionConflictException) {
            throw DaemonUnavailableException(e.message.orEmpty(), versionConflict = true)
        } catch (e: DaemonStartException) {
            throw DaemonUnavailableException(e.message.orEmpty())
        } catch (e: DaemonGoneException) {
            throw DaemonUnavailableException(e.message.orEmpty())
        }
    }

    private suspend fun call(operation: String, params: Map<String, String> = emptyMap()) = try {
        client().call(operation, JsonObject(params.mapValues { JsonPrimitive(it.value) })).result
    } catch (e: DaemonCallException) {
        throw DaemonUnavailableException(e.message.orEmpty())
    } catch (e: DaemonGoneException) {
        throw DaemonUnavailableException(e.message.orEmpty())
    }

    override suspend fun chooseDevice(serial: String?): DeviceChoice {
        val devices = DtaJson.decodeFromJsonElement(ListSerializer(DeviceInfo.serializer()), call("devices.list"))
        val online = devices.filter { it.state == "online" }
        if (serial != null) {
            val named = online.firstOrNull { it.serial == serial } ?: return DeviceChoice.NotFound(serial, online.map { it.serial })
            return DeviceChoice.Chosen(named.serial, named.model)
        }
        return when (online.size) {
            0 -> DeviceChoice.NoDevice
            1 -> DeviceChoice.Chosen(online.single().serial, online.single().model)
            else -> DeviceChoice.Several(online.map { it.serial })
        }
    }

    override suspend fun watch(serial: String, applicationId: String): ConnectionWatch {
        val events = Channel<ConnectionOutcome>(Channel.UNLIMITED)
        val subscribed = CompletableDeferred<Unit>()
        val daemon = client()
        val job = scope.launch {
            try {
                daemon.appEvents(JsonObject(mapOf("device" to JsonPrimitive(serial), "app" to JsonPrimitive(applicationId)))).collect { data ->
                    // The first item is the subscription with the apps running now; then changes.
                    if (data.containsKey("selection")) {
                        subscribed.complete(Unit)
                        return@collect
                    }
                    val event = runCatching { DtaJson.decodeFromJsonElement(AppConnectionEvent.serializer(), data) }.getOrNull() ?: return@collect
                    if (event.device != serial || event.app != applicationId) return@collect
                    when (event.state) {
                        "connected" -> events.send(ConnectionOutcome.Connected)
                        "refused" -> events.send(ConnectionOutcome.Refused(event.reason ?: "no reason given"))
                    }
                }
            } finally {
                subscribed.complete(Unit)
            }
        }
        withTimeoutOrNull(SUBSCRIBE_TIMEOUT) { subscribed.await() }
        return object : ConnectionWatch {
            override suspend fun next(timeout: Duration): ConnectionOutcome =
                withTimeoutOrNull(timeout) { events.receive() } ?: ConnectionOutcome.TimedOut

            override fun close() = job.cancel()
        }
    }

    override suspend fun sidekickPresent(serial: String, applicationId: String): Boolean =
        DtaJson.decodeFromJsonElement(AppList.serializer(), call("apps.list", mapOf("device" to serial))).apps.any { it.packageName == applicationId }

    /** The adb the daemon resolved, or null when it has none. */
    suspend fun adbPath(): String? = DtaJson.decodeFromJsonElement(DaemonStatus.serializer(), call("daemon.status")).adb.path

    /** Ends the run's connection to the daemon. */
    suspend fun disconnect() {
        scope.cancel()
        lock.withLock {
            client?.let { runCatching { it.close() } }
            client = null
        }
    }

    override fun close() {
        scope.cancel()
    }

    private companion object {
        val SUBSCRIBE_TIMEOUT = 10.seconds
    }
}
