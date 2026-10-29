package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.protocol.network.NetworkFilter
import io.yamsergey.dta.protocol.network.NetworkFilterEval
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.ops.AppConnectionEvent
import io.yamsergey.dta.protocol.ops.AppList
import io.yamsergey.dta.protocol.ops.ClientInfo
import io.yamsergey.dta.protocol.ops.DaemonEvents
import io.yamsergey.dta.protocol.ops.DaemonHello
import io.yamsergey.dta.protocol.ops.DaemonHelloResult
import io.yamsergey.dta.protocol.ops.DaemonMethods
import io.yamsergey.dta.protocol.ops.DaemonReplacedEvent
import io.yamsergey.dta.protocol.ops.DaemonStatus
import io.yamsergey.dta.protocol.ops.DaemonStoppingEvent
import io.yamsergey.dta.protocol.ops.ReplaceParams
import io.yamsergey.dta.protocol.ops.Scope
import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.protocol.ops.VersionIdentity
import io.yamsergey.dta.protocol.network.NetworkEntryEvent
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.ErrorCodes
import io.yamsergey.dta.protocol.rpc.ErrorInfo
import io.yamsergey.dta.protocol.rpc.Event
import io.yamsergey.dta.protocol.rpc.Message
import io.yamsergey.dta.protocol.rpc.Request
import io.yamsergey.dta.protocol.rpc.Response
import io.yamsergey.dta.protocol.wire.WebSocketConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The daemon process's client endpoint (feature 008, contract daemon-protocol): loopback
 * only, bearer token on the upgrade, `daemon.hello` first, then catalog operations through
 * the [dispatcher], live network events per subscription, `daemon.status` / `daemon.stop`
 * / `daemon.replace`. Same WebSocket + JSON-RPC wire as the sidekick channel (research R1).
 */
