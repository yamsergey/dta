package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.protocol.network.EntryChange
import io.yamsergey.dta.protocol.network.Order
import io.yamsergey.dta.protocol.network.RunScope
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.NetworkFilter
import io.yamsergey.dta.protocol.network.NetworkFilterEval
import io.yamsergey.dta.protocol.network.NetworkHistoryView
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.ops.AppConnectionEvent
import io.yamsergey.dta.protocol.ops.AppList
import io.yamsergey.dta.protocol.ops.ClientInfo
import io.yamsergey.dta.protocol.ops.ClientName
import io.yamsergey.dta.protocol.ops.ClientSession
import io.yamsergey.dta.protocol.ops.ClientSessions
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
    /** Events decided and waiting to be sent per subscription; the decisions wait for room (D-040). */
    private val subscriberBuffer: Int = 256,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap.newKeySet<Session>()

    init {
        // D-121: a change of the agents' setting reaches every plugin connection that watches it.
        dispatcher.agents.onChange { on -> sessions.forEach { it.pushRedaction(on) } }
    }
    private val stopping = AtomicBoolean(false)
    private lateinit var serverSocket: ServerSocket
    private lateinit var token: String

    val port: Int get() = serverSocket.localPort

    /** Listens, then publishes `daemon.json` (clients may connect from that moment). */
    fun start() {
        token = stateFile.newToken()
        serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(name = "dta-daemon-accept", isDaemon = true) { acceptLoop() }
        stateFile.write(DaemonState(pid, port, identity.version, identity.build, identity.protocolVersion, clock(), identity.content))
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
        /** Feature 067: this client's live views are its own (another client may use the same names). */
        val viewPrefix = "c${java.util.UUID.randomUUID()}:"

        /** A client's view name made this session's. */
        private fun withView(params: JsonObject?): JsonObject? {
            val view = (params?.get("view") as? JsonPrimitive)?.content ?: return params
            return JsonObject(params + ("view" to JsonPrimitive(viewPrefix + view)))
        }

        /**
         * Feature 072: whether what this connection receives is redacted now: every kind but the plugin's own
         * views, while the agents' setting is on. Read per answer and per event, so a change applies to the next.
         */
        private fun redacts(): Boolean = dispatcher.agents.redactsFor(hello?.client)

        /** What an event to an agent says when redaction is off, so a reader is never left to guess (FR-004). */
        private fun statedOff(): io.yamsergey.dta.protocol.network.RedactionInfo? =
            if (hello?.client != io.yamsergey.dta.protocol.ops.ClientKind.IDE && !dispatcher.agents.redaction) io.yamsergey.dta.protocol.network.RedactionInfo(applied = false, forAgents = true) else null

        /** Feature 073: what this connection says it is; null until hello. */
        @Volatile private var name: String? = null
        @Volatile private var agentVersion: String? = null
        /** Feature 073: the agent sessions an HTTP server reported (replaced by each report), and which server. */
        @Volatile private var reported: List<ClientSession> = emptyList()
        @Volatile private var reportedServer: String? = null
        /** Feature 073: this connection's `daemon.clients` subscriptions. */
        private val clientSubs = ConcurrentHashMap.newKeySet<String>()
        /** D-121: this connection's `agents.redaction.watch` subscriptions. */
        private val redactionSubs = ConcurrentHashMap.newKeySet<String>()

        fun pushRedaction(on: Boolean) {
            redactionSubs.toList().forEach { id ->
                if (subscriptions[id]?.isActive != true) { redactionSubs -= id; return@forEach }
                send(Event(name = DaemonEvents.AGENTS_REDACTION, seq = seq.incrementAndGet(), data = buildJsonObject { put("subscription", id); put("redaction", on) }))
            }
        }

        private fun subscribeRedaction(request: Request) {
            if (hello?.client != io.yamsergey.dta.protocol.ops.ClientKind.IDE) {
                error(request.id, io.yamsergey.dta.protocol.ops.ClientErrorCodes.NOT_AVAILABLE, "${DaemonMethods.AGENTS_REDACTION_SUBSCRIBE} is the Android Studio plugin's: the agents' setting is not for agents to follow")
                return
            }
            val id = "s${subscriptionIds.incrementAndGet()}"
            subscriptions[id] = kotlinx.coroutines.Job()
            redactionSubs += id
            respond(request.id, DtaJson.encodeToJsonElement(Selected.serializer(), Selected(null, buildJsonObject { put("subscription", id); put("redaction", dispatcher.agents.redaction) })))
        }

        /**
         * Feature 073: the client rows this connection stands for. An HTTP server's connection stands for its agent
         * sessions (none when it has none: the server is not an agent); any other, for itself.
         */
        val infos: List<ClientInfo> get() {
            val h = hello ?: return emptyList()
            return if (h.client == io.yamsergey.dta.protocol.ops.ClientKind.MCP_HTTP) {
                reported.map { ClientInfo(h.client, h.identity.version, it.since, it.name, it.version, reportedServer) }
            } else {
                listOf(ClientInfo(h.client, h.identity.version, connectedAt, name ?: h.name, agentVersion))
            }
        }

        fun pushClients() {
            if (clientSubs.isEmpty()) return
            val rows = DtaJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ClientInfo.serializer()), clientInfos())
            clientSubs.toList().forEach { id ->
                if (subscriptions[id]?.isActive != true) { clientSubs -= id; return@forEach }
                send(Event(name = DaemonEvents.CLIENTS, seq = seq.incrementAndGet(), data = buildJsonObject { put("subscription", id); put("clients", rows) }))
            }
        }

        private fun subscribeClients(request: Request) {
            val id = "s${subscriptionIds.incrementAndGet()}"
            subscriptions[id] = kotlinx.coroutines.Job()
            clientSubs += id
            val rows = DtaJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ClientInfo.serializer()), clientInfos())
            respond(request.id, DtaJson.encodeToJsonElement(Selected.serializer(), Selected(null, buildJsonObject { put("subscription", id); put("clients", rows) })))
        }

        private fun clientName(request: Request) {
            val p = request.params?.let { runCatching { DtaJson.decodeFromJsonElement(ClientName.serializer(), it) }.getOrNull() }
            if (p == null || p.name.isBlank()) {
                error(request.id, ErrorCodes.INVALID_PARAMS, "${DaemonMethods.CLIENT_NAME} requires {name}")
                return
            }
            name = p.name
            agentVersion = p.version
            respond(request.id, buildJsonObject { put("named", true) })
            clientsChanged()
        }

        private fun clientSessions(request: Request) {
            val p = request.params?.let { runCatching { DtaJson.decodeFromJsonElement(ClientSessions.serializer(), it) }.getOrNull() }
            if (p == null) {
                error(request.id, ErrorCodes.INVALID_PARAMS, "${DaemonMethods.CLIENT_SESSIONS} requires {sessions: [{name, version, since}]}")
                return
            }
            reported = p.sessions
            if (p.server != null) reportedServer = p.server
            respond(request.id, buildJsonObject { put("sessions", p.sessions.size) })
            clientsChanged()
        }

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
                DaemonMethods.TIMELINE_SUBSCRIBE -> scope.launch { subscribeTimeline(request) }
                DaemonMethods.LAYOUT_SUBSCRIBE -> scope.launch { subscribeLayout(request) }
                DaemonMethods.SELECTION_SUBSCRIBE -> scope.launch { subscribeSelection(request) }
                DaemonMethods.CLIENTS_SUBSCRIBE -> subscribeClients(request)
                DaemonMethods.AGENTS_REDACTION_SUBSCRIBE -> subscribeRedaction(request)
                DaemonMethods.CLIENT_NAME -> clientName(request)
                DaemonMethods.CLIENT_SESSIONS -> clientSessions(request)
                DaemonMethods.NETWORK_FIND -> scope.launch { find(request) }
                DaemonMethods.NETWORK_FIND_MORE -> {
                    val id = (request.params?.get("stream") as? JsonPrimitive)?.content
                    val n = (request.params?.get("n") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                    val run = id?.let { finds[it] }
                    run?.second?.more(n)
                    respond(request.id, buildJsonObject { put("more", run != null) })
                }
                DaemonMethods.NETWORK_FIND_STOP -> {
                    val id = (request.params?.get("stream") as? JsonPrimitive)?.content
                    val run = id?.let { finds[it] }
                    if (run != null) { stopReasons.putIfAbsent(id, io.yamsergey.dta.protocol.network.StopReason.STOP); run.first.cancel() }
                    respond(request.id, buildJsonObject { put("stopped", run != null) })
                }
                DaemonMethods.REQUEST_CANCEL -> {
                    val id = (request.params?.get("id") as? JsonPrimitive)?.content?.toLongOrNull()
                    val call = id?.let { calls[it] }
                    if (call != null) { cancelled += id; call.cancel() }
                    respond(request.id, buildJsonObject { put("cancelled", call != null) })
                }
                DaemonMethods.NETWORK_UNSUBSCRIBE, DaemonMethods.APPS_UNSUBSCRIBE, DaemonMethods.TIMELINE_UNSUBSCRIBE, DaemonMethods.LAYOUT_UNSUBSCRIBE, DaemonMethods.SELECTION_UNSUBSCRIBE, DaemonMethods.CLIENTS_UNSUBSCRIBE, DaemonMethods.AGENTS_REDACTION_UNSUBSCRIBE -> {
                    val id = (request.params?.get("subscription") as? JsonPrimitive)?.content
                    subscriptions.remove(id)?.cancel()
                    id?.let { networkTargets.remove(it) }
                    respond(request.id, buildJsonObject { put("unsubscribed", id != null) })
                }
                "daemon.status" -> respond(
                    request.id,
                    DtaJson.encodeToJsonElement(Selected.serializer(), Selected(null, DtaJson.encodeToJsonElement(DaemonStatus.serializer(), status(clientInfos())))),
                )
                "daemon.stop" -> {
                    respond(request.id, DtaJson.encodeToJsonElement(Selected.serializer(), Selected(null, buildJsonObject { put("stopping", true) })))
                    requestStop("stopped by a client (${hello?.client?.name?.lowercase()})")
                }
                else -> scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    try {
                        val params = if (request.method == "network.list" || request.method == "network.search") withView(request.params) else request.params
                        val selected = dispatcher.dispatch(request.method, params, hello?.client)
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
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Feature 071: cancelled by its client (request.cancel); a closed session hears nothing.
                        if (cancelled.remove(request.id)) error(request.id, io.yamsergey.dta.protocol.ops.ClientErrorCodes.CANCELLED, "cancelled by the client")
                        throw e
                    } catch (e: Exception) {
                        error(request.id, ErrorCodes.INTERNAL, "${e.javaClass.simpleName}: ${e.message}")
                    }
                }.also { job ->
                    calls[request.id] = job
                    job.invokeOnCompletion { calls.remove(request.id) }
                    job.start()
                }
            }
        }

        /** Feature 071: this client's running calls (for request.cancel and its going away). */
        private val calls = ConcurrentHashMap<Long, Job>()
        private val cancelled = ConcurrentHashMap.newKeySet<Long>()

        /** Feature 071: this client's streamed searches, with their credit, and why each was stopped. */
        private val finds = ConcurrentHashMap<String, Pair<Job, io.yamsergey.dta.daemon.network.Credit>>()
        private val stopReasons = ConcurrentHashMap<String, io.yamsergey.dta.protocol.network.StopReason>()

        /**
         * `network.find` (feature 071, contracts/find.md): answers the stream's id at once, then
         * `network.found` events — waiting, searching with rows, and one final state. Nothing follows it.
         */
        private suspend fun find(request: Request) {
            val params = request.params ?: JsonObject(emptyMap())
            try {
                val resolution = selector.resolve(Scope.APP, (params["device"] as? JsonPrimitive)?.content, (params["app"] as? JsonPrimitive)?.content)
                val serial = resolution.serial!!
                val pkg = resolution.pkg!!
                val limit = (params["limit"] as? JsonPrimitive)?.content?.let { it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: throw ClientException(ErrorCodes.INVALID_PARAMS, "limit must be a number of rows, 1 or more; was $it") }
                val window = (params["window"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceAtLeast(1) ?: FIND_WINDOW
                val query = try {
                    dispatcher.networkQuery(JsonObject(params - "device" - "app" - "limit" - "window" - "view"), redacts())
                } catch (e: io.yamsergey.dta.protocol.rpc.InvalidParamsException) {
                    throw ClientException(ErrorCodes.INVALID_PARAMS, e.message ?: "invalid query")
                }
                val id = "f${subscriptionIds.incrementAndGet()}"
                val credit = io.yamsergey.dta.daemon.network.Credit(window)
                fun found(e: io.yamsergey.dta.protocol.network.FoundEvent) = event(DaemonEvents.NETWORK_FOUND, io.yamsergey.dta.protocol.network.FoundEvent.serializer(), e)
                val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    val st = io.yamsergey.dta.protocol.network.FindState.SEARCHING
                    try {
                        val r = ops.networkFind(serial, pkg, query, limit, credit,
                            waitingAt = { position -> found(io.yamsergey.dta.protocol.network.FoundEvent(id, io.yamsergey.dta.protocol.network.FindState.WAITING, position = position)) },
                            started = { scope, saved -> found(io.yamsergey.dta.protocol.network.FoundEvent(id, st, scope = scope, saved = saved)) },
                            send = { items -> found(io.yamsergey.dta.protocol.network.FoundEvent(id, st, items = items)) },
                        )
                        val state = if (r.end.next != null) io.yamsergey.dta.protocol.network.FindState.LIMIT else io.yamsergey.dta.protocol.network.FindState.DONE
                        found(io.yamsergey.dta.protocol.network.FoundEvent(id, state, next = r.end.next, notSearched = r.end.notSearched, scope = r.scope, saved = r.saved, redaction = r.redaction?.copy(forAgents = true) ?: statedOff()))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        val reason = stopReasons.remove(id) ?: io.yamsergey.dta.protocol.network.StopReason.CANCELLED
                        if (reason != io.yamsergey.dta.protocol.network.StopReason.CLOSED) {
                            found(io.yamsergey.dta.protocol.network.FoundEvent(id, io.yamsergey.dta.protocol.network.FindState.STOPPED, reason = reason))
                        }
                        throw e
                    } catch (e: ClientException) {
                        found(io.yamsergey.dta.protocol.network.FoundEvent(id, io.yamsergey.dta.protocol.network.FindState.FAILED, error = e.message))
                    } catch (e: Exception) {
                        found(io.yamsergey.dta.protocol.network.FoundEvent(id, io.yamsergey.dta.protocol.network.FindState.FAILED, error = e.message ?: e.javaClass.simpleName))
                    } finally {
                        finds.remove(id)
                    }
                }
                finds[id] = job to credit
                respond(request.id, DtaJson.encodeToJsonElement(Selected.serializer(), Selected(resolution.statement, buildJsonObject { put("stream", id) })))
                // Started after the answer: the client knows its stream before the first event.
                job.start()
            } catch (e: ClientException) {
                error(request.id, e.code, e.message ?: "failed", e.data)
            } catch (e: Exception) {
                error(request.id, ErrorCodes.INVALID_PARAMS, e.message ?: "invalid search")
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
            clientsChanged()
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
                val given = DtaJson.decodeFromJsonElement(NetworkFilter.serializer(), JsonObject(params.filterKeys { it in filterKeys }))
                // Feature 040: the query line's filters apply to new entries too; its search words hide nothing.
                // Feature 043: and the app's saved filters, as in a list, unless the call ignores them.
                val ignoreSaved = (params["ignoreSavedFilters"] as? JsonPrimitive)?.content == "true"
                val typed = (params["q"] as? JsonPrimitive)?.content
                val line = if (ignoreSaved) typed else io.yamsergey.dta.protocol.network.SavedFilters.merge(ops.networkFilters(pkg).saved, typed).q
                val filter = io.yamsergey.dta.protocol.network.NetworkQuery(filter = given, q = line).resolved().first ?: NetworkFilter()
                // Feature 067: a live view's subscription decides each entry with the whole line, search words included.
                val view = (params["view"] as? JsonPrimitive)?.content?.let { viewPrefix + it }
                // Feature 072: the connection decides (see redacts()); read again for each event.
                // Feature 071: the switch scopes live decisions too (off: entries of the app's current run only).
                val archive = (params["archive"] as? JsonPrimitive)?.content == "true"
                // Decision 2026-11-22: typo tolerance only when asked (typos, or typos:on in the line).
                val typos = (params["typos"] as? JsonPrimitive)?.content == "true"
                // D-085: a view that counts the run (the plugin's caption M) is told of new entries it does not list.
                val counts = (params["counts"] as? JsonPrimitive)?.content == "true"
                val liveQuery = io.yamsergey.dta.protocol.network.NetworkQuery(filter = given.takeIf { it != NetworkFilter() }, q = line, redact = redacts(), ignoreSavedFilters = true, archive = archive, typos = typos)
                /** Entries decided by a body's preview: decided again once the body is copied here. */
                val byPreview = ConcurrentHashMap.newKeySet<String>()
                val id = "s${subscriptionIds.incrementAndGet()}"
                val connectionUrls = ConcurrentHashMap<String, String>()
                // Body filters on live events: an event carries only the preview, so a body the preview
                // is all of is matched here; any other is passed marked, and the client lists again
                // (the list matches the host copy). Neither dropped nor let through silently.
                // Feature 071: no time limit on a pattern; a body not whole in the preview leaves the event unchecked.
                var unchecked = false
                // Feature 071: decisions run in their own coroutine (below); a runaway one stops with its subscription.
                var decisionStop: io.yamsergey.dta.protocol.network.PatternStop = io.yamsergey.dta.protocol.network.PatternStop.NONE
                fun eval(redact: Boolean) = NetworkFilterEval(filter, liveRunId = "", connectionUrl = { connectionUrls[it] }, check = { if (decisionStop.stopped()) throw io.yamsergey.dta.protocol.network.SearchStoppedException() }, redact = redact, bodyBytes = { entry, part ->
                    NetworkHistoryView.bodyOf(entry, part)?.wholeText()?.let { NetworkFilterEval.BodyBytes(it.toByteArray(Charsets.UTF_8), true) }
                        ?: run { unchecked = true; null }
                })
                val buffer = Channel<Pair<NetworkEntryEvent, Any>>(subscriberBuffer)
                val dropped = AtomicBoolean(false)
                // history=current: the running process's entries first, then the live ones.
                val history = (params["history"] as? JsonPrimitive)?.content == "current"
                val subscribed = kotlinx.coroutines.CompletableDeferred<Unit>()
                val events = ops.networkEvents(serial, pkg) { subscribed.complete(Unit) }
                val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    launch {
                        // D-085 (gate 7): what a run holds now, read after the entry was stored: the counting view takes it
                        // as is, so an entry its list already counted (stored, listed, then told) is not counted twice.
                        fun runEntries(runs: Collection<String>) = runs.mapNotNull { r -> runCatching { ops.networkRunEntries(serial, pkg, r) }.getOrNull()?.let { r to it } }
                            .takeIf { it.isNotEmpty() }?.let { found -> buildJsonObject { found.forEach { (r, n) -> put(r, n) } } }
                        // D-085: new entries the view does not list, per run, said at once or with the next event.
                        val counted = LinkedHashMap<String, Long>()
                        suspend fun flushCounted() {
                            if (counted.isEmpty()) return
                            val runs = counted.toMap().also { counted.clear() }
                            send(Event(name = DaemonEvents.NETWORK_ENTRY, seq = seq.incrementAndGet(), data = buildJsonObject {
                                put("subscription", id)
                                put("device", serial)
                                put("app", pkg)
                                put("counted", buildJsonObject { runs.forEach { (run, n) -> put(run, n) } })
                                runEntries(runs.keys)?.let { put("runEntries", it) }
                            }))
                        }
                        for ((event, mark) in buffer) {
                            if (mark == COUNTED) {
                                counted.merge(event.entry.runId, 1L, Long::plus)
                                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                                if (buffer.isEmpty) flushCounted()
                                continue
                            }
                            flushCounted()
                            val bodyUnchecked = mark == true
                            val decision = mark as? io.yamsergey.dta.protocol.network.EntryDecision
                            val left = mark == LEFT
                            val marker = if (dropped.getAndSet(false)) {
                                listOf(DegradationMarker("network", "network-events-dropped: this client fell behind; use network.list with sinceMs to catch up"))
                            } else {
                                null
                            }
                            // Decided on the entry as stored (search and patterns redact as they read); sent as network.list shows it.
                            val (shown, redaction) = if (redacts()) io.yamsergey.dta.protocol.network.Redactor.entry(event.entry).let { (e, info) -> e to info.copy(forAgents = true) } else event.entry to statedOff()
                            send(
                                Event(
                                    name = DaemonEvents.NETWORK_ENTRY,
                                    seq = seq.incrementAndGet(),
                                    data = buildJsonObject {
                                        put("subscription", id)
                                        put("device", serial)
                                        put("app", pkg)
                                        put("event", DtaJson.encodeToJsonElement(NetworkEntryEvent.serializer(), event.copy(entry = shown)))
                                        redaction?.let { put("redaction", DtaJson.encodeToJsonElement(io.yamsergey.dta.protocol.network.RedactionInfo.serializer(), it)) }
                                        if (bodyUnchecked) put("bodyFilterUnchecked", true)
                                        decision?.let { d ->
                                            put("decision", buildJsonObject {
                                                put("match", true)
                                                d.hits?.let { put("hits", DtaJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(io.yamsergey.dta.protocol.network.SearchHit.serializer()), it)) }
                                                if (d.byPreview) put("byPreview", true)
                                            })
                                        }
                                        if (left) put("decision", buildJsonObject { put("match", false); put("left", true) })
                                        if (counts && event.change == EntryChange.CREATED) runEntries(listOf(event.entry.runId))?.let { put("runEntries", it) }
                                        marker?.let { put("degraded", DtaJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(DegradationMarker.serializer()), it)) }
                                    },
                                ),
                            )
                        }
                    }
                    // D-040: the decider waits for the sender (it runs apart from the shared feed, 071 R8), so a burst a
                    // client reads at full speed is never dropped here; only [undecided] filling (the client stopped reading) is.
                    suspend fun offer(event: NetworkEntryEvent) {
                        // Feature 068: the archive could not hold entries for this watcher: said with the next one.
                        if ((event.missed ?: 0) > 0) dropped.set(true)
                        (event.entry as? WsConnectionEntry)?.let { connectionUrls[it.id] = it.url }
                        val redactNow = redacts()
                        val query = if (liveQuery.redact == redactNow) liveQuery else liveQuery.copy(redact = redactNow)
                        if (view != null) {
                            val (d, had) = ops.networkDecide(serial, pkg, view, query, event.entry, decisionStop) { connectionUrls[it] } ?: (null to false)
                            if (d != null) {
                                if (d.byPreview) byPreview += event.entry.id else byPreview -= event.entry.id
                                val mark: Any? = when {
                                    d.match -> d
                                    had -> LEFT
                                    else -> null
                                }
                                if (mark != null) buffer.send(event to mark)
                                else if (counts && event.change == EntryChange.CREATED) buffer.send(event to COUNTED)
                                return
                            }
                        }
                        // Feature 071 (D-079): the same scope a view's decision has (HostIndex.decide): without the switch
                        // the current run only; an earlier run's entries arriving later (a catch-up) are not delivered.
                        if (!io.yamsergey.dta.daemon.network.LiveScope.includes(query, event.entry, ops.networkCurrentRun(serial, pkg))) return
                        unchecked = false
                        val matched = eval(redactNow).matches(event.entry) != null
                        if (matched || unchecked) buffer.send(event to unchecked)
                    }
                    // Feature 071 (research R8): events are decided here, one by one, apart from the feed every
                    // watcher shares: a decision that runs long holds this subscription only, never the others.
                    val undecided = Channel<NetworkEntryEvent>(DECIDE_BUFFER)
                    fun enqueue(event: NetworkEntryEvent) { if (undecided.trySend(event).isFailure) dropped.set(true) }
                    val decider = launch(kotlinx.coroutines.Dispatchers.IO) {
                        val me = coroutineContext[Job]!!
                        decisionStop = io.yamsergey.dta.protocol.network.PatternStop { !me.isActive }
                        for (event in undecided) offer(event)
                    }
                    // Feature 067: a body decided by its preview is decided again once its copy is here.
                    if (view != null) launch {
                        ops.networkCopied(serial, pkg).collect { id ->
                            if (id !in byPreview) return@collect
                            val entry = ops.networkLiveEntry(serial, pkg, id) ?: return@collect
                            enqueue(NetworkEntryEvent(entry, EntryChange.UPDATED))
                        }
                    }
                    try {
                        if (!history) {
                            events.collect(::enqueue)
                        } else {
                            // Subscribe first and hold live events, then read the snapshot: nothing
                            // between the two is lost, and a live repeat of a replayed state is skipped.
                            val live = Channel<NetworkEntryEvent>(Channel.UNLIMITED)
                            val collector = launch {
                                try { events.collect { live.send(it) } } finally { live.close() }
                            }
                            kotlinx.coroutines.withTimeoutOrNull(10_000) { subscribed.await() }
                            val replayed = HashMap<String, NetworkEntry>()
                            var cursor: String? = null
                            do {
                                // Feature 071 (FR-002, D-079): the switch scopes the replay as it scopes the live part: every run with it.
                                val replayScope = if (archive) NetworkQuery(filter = filter, archive = true) else NetworkQuery(filter = filter.copy(runs = RunScope.CURRENT))
                                val page = ops.network(serial, pkg, replayScope.copy(limit = 1000, order = Order.OLDEST_FIRST, before = cursor, ignoreSavedFilters = true))
                                page.entries.forEach { entry ->
                                    replayed[entry.id] = entry
                                    undecided.send(NetworkEntryEvent(entry, EntryChange.CREATED))
                                }
                                cursor = page.next
                            } while (cursor != null)
                            for (event in live) {
                                if (replayed.remove(event.entry.id)?.let { it == event.entry } == true) continue
                                undecided.send(event)
                            }
                            collector.join()
                        }
                        undecided.close()
                        decider.join()
                        event(DaemonEvents.STOPPING, DaemonStoppingEvent.serializer(), DaemonStoppingEvent("event stream of $pkg ended"))
                    } finally {
                        undecided.close()
                        buffer.close()
                    }
                }
                val watchScope = runCatching { ops.networkScope(serial, pkg, liveQuery) }.getOrNull()
                subscriptions[id] = job
                networkTargets[id] = serial to pkg
                job.invokeOnCompletion { networkTargets.remove(id) }
                respond(
                    request.id,
                    DtaJson.encodeToJsonElement(Selected.serializer(), Selected(resolution.statement, buildJsonObject {
                        put("subscription", id)
                        if (view != null) put("liveQuery", true)
                        // Feature 071 (FR-004, D-079): the watch states its scope, as a list does.
                        put("archive", archive)
                        watchScope?.let { put("scope", DtaJson.encodeToJsonElement(io.yamsergey.dta.protocol.network.HistoryScope.serializer(), it)) }
                    })),
                )
                // Started after the answer: a client knows its subscription before the first event.
                job.start()
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
                // D-052: following before the answer, sending after it. The answer means "watching": an
                // event right after it is not lost, and the client knows its subscription before the first one.
                val answered = kotlinx.coroutines.CompletableDeferred<Unit>()
                val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    events.collect { e ->
                        answered.await()
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
                answered.complete(Unit)
            } catch (e: ClientException) {
                error(request.id, e.code, e.message ?: "failed", e.data)
            } catch (e: Exception) {
                error(request.id, ErrorCodes.INTERNAL, "${e.javaClass.simpleName}: ${e.message}")
            }
        }

        /** `timeline.watch` (features 015/016): the app's timeline as the host takes it in, optionally the kept events first. */
        private suspend fun subscribeTimeline(request: Request) {
            val params = request.params ?: JsonObject(emptyMap())
            try {
                val resolution = selector.resolve(
                    Scope.APP,
                    (params["device"] as? JsonPrimitive)?.content,
                    (params["app"] as? JsonPrimitive)?.content,
                )
                val serial = resolution.serial!!
                val pkg = resolution.pkg!!
                val kinds = (params["kinds"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty().toSet()
                val sources = (params["sources"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty().toSet()
                val history = (params["history"] as? JsonPrimitive)?.content == "current"
                val id = "s${subscriptionIds.incrementAndGet()}"
                val ignore = (params["ignoreSavedFilters"] as? JsonPrimitive)?.content == "true"
                val events = ops.timelineWatch(serial, pkg, history, sources, kinds, ignore)
                val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    events.collect { item ->
                        send(
                            Event(
                                name = DaemonEvents.TIMELINE_EVENT,
                                seq = seq.incrementAndGet(),
                                data = buildJsonObject {
                                    put("subscription", id)
                                    put("device", serial)
                                    put("app", pkg)
                                    when (item) {
                                        is io.yamsergey.dta.daemon.timeline.TimelineWatchItem.Event -> {
                                            // Decision 2026-11-22: a redacted watch sends events as timeline.events redacts them.
                                            val (event, redaction) = if (redacts()) io.yamsergey.dta.protocol.timeline.TimelineRedaction.event(item.event).let { (e, info) -> e to info.copy(forAgents = true) } else item.event to statedOff()
                                            put("event", DtaJson.encodeToJsonElement(io.yamsergey.dta.protocol.timeline.TimelineEvent.serializer(), event))
                                            redaction?.let { put("redaction", DtaJson.encodeToJsonElement(io.yamsergey.dta.protocol.network.RedactionInfo.serializer(), it)) }
                                        }
                                        // Feature 065: which of Network's saved filters apply (at the start, on a change).
                                        is io.yamsergey.dta.daemon.timeline.TimelineWatchItem.Filters ->
                                            put("saved", DtaJson.encodeToJsonElement(io.yamsergey.dta.protocol.network.SavedFiltersApplied.serializer(), item.saved))
                                    }
                                },
                            ),
                        )
                    }
                }
                subscriptions[id] = job
                respond(
                    request.id,
                    DtaJson.encodeToJsonElement(Selected.serializer(), Selected(resolution.statement, buildJsonObject { put("subscription", id) })),
                )
                // Started after the answer: a client knows its subscription before the first event.
                job.start()
            } catch (e: ClientException) {
                error(request.id, e.code, e.message ?: "failed", e.data)
            } catch (e: Exception) {
                error(request.id, ErrorCodes.INTERNAL, "${e.javaClass.simpleName}: ${e.message}")
            }
        }

        /** `layout.subscribe` (feature 057): the app's screen-change signal, leased while this subscription lives. */
        private suspend fun subscribeLayout(request: Request) {
            val params = request.params ?: JsonObject(emptyMap())
            try {
                val resolution = selector.resolve(
                    Scope.APP,
                    (params["device"] as? JsonPrimitive)?.content,
                    (params["app"] as? JsonPrimitive)?.content,
                )
                val serial = resolution.serial!!
                val pkg = resolution.pkg!!
                val id = "s${subscriptionIds.incrementAndGet()}"
                val updates = ops.layoutSignal(serial, pkg)
                val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    updates.collect { u ->
                        val (name, data) = when (u) {
                            is io.yamsergey.dta.daemon.layout.LayoutSignalWatch.Update.Changed -> DaemonEvents.LAYOUT_CHANGED to
                                (DtaJson.encodeToJsonElement(io.yamsergey.dta.protocol.layout.LayoutChanged.serializer(), u.change) as JsonObject)
                            is io.yamsergey.dta.daemon.layout.LayoutSignalWatch.Update.State -> DaemonEvents.LAYOUT_SIGNAL to
                                (DtaJson.encodeToJsonElement(io.yamsergey.dta.protocol.layout.LayoutSignalState.serializer(), io.yamsergey.dta.protocol.layout.LayoutSignalState(u.state, u.reason)) as JsonObject)
                        }
                        send(Event(name = name, seq = seq.incrementAndGet(), data = JsonObject(data + mapOf("subscription" to JsonPrimitive(id), "device" to JsonPrimitive(serial), "app" to JsonPrimitive(pkg)))))
                    }
                }
                subscriptions[id] = job
                respond(
                    request.id,
                    DtaJson.encodeToJsonElement(Selected.serializer(), Selected(resolution.statement, buildJsonObject { put("subscription", id) })),
                )
                job.start()
            } catch (e: ClientException) {
                error(request.id, e.code, e.message ?: "failed", e.data)
            } catch (e: Exception) {
                error(request.id, ErrorCodes.INTERNAL, "${e.javaClass.simpleName}: ${e.message}")
            }
        }

        /** Feature 055: the app's shared layout selection, now and at every change. */
        private suspend fun subscribeSelection(request: Request) {
            val params = request.params ?: JsonObject(emptyMap())
            try {
                val resolution = selector.resolve(Scope.APP, (params["device"] as? JsonPrimitive)?.content, (params["app"] as? JsonPrimitive)?.content)
                val serial = resolution.serial!!
                val pkg = resolution.pkg!!
                val id = "s${subscriptionIds.incrementAndGet()}"
                val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    ops.layoutSelections(serial, pkg).collect { selection ->
                        send(Event(name = DaemonEvents.LAYOUT_SELECTION, seq = seq.incrementAndGet(), data = buildJsonObject {
                            put("subscription", id)
                            put("device", serial)
                            put("app", pkg)
                            put("selection", DtaJson.encodeToJsonElement(io.yamsergey.dta.protocol.layout.LayoutSelection.serializer(), selection))
                            selection.changedBy?.let { put("by", it) }
                        }))
                    }
                }
                subscriptions[id] = job
                respond(request.id, DtaJson.encodeToJsonElement(Selected.serializer(), Selected(resolution.statement, buildJsonObject { put("subscription", id) })))
                job.start()
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
            clientsChanged()
            subscriptions.values.forEach { it.cancel() }
            subscriptions.clear()
            // Feature 071: a client that went away stops its searches and its calls.
            finds.forEach { (id, run) -> stopReasons[id] = io.yamsergey.dta.protocol.network.StopReason.CLOSED; run.first.cancel() }
            calls.values.forEach { it.cancel() }
            ops.networkViewsClosed(viewPrefix)
        }
    }

    /** Feature 073: every agent row now, in connection order of the sessions. */
    private fun clientInfos(): List<ClientInfo> = sessions.flatMap { it.infos }

    /** Feature 073: tells every `daemon.clients` subscriber the list changed. */
    private fun clientsChanged() = sessions.forEach { it.pushClients() }

    private fun requestStop(reason: String) {
        thread(name = "dta-daemon-stop", isDaemon = false) {
            Thread.sleep(100) // let the response reach the requester first
            onStop(reason)
        }
    }
}

/** Feature 055: who changed the shared selection, in the words the plugin shows. */
internal fun who(kind: io.yamsergey.dta.protocol.ops.ClientKind): String = when (kind) {
    io.yamsergey.dta.protocol.ops.ClientKind.IDE -> "plugin"
    io.yamsergey.dta.protocol.ops.ClientKind.CLI -> "cli"
    io.yamsergey.dta.protocol.ops.ClientKind.MCP, io.yamsergey.dta.protocol.ops.ClientKind.MCP_HTTP -> "mcp"
}

/** Feature 071 (research R8): live events of one subscription waiting for their decision; past this the client fell behind and is told (D-040). */
private const val DECIDE_BUFFER = 4_096

/** Feature 071: the first credit of a stream when the client does not say (contracts/find.md). */
private const val FIND_WINDOW = 500

/** Feature 067: an entry that left a live view's matches (it matched before, it does not now). */
private object LEFT

/** D-085: a new entry a counting view does not list: only its run is counted. */
private object COUNTED