class DaemonServer(
    private val identity: VersionIdentity,
    private val stateFile: StateFile,
    private val dispatcher: OperationDispatcher,
    private val ops: DaemonOperations,
    private val selector: Selector,
    /** Builds the status from the connected clients. */
    private val status: (clients: List<ClientInfo>) -> DaemonStatus,
    /** Called once when the daemon must stop (stop request, replacement); the owner shuts down. */
    private val onStop: (reason: String) -> Unit,
    private val pid: Long = ProcessHandle.current().pid(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Events buffered per subscription before the oldest are dropped (stated on the next event). */
    private val subscriberBuffer: Int = 256,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap.newKeySet<Session>()
    private val stopping = AtomicBoolean(false)
    private lateinit var serverSocket: ServerSocket
    private lateinit var token: String

    val port: Int get() = serverSocket.localPort

    /** Listens, then publishes `daemon.json` (clients may connect from that moment). */
    fun start() {
        token = stateFile.newToken()
        serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(name = "dta-daemon-accept", isDaemon = true) { acceptLoop() }
        stateFile.write(DaemonState(pid, port, identity.version, identity.build, identity.protocolVersion, clock()))
    }

    /** Tells every client why, closes them and the socket, and removes `daemon.json`. */
    fun stop(reason: String?) {
        if (!stopping.compareAndSet(false, true)) return
        reason?.let { r -> sessions.forEach { it.event(DaemonEvents.STOPPING, DaemonStoppingEvent.serializer(), DaemonStoppingEvent(r)) } }
        sessions.forEach { it.close() }
        runCatching { serverSocket.close() }
        stateFile.deleteIfOwnedBy(pid)
        scope.cancel()
    }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: Exception) {
                return
            }
            thread(name = "dta-daemon-client", isDaemon = true) { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            val session = Session()
            val connection = WebSocketConnection(socket.getInputStream(), socket.getOutputStream(), session, authorize = ::authorized)
            session.connection = connection
            connection.run()
            session.closed()
        }
    }

    private fun authorized(headers: Map<String, String>): Boolean {
        val presented = headers["authorization"]?.removePrefix("Bearer ")?.trim() ?: return false
        return MessageDigest.isEqual(presented.toByteArray(), token.toByteArray())
    }

    private inner class Session : WebSocketConnection.Listener {
        lateinit var connection: WebSocketConnection
        private var hello: DaemonHello? = null
        private var connectedAt = 0L
        private val subscriptions = ConcurrentHashMap<String, Job>()
        /** Device and app of each network subscription, for `network.cleared`. */
        private val networkTargets = ConcurrentHashMap<String, Pair<String, String>>()
        private val seq = AtomicLong(0)
        private val subscriptionIds = AtomicLong(0)

        val info: ClientInfo? get() = hello?.let { ClientInfo(it.client, it.identity.version, connectedAt) }

        override fun onText(connection: WebSocketConnection, text: String) {
            val request = runCatching { DtaJson.decodeFromString(Message.serializer(), text) }.getOrNull() as? Request ?: return
            if (hello == null && request.method != DaemonMethods.HELLO) {
                error(request.id, ErrorCodes.NOT_READY, "Send ${DaemonMethods.HELLO} first")
                return
            }
            when (request.method) {
                DaemonMethods.HELLO -> hello(request)
                DaemonMethods.REPLACE -> replace(request)
                DaemonMethods.NETWORK_SUBSCRIBE -> scope.launch { subscribe(request) }
                DaemonMethods.APPS_SUBSCRIBE -> scope.launch { subscribeApps(request) }
                DaemonMethods.NETWORK_UNSUBSCRIBE, DaemonMethods.APPS_UNSUBSCRIBE -> {
                    val id = (request.params?.get("subscription") as? JsonPrimitive)?.content
                    subscriptions.remove(id)?.cancel()
                    id?.let { networkTargets.remove(it) }
                    respond(request.id, buildJsonObject { put("unsubscribed", id != null) })
                }
                "daemon.status" -> respond(
                    request.id,
                    DtaJson.encodeToJsonElement(Selected.serializer(), Selected(null, DtaJson.encodeToJsonElement(DaemonStatus.serializer(), status(sessions.mapNotNull { it.info })))),
                )
                "daemon.stop" -> {
                    respond(request.id, DtaJson.encodeToJsonElement(Selected.serializer(), Selected(null, buildJsonObject { put("stopping", true) })))
                    requestStop("stopped by a client (${hello?.client?.name?.lowercase()})")
                }
                else -> scope.launch {
                    try {
                        val selected = dispatcher.dispatch(request.method, request.params)
                        respond(request.id, DtaJson.encodeToJsonElement(Selected.serializer(), selected))
                        if (request.method == "network.clear") {
                            val device = selected.selection?.device
                            val app = selected.selection?.app
                            if (device != null && app != null) {
                                val by = hello?.client?.name?.lowercase() ?: "a client"
                                sessions.forEach { it.cleared(device, app, selected.result, by) }
                            }
                        }
                    } catch (e: ClientException) {
                        error(request.id, e.code, e.message ?: "failed", e.data)
                    } catch (e: Exception) {
                        error(request.id, ErrorCodes.INTERNAL, "${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }
        }

        private fun hello(request: Request) {
            val params = request.params?.let { runCatching { DtaJson.decodeFromJsonElement(DaemonHello.serializer(), it) }.getOrNull() }
            if (params == null) {
                error(request.id, ErrorCodes.INVALID_PARAMS, "${DaemonMethods.HELLO} requires {client, identity}")
                return
            }
            hello = params
            connectedAt = clock()
            sessions += this
            respond(request.id, DtaJson.encodeToJsonElement(DaemonHelloResult.serializer(), DaemonHelloResult(identity, pid)))
        }

        private fun replace(request: Request) {
            val by = request.params?.let { runCatching { DtaJson.decodeFromJsonElement(ReplaceParams.serializer(), it) }.getOrNull() }?.by
            if (by == null || VersionIdentity.compare(client = by, daemon = identity) != VersionIdentity.Comparison.CLIENT_NEWER) {
                error(
                    request.id, ErrorCodes.VERSION_INCOMPATIBLE,
                    "Only a newer DTA replaces this daemon (${identity.display}); ${by?.display ?: "no version"} is not newer",
                )
                return
            }
            respond(request.id, buildJsonObject { put("replaced", true) })
            sessions.filter { it !== this }.forEach { it.event(DaemonEvents.REPLACED, DaemonReplacedEvent.serializer(), DaemonReplacedEvent(by)) }
            requestStop("replaced by ${by.display}")
        }

        private suspend fun subscribe(request: Request) {
            val params = request.params ?: JsonObject(emptyMap())
            try {
                val resolution = selector.resolve(
                    Scope.APP,
                    (params["device"] as? JsonPrimitive)?.content,
                    (params["app"] as? JsonPrimitive)?.content,
                )
                val serial = resolution.serial!!
                val pkg = resolution.pkg!!
                val filterKeys = NetworkFilter.serializer().descriptor.let { d -> (0 until d.elementsCount).map { d.getElementName(it) } }
                val filter = DtaJson.decodeFromJsonElement(NetworkFilter.serializer(), JsonObject(params.filterKeys { it in filterKeys }))
                val id = "s${subscriptionIds.incrementAndGet()}"
                val connectionUrls = ConcurrentHashMap<String, String>()
                val eval = NetworkFilterEval(filter, liveRunId = "", connectionUrl = { connectionUrls[it] }, bodyBytes = { _, _ -> null })
                val buffer = Channel<NetworkEntryEvent>(subscriberBuffer)
                val dropped = AtomicBoolean(false)
                val events = ops.networkEvents(serial, pkg)
                val job = scope.launch {
                    launch {
                        for (event in buffer) {
                            val marker = if (dropped.getAndSet(false)) {
                                listOf(DegradationMarker("network", "network-events-dropped: this client fell behind; use network.list with sinceMs to catch up"))
                            } else {
                                null
                            }
                            send(
                                Event(
                                    name = DaemonEvents.NETWORK_ENTRY,
                                    seq = seq.incrementAndGet(),
                                    data = buildJsonObject {
                                        put("subscription", id)
                                        put("device", serial)
                                        put("app", pkg)
                                        put("event", DtaJson.encodeToJsonElement(NetworkEntryEvent.serializer(), event))
                                        marker?.let { put("degraded", DtaJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(DegradationMarker.serializer()), it)) }
                                    },
                                ),
                            )
                        }
                    }
                    try {
                        events.collect { event ->
                            (event.entry as? WsConnectionEntry)?.let { connectionUrls[it.id] = it.url }
                            if (eval.matches(event.entry) != null && buffer.trySend(event).isFailure) dropped.set(true)
                        }
                        event(DaemonEvents.STOPPING, DaemonStoppingEvent.serializer(), DaemonStoppingEvent("event stream of $pkg ended"))
                    } finally {
                        buffer.close()
                    }
                }
                subscriptions[id] = job
                networkTargets[id] = serial to pkg
                job.invokeOnCompletion { networkTargets.remove(id) }
                respond(
                    request.id,
                    DtaJson.encodeToJsonElement(Selected.serializer(), Selected(resolution.statement, buildJsonObject { put("subscription", id) })),
                )
            } catch (e: ClientException) {
                error(request.id, e.code, e.message ?: "failed", e.data)
            } catch (e: Exception) {
                error(request.id, ErrorCodes.INVALID_PARAMS, e.message ?: "invalid subscription")
            }
        }

        /**
         * `apps.watch` (feature 009): the current apps of the watched devices first, then every
         * `app.connection` change on them, narrowed to one app when `app` is given.
         */
        private suspend fun subscribeApps(request: Request) {
            val params = request.params ?: JsonObject(emptyMap())
            try {
                val device = (params["device"] as? JsonPrimitive)?.content
                val app = (params["app"] as? JsonPrimitive)?.content
                val serials = device?.let { listOf(it) } ?: ops.devices().filter { it.state == "online" }.map { it.serial }
                val apps = serials.map { DtaJson.encodeToJsonElement(AppList.serializer(), ops.apps(it)) }
                val id = "s${subscriptionIds.incrementAndGet()}"
                val events = ops.appEvents(device)
                val job = scope.launch {
                    events.collect { e ->
                        if ((device == null || e.device == device) && (app == null || e.app == app)) {
                            val data = DtaJson.encodeToJsonElement(AppConnectionEvent.serializer(), e) as JsonObject
                            send(Event(name = DaemonEvents.APP_CONNECTION, seq = seq.incrementAndGet(), data = JsonObject(data + ("subscription" to JsonPrimitive(id)))))
                        }
                    }
                }
                subscriptions[id] = job
                respond(
                    request.id,
                    DtaJson.encodeToJsonElement(Selected.serializer(), Selected(null, buildJsonObject { put("subscription", id); put("apps", JsonArray(apps)) })),
                )
            } catch (e: ClientException) {
                error(request.id, e.code, e.message ?: "failed", e.data)
            } catch (e: Exception) {
                error(request.id, ErrorCodes.INTERNAL, "${e.javaClass.simpleName}: ${e.message}")
            }
        }

        /** Tells this client's watches of [app] on [device] that its history is gone, so their lists empty too. */
        fun cleared(device: String, app: String, result: JsonElement, by: String) {
            networkTargets.filterValues { it == device to app }.keys.forEach { id ->
                send(Event(name = DaemonEvents.NETWORK_CLEARED, seq = seq.incrementAndGet(), data = buildJsonObject {
                    put("subscription", id)
                    put("device", device)
                    put("app", app)
                    put("cleared", result)
                    put("by", by)
                }))
            }
        }

        fun <T> event(name: String, serializer: KSerializer<T>, data: T) =
            send(Event(name = name, seq = seq.incrementAndGet(), data = DtaJson.encodeToJsonElement(serializer, data)))

        private fun respond(id: Long, result: JsonElement) = send(Response(id = id, result = result))

        private fun error(id: Long, code: Int, message: String, data: JsonObject? = null) =
            send(Response(id = id, error = ErrorInfo(code = code, message = message, details = data)))

        private fun send(message: Message) {
            runCatching { connection.sendText(DtaJson.encodeToString(Message.serializer(), message)) }
        }

        fun close() = runCatching { connection.close(1001, "daemon stopping") }

        fun closed() {
            sessions -= this
            subscriptions.values.forEach { it.cancel() }
            subscriptions.clear()
        }
    }

    private fun requestStop(reason: String) {
        thread(name = "dta-daemon-stop", isDaemon = false) {
            Thread.sleep(100) // let the response reach the requester first
            onStop(reason)
        }
    }
}
