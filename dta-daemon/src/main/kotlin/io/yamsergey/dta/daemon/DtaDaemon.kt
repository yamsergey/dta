package io.yamsergey.dta.daemon

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.yamsergey.dta.daemon.adb.AdbExec
import io.yamsergey.dta.daemon.adb.PortForwards
import io.yamsergey.dta.daemon.connection.ForwardOps
import io.yamsergey.dta.daemon.connection.SidekickConnection
import io.yamsergey.dta.daemon.connection.SidekickConnectionManager
import io.yamsergey.dta.daemon.discovery.ConnectionKey
import io.yamsergey.dta.daemon.discovery.Device
import io.yamsergey.dta.protocol.ops.AdbInfo
import io.yamsergey.dta.protocol.ops.AppInfo
import io.yamsergey.dta.protocol.ops.AppList
import io.yamsergey.dta.protocol.ops.DeviceInfo
import io.yamsergey.dta.protocol.rpc.IdentityReport
import io.yamsergey.dta.daemon.discovery.SidekickDiscovery
import io.yamsergey.dta.daemon.layout.ForegroundDetector
import io.yamsergey.dta.daemon.layout.LayoutService
import io.yamsergey.dta.daemon.layout.UiAutomatorFallback
import io.yamsergey.dta.daemon.network.BodyOutput
import io.yamsergey.dta.daemon.network.BodyResult
import io.yamsergey.dta.daemon.network.NetworkScriptService
import io.yamsergey.dta.daemon.network.NetworkService
import io.yamsergey.dta.daemon.network.proto.BodyDecoding
import io.yamsergey.dta.daemon.network.proto.BodyMapping
import io.yamsergey.dta.daemon.network.proto.BodyMappingSpec
import io.yamsergey.dta.daemon.network.proto.SchemaInput
import io.yamsergey.dta.daemon.network.proto.SchemaRegistry
import io.yamsergey.dta.daemon.network.proto.SchemaSet
import io.yamsergey.dta.protocol.network.ScriptLogPage
import io.yamsergey.dta.protocol.network.ScriptStatus
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.layout.DetailLevel
import io.yamsergey.dta.protocol.layout.LayoutCapture
import io.yamsergey.dta.protocol.layout.LayoutFilter
import io.yamsergey.dta.protocol.layout.LayoutTreeParams
import io.yamsergey.dta.protocol.layout.Screen
import io.yamsergey.dta.protocol.network.ClearResult
import io.yamsergey.dta.protocol.network.NetworkEntryDetail
import io.yamsergey.dta.protocol.network.NetworkEntryEvent
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.NetworkQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Host-side entry point of the channel feature. In this feature the daemon
 * ships as a library (no server process yet — see plan.md); this class is the
 * production wiring of discovery + forwards + the connection manager.
 */
class DtaDaemon(
    private val runAdb: (List<String>) -> AdbExec.Result,
    /** Resolved adb binary, or null with [adbUnavailableReason] (feature 008: the daemon runs without adb). */
    private val adbPath: String?,
    requestTimeout: Duration = 10.seconds,
    /** Daemon state on the developer's machine: protobuf schemas and mappings (feature 006). */
    dataDir: java.io.File = defaultHome(),
    private val adbUnavailableReason: String? = null,
    /** This DTA as told to apps' sidekicks and in messages about them (feature 036). */
    private val selfVersion: String = "dev",
) {
    constructor(
        adb: AdbExec = AdbExec(),
        requestTimeout: Duration = 10.seconds,
        dataDir: java.io.File = defaultHome(),
        selfVersion: String = "dev",
    ) : this({ args -> adb.run(*args.toTypedArray()) }, adb.binaryPath, requestTimeout, dataDir, selfVersion = selfVersion)

    private val schemas = SchemaRegistry(dataDir)
    /** Feature 039: the user's WebSocket message format rules; every text message returned carries the envelope they read. */
    private val formats = io.yamsergey.dta.daemon.network.MessageFormats(dataDir)
    private val injectedTooling = io.yamsergey.dta.daemon.inject.InjectedTooling(dataDir)

    /** The host's network store (feature 011): opened on first use, one per daemon. */
    private val hostStore by lazy { io.yamsergey.dta.daemon.store.HostStore(java.io.File(dataDir, "store")) }
    private val deviceIdentities = io.yamsergey.dta.daemon.device.DeviceIdentities { serial -> runAdb(listOf("-s", serial, "shell", "getprop")) }
    private val hostIndexes = java.util.concurrent.ConcurrentHashMap<ConnectionKey, io.yamsergey.dta.daemon.network.HostIndex>()

    /** Background work of the host store: copying bodies as soon as entries finish (011 US7). */
    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private fun hostIndex(serial: String, pkg: String): io.yamsergey.dta.daemon.network.HostIndex =
        hostIndexes.computeIfAbsent(ConnectionKey(serial, pkg)) { key ->
            io.yamsergey.dta.daemon.network.HostIndex(hostStore, deviceIdentities.of(serial), pkg, decoding = { BodyDecoding(schemas.rules(pkg)) }) {
                if (kotlinx.coroutines.currentCoroutineContext()[HeldOnly] != null) {
                    connections.held(key) ?: throw io.yamsergey.dta.daemon.connection.ConnectionLostException("${key.packageName} is not connected")
                } else {
                    connections.connect(key)
                }
            }.also { startCopying(key, it) }
        }

    /** Marks background work: it uses the held connection only and never dials (see [startCopying]). */
    private class HeldOnly : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<HeldOnly>
    }

    /**
     * Catches up and copies bodies while the app is connected. It never opens a connection
     * itself, even when the app dies between the open-keys check and the call (HeldOnly):
     * attempts on an app that is gone would leave a failure behind that a query right after
     * the app restarts would get.
     */
    private fun startCopying(key: ConnectionKey, index: io.yamsergey.dta.daemon.network.HostIndex) {
        val worker = io.yamsergey.dta.daemon.network.CopyWorker(index)
        background.launch(HeldOnly()) {
            while (isActive) {
                if (key !in connections.openKeys()) {
                    delay(1_000)
                    continue
                }
                val copied = try {
                    index.refresh()
                    worker.runOnce()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    delay(5_000)
                    continue
                }
                if (copied == 0L) delay(1_000)
            }
        }
    }
    private val forwards = PortForwards(runAdb)

    init {
        // FR-011: forwards leaked by a previous run are cleaned at startup.
        // Never fatal — no adb/no devices just means nothing to sweep.
        if (adbPath != null) runCatching { forwards.startupSweep() }
    }

    /** Re-runs the leaked-forward cleanup; returns how many were removed. */
    fun startupSweep(): Int = forwards.startupSweep()

    val discovery = SidekickDiscovery(runAdb)

    val connections = SidekickConnectionManager(
        forwardOps = ForwardOps(
            forward = { forwards.forward(it) },
            remove = { serial, port -> forwards.remove(serial, port) },
        ),
        connector = { key, port ->
            SidekickConnection.connect(
                uri = URI("ws://127.0.0.1:$port/"),
                expectedPackage = key.packageName,
                requestTimeout = requestTimeout,
                clientVersion = selfVersion,
            )
        },
        probe = { connection ->
            runCatching { connection.request("ping", null, 2.seconds) }.isSuccess
        },
    )

    /** Keeps watched apps connected and says when that changes (feature 009, FR-017). */
    private val watcher = io.yamsergey.dta.daemon.connection.AppConnectionWatcher(
        instances = { serial -> discovery.listInstances().filter { serial == null || it.serial == serial }.map { ConnectionKey(it.serial, it.packageName) } },
        manager = connections,
    )

    /** App connection state changes on [serial] (every device when null), for as long as collected. */
    fun appEvents(serial: String?): Flow<io.yamsergey.dta.protocol.ops.AppConnectionEvent> = watcher.events(serial)

    private val foregroundDetector = ForegroundDetector(runAdb)
    private val fallback = UiAutomatorFallback(runAdb)

    /**
     * Capture the layout tree for [pkg] on [serial], applying the sidekick-vs-
     * fallback decision ladder with honest labeling (contract §4). [pkg] null
     * means "whatever is foreground" (fallback only).
     */
    suspend fun layout(
        serial: String,
        pkg: String?,
        filters: LayoutFilter? = null,
        detail: DetailLevel = DetailLevel.SEMANTIC,
        frameworkFrames: Boolean = false,
        /** Feature 014: also the screen image and the windows on screen, around the tree. */
        screen: io.yamsergey.dta.daemon.layout.ScreenRequest? = null,
    ): LayoutCapture {
        val service = LayoutService(
            foregroundPackage = { foregroundDetector.foregroundPackage(it) },
            sidekickCapture = pkg?.let { p ->
                { params ->
                    val conn = connections.connect(ConnectionKey(serial, p))
                    val result = conn.request("layout_tree", params.toJson())
                    DtaJson.decodeFromJsonElement(LayoutCapture.serializer(), result)
                }
            },
            uiautomatorCapture = { s ->
                val screen = screenOf(s)
                fallback.capture(s, screen.widthPx, screen.heightPx)
            },
            screenOf = { screenOf(it) },
        )
        val params = LayoutTreeParams(filters, detail, frameworkFrames)
        if (screen == null) return service.capture(serial, pkg, params)
        return screenCapture.capture(serial, pkg, screen.output) { service.capture(serial, pkg, params) }
    }

    private val screenCapture = io.yamsergey.dta.daemon.layout.ScreenCapture(
        dumpsys = { serial, args ->
            val r = runAdb(listOf("-s", serial, "shell", "dumpsys") + args)
            check(r.exitCode == 0) { r.stderr.trim().ifEmpty { "dumpsys ${args.joinToString(" ")} exited with ${r.exitCode}" } }
            r.stdout
        },
        screencap = { serial ->
            val adb = AdbExec(adbPath ?: error(adbUnavailableReason ?: "adb is not available"))
            val r = adb.runBytes("-s", serial, "exec-out", "screencap", "-p", timeout = 15.seconds)
            check(r.exitCode == 0 && r.stdout.isNotEmpty()) { r.stderr.trim().ifEmpty { "screencap exited with ${r.exitCode}" } }
            r.stdout
        },
    )

    /** Network history of [pkg]: HTTP and WebSocket entries in one list (feature 004). */
    suspend fun network(serial: String, pkg: String, query: NetworkQuery = NetworkQuery()): NetworkPage =
        formats.annotate(pkg, networkService(serial, pkg).list(query))

    /** Feature 040: entries holding every search word of the query line, with where. */
    suspend fun networkSearch(serial: String, pkg: String, query: NetworkQuery): io.yamsergey.dta.protocol.network.SearchPage =
        networkService(serial, pkg).search(query)

    suspend fun networkEntry(serial: String, pkg: String, id: String, redact: Boolean = false): NetworkEntryDetail =
        formats.annotate(pkg, networkService(serial, pkg).entry(id, redact))

    /**
     * One body: decoded text, or bytes to a file. Protobuf bodies a rule selects (built-in
     * OTLP, user mappings) come back as JSON with [BodyResult.decoding]; other binary bodies
     * need [BodyOutput.File].
     * With [redact], bodies that cannot be redacted are withheld unless [allowUnredacted].
     */
    suspend fun networkBody(
        serial: String,
        pkg: String,
        id: String,
        part: BodyPart,
        output: BodyOutput = BodyOutput.Text,
        redact: Boolean = false,
        allowUnredacted: Boolean = false,
    ): BodyResult {
        val body = networkService(serial, pkg).body(id, part, output, redact, allowUnredacted)
        if (part != BodyPart.MESSAGE || body.text == null) return body
        // Feature 039: a message read whole gets its envelope from the whole text (the list has the preview's).
        val entry = runCatching { networkService(serial, pkg).entry(id, redact).entry }.getOrNull()
        return formats.annotate(pkg, entry, body)
    }

    suspend fun networkClear(serial: String, pkg: String): ClearResult =
        networkService(serial, pkg).clear()

    private val timelineServices = java.util.concurrent.ConcurrentHashMap<ConnectionKey, io.yamsergey.dta.daemon.timeline.TimelineService>()

    /** One app's timeline on the host, followed from its first use for the daemon's life (features 015/016). */
    private fun timelineService(serial: String, pkg: String): io.yamsergey.dta.daemon.timeline.TimelineService =
        timelineServices.computeIfAbsent(ConnectionKey(serial, pkg)) { key ->
            val timeline = io.yamsergey.dta.daemon.timeline.Timeline(session = "u${System.currentTimeMillis().toString(36)}${timelineServices.size}")
            io.yamsergey.dta.daemon.timeline.TimelineService(
                journal = timeline,
                windows = io.yamsergey.dta.daemon.ui.HostWindowWatch(pkg, timeline, dumpsys = {
                    val r = runAdb(listOf("-s", serial, "shell", "dumpsys", "window", "windows"))
                    check(r.exitCode == 0) { r.stderr.trim() }
                    r.stdout
                }),
                held = { connections.held(key) },
                connect = { connections.connect(key) },
                running = { discovery.listInstances().any { it.serial == key.serial && it.packageName == key.packageName } },
            ).also { it.start(background) }
        }

    /** The app's timeline after [since] (a cursor, or `end`), oldest first. */
    suspend fun timelineEvents(
        serial: String,
        pkg: String,
        since: String? = null,
        sinceMs: Long? = null,
        sources: Set<String> = emptySet(),
        kinds: Set<String> = emptySet(),
        limit: Int = 100,
    ): io.yamsergey.dta.protocol.timeline.TimelinePage =
        timelineService(serial, pkg).page(since, limit.coerceIn(1, 500), sinceMs, sources, kinds)

    /** Timeline events as the host takes them in; with [history] the kept ones first. */
    fun timelineWatch(serial: String, pkg: String, history: Boolean, sources: Set<String>, kinds: Set<String>): Flow<io.yamsergey.dta.protocol.timeline.TimelineEvent> = kotlinx.coroutines.flow.flow {
        val service = timelineService(serial, pkg)
        val first = service.page(null, 500, null, sources, kinds)
        if (history) first.events.forEach { emit(it) }
        var cursor = first.next
        // Live events are taken from the timeline by cursor, so watching never skips or repeats;
        // windows that are not the app's are sampled for as long as someone watches.
        val lease = background.launch { while (true) { service.windows?.touch(); kotlinx.coroutines.delay(30_000) } }
        try {
            kotlinx.coroutines.flow.merge(
                kotlinx.coroutines.flow.flow { service.events.collect { emit(Unit) } },
                kotlinx.coroutines.flow.flow { while (true) { kotlinx.coroutines.delay(1_000); emit(Unit) } },
            ).collect {
                val page = service.journal.page(cursor, 500, null, sources, kinds)
                page.events.forEach { e -> emit(e) }
                cursor = page.next
            }
        } finally {
            lease.cancel()
        }
    }

    /** Why [event] happened, or what it led to (feature 016). Throws [io.yamsergey.dta.daemon.timeline.CauseGraph.UnknownEvent]. */
    suspend fun timelineCauses(
        serial: String,
        pkg: String,
        event: String,
        direction: io.yamsergey.dta.protocol.timeline.ChainDirection = io.yamsergey.dta.protocol.timeline.ChainDirection.CAUSES,
        depth: Int = io.yamsergey.dta.daemon.timeline.CauseGraph.DEFAULT_DEPTH,
        inferred: Boolean = false,
    ): io.yamsergey.dta.protocol.timeline.CauseChain {
        val service = timelineService(serial, pkg)
        service.refresh()
        val events = service.journal.kept()
        return causeGraph(serial, pkg, events, inferred).walk(event, direction, depth, inferred)
    }

    /** The cause graph over [events]; with [inferred], recent response bodies are read for the text rule. */
    private suspend fun causeGraph(serial: String, pkg: String, events: List<io.yamsergey.dta.protocol.timeline.TimelineEvent>, inferred: Boolean): io.yamsergey.dta.daemon.timeline.CauseGraph {
        if (!inferred) return io.yamsergey.dta.daemon.timeline.CauseGraph(events)
        val ids = events.filter { it.kind == io.yamsergey.dta.protocol.timeline.Kinds.NETWORK_RESPONSE }
            .takeLast(io.yamsergey.dta.daemon.timeline.InferredRules.RECENT_RESPONSES)
            .mapNotNull { it.network?.entryId }.distinct()
        val bodies = ids.mapNotNull { id ->
            runCatching {
                networkService(serial, pkg).body(id, io.yamsergey.dta.protocol.network.BodyPart.RESPONSE, redact = true).text
                    ?.takeIf { it.length <= MAX_MATCHED_BODY }?.let { id to it }
            }.getOrNull()
        }.toMap()
        return io.yamsergey.dta.daemon.timeline.CauseGraph(events, io.yamsergey.dta.daemon.timeline.InferredRules(events, body = bodies::get))
    }

    /** What the app is in now: every facet, and each source's status (feature 016). */
    suspend fun stateCurrent(serial: String, pkg: String): io.yamsergey.dta.protocol.timeline.CurrentState {
        val service = timelineService(serial, pkg)
        service.refresh()
        return service.journal.facets.current(service.sources())
    }

    /** States and transitions of the app's current process, grouped by [facets] (feature 016). */
    suspend fun stateGraph(serial: String, pkg: String, facets: List<String>?, minHoldMs: Long = 0, inferred: Boolean = false): io.yamsergey.dta.protocol.timeline.StateGraph {
        val service = timelineService(serial, pkg)
        service.refresh()
        val timeline = service.journal
        val events = timeline.currentEpochEvents()
        val current = timeline.facets.snapshot()
        val causes = causeGraph(serial, pkg, events, inferred)
        val available = current.map { it.name }.toSet() + events.flatMap { e -> e.facets.map { it.name } }
        return io.yamsergey.dta.daemon.timeline.StateGrouper(
            events,
            io.yamsergey.dta.daemon.timeline.StateGrouper.initialValues(events, current),
            available,
            partial = timeline.currentEpochPartial(),
            causesOf = { causes.causesOf(it, inferred) },
            known = causes::hasKnownCause,
        ).graph(facets?.takeIf { it.isNotEmpty() }, minHoldMs)
    }

    /** The shortest observed path between two states (feature 016). */
    suspend fun statePath(serial: String, pkg: String, from: String, to: String?, facets: List<String>?): io.yamsergey.dta.protocol.timeline.StatePath =
        io.yamsergey.dta.daemon.timeline.StatePaths(stateGraph(serial, pkg, facets)).path(from, to)

    /** Live entry events; collect for as long as needed. */
    fun networkEvents(serial: String, pkg: String, onSubscribed: suspend () -> Unit = {}): Flow<NetworkEntryEvent> =
        networkService(serial, pkg).events(onSubscribed).map { it.copy(entry = formats.annotate(pkg, it.entry)) }

    /** Feature 039: adds an app's message format rule; throws [io.yamsergey.dta.daemon.network.proto.SchemaException] naming what is wrong. */
    fun networkFormatAdd(pkg: String, spec: io.yamsergey.dta.protocol.network.FormatRuleSpec): io.yamsergey.dta.protocol.network.FormatRule = formats.add(pkg, spec)

    fun networkFormats(pkg: String): List<io.yamsergey.dta.protocol.network.FormatRule> = formats.list(pkg)

    fun networkFormatRemove(pkg: String, id: String): Boolean = formats.remove(pkg, id)

    /**
     * How [spec] reads the messages of [connection] (a ws-connection id), or of the newest
     * connection whose URL the rule's pattern matches; nothing is kept.
     */
    suspend fun networkFormatTest(serial: String, pkg: String, spec: io.yamsergey.dta.protocol.network.FormatRuleSpec, connection: String?): io.yamsergey.dta.protocol.network.FormatTestResult {
        val service = networkService(serial, pkg)
        val id = connection ?: run {
            val glob = Regex(spec.urlPattern.split('*').joinToString(".*") { Regex.escape(it) })
            val page = service.list(NetworkQuery(filter = io.yamsergey.dta.protocol.network.NetworkFilter(kinds = listOf(io.yamsergey.dta.protocol.network.EntryKind.WS_CONNECTION)), limit = NetworkQuery.MAX_LIMIT))
            page.entries.filterIsInstance<io.yamsergey.dta.protocol.network.WsConnectionEntry>().firstOrNull { glob.matches(it.url) }?.id
                ?: throw io.yamsergey.dta.daemon.network.proto.SchemaException("no WebSocket connection of the app matches ${spec.urlPattern}; give connection (a ws-connection id from network_list) or a urlPattern of a socket the app opened")
        }
        val detail = service.entry(id, false)
        val messages = detail.messages ?: throw io.yamsergey.dta.daemon.network.proto.SchemaException("$id is not a WebSocket connection; give a ws-connection id from network_list")
        return formats.test(spec, messages)
    }

    /**
     * Adds (or replaces) a protobuf schema set for [pkg]: a compiled descriptor set or
     * `.proto` sources (no protoc needed). Throws [io.yamsergey.dta.daemon.network.proto.SchemaException]
     * naming the problem. Schemas and mappings persist under the daemon's data directory.
     */
    fun networkSchemaAdd(pkg: String, name: String, input: SchemaInput): SchemaSet = schemas.addSchema(pkg, name, input)

    fun networkSchemas(pkg: String): List<SchemaSet> = schemas.schemas(pkg)

    /** Removes a schema set; returns the mappings that used it, now broken. */
    fun networkSchemaRemove(pkg: String, name: String): List<BodyMapping> = schemas.removeSchema(pkg, name)

    /** Replaces one origin's schema set and derived mappings (feature 013). */
    fun networkSchemaSync(
        pkg: String,
        origin: io.yamsergey.dta.daemon.network.proto.SchemaOrigin,
        files: Map<String, String>,
        mappings: List<io.yamsergey.dta.daemon.network.proto.DerivedMapping>,
        notes: List<String> = emptyList(),
    ): io.yamsergey.dta.daemon.network.proto.SyncResult = schemas.sync(pkg, origin, files, mappings, notes)

    /** Maps a message type to bodies: URL glob, part, and optionally method (HTTP) or direction (WebSocket). */
    fun networkMappingAdd(pkg: String, mapping: BodyMappingSpec): BodyMapping = schemas.addMapping(pkg, mapping)

    /** User mappings (with `broken` when unusable), then the built-in OTLP rules. */
    fun networkMappings(pkg: String): List<BodyMapping> = schemas.mappings(pkg)

    fun networkMappingRemove(pkg: String, id: String): Boolean = schemas.removeMapping(pkg, id)

    /** Installs (replaces) the app's network script; it persists until [networkScriptClear]. */
    suspend fun networkScriptSet(serial: String, pkg: String, source: String): ScriptStatus =
        scriptService(serial, pkg).set(source)

    suspend fun networkScriptClear(serial: String, pkg: String): Boolean = scriptService(serial, pkg).clear()

    suspend fun networkScriptStatus(serial: String, pkg: String): ScriptStatus = scriptService(serial, pkg).status()

    suspend fun networkScriptLogs(serial: String, pkg: String, since: Long = 0): ScriptLogPage =
        scriptService(serial, pkg).logs(since)

    suspend fun networkWsPush(serial: String, pkg: String, connection: String?, text: String?, base64: String?, waitMs: Long?): io.yamsergey.dta.protocol.network.WsPushResult =
        scriptService(serial, pkg).push(connection, text, base64, waitMs)

    /** Feature 027: the app's stores. */
    fun data(serial: String, pkg: String) =
        io.yamsergey.dta.daemon.data.DataService(pkg, schemas) { connections.connect(ConnectionKey(serial, pkg)) }

    private fun scriptService(serial: String, pkg: String) =
        NetworkScriptService { connections.connect(ConnectionKey(serial, pkg)) }

    private fun networkService(serial: String, pkg: String) =
        NetworkService(BodyDecoding(schemas.rules(pkg)), hostIndex(serial, pkg)) { connections.connect(ConnectionKey(serial, pkg)) }

    private fun screenOf(serial: String): Screen {
        // wm size / density via adb, best-effort.
        val size = runAdb(listOf("-s", serial, "shell", "wm", "size")).stdout
        val (w, h) = Regex("""(\d+)x(\d+)""").find(size)?.destructured
            ?.let { (a, b) -> a.toInt() to b.toInt() } ?: (0 to 0)
        val density = runAdb(listOf("-s", serial, "shell", "wm", "density")).stdout
            .let { Regex("""(\d+)""").find(it)?.value?.toFloat()?.div(160f) ?: 1f }
        return Screen(w, h, density)
    }

    /**
     * Opens [uri] on the device (feature 012 US2): who received it, and — when the app with DTA
     * did — the link's record, awaited for a few seconds; for an http(s) link the app did not
     * get, its host's App Links state.
     */
    suspend fun linksOpen(serial: String, uri: String, pkg: String?, only: Boolean = false): io.yamsergey.dta.protocol.network.LinkOpenResult {
        val quoted = io.yamsergey.dta.daemon.links.LinkParsers.shellQuote(uri)
        fun linkIds(app: String): Set<String> = runCatching {
            kotlinx.coroutines.runBlocking { linkQuery(serial, app, uri) }
        }.getOrDefault(emptySet())
        val before = pkg?.let(::linkIds).orEmpty()
        val command = "am start -W -a android.intent.action.VIEW -d $quoted" + (pkg?.takeIf { only }?.let { " -p $it" } ?: "") + " --user current"
        val output = runAdb(listOf("-s", serial, "shell", command)).let { it.stdout + it.stderr }
        val result = io.yamsergey.dta.daemon.links.LinkParsers.launch(output, pkg)
        if (result.outcome == io.yamsergey.dta.protocol.network.LinkOpenOutcome.RECEIVED) {
            val app = result.packageName ?: pkg ?: return result
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val now = runCatching { linkQuery(serial, app, uri) }.getOrDefault(emptySet())
                (now - before).firstOrNull()?.let { return result.copy(recordId = it) }
                kotlinx.coroutines.delay(300)
            }
            val why = runCatching { capabilityReport(serial, app).capabilities.firstOrNull { it.name == "links" }?.reason }.getOrNull()
            return result.copy(reason = listOfNotNull(result.reason, "received, but DTA has no record of it" + (why?.let { ": $it" } ?: " (no record arrived within 5 s)")).joinToString("; "))
        }
        val host = runCatching { java.net.URI(uri) }.getOrNull()?.takeIf { it.scheme == "http" || it.scheme == "https" }?.host
        if (host != null && pkg != null) {
            val state = runCatching { linksVerification(serial, pkg) }.getOrNull()
            return result.copy(appLinks = state?.hosts?.firstOrNull { it.host.equals(host, ignoreCase = true) } ?: io.yamsergey.dta.protocol.network.AppLinkHost(host, "not declared", "$pkg declares no App Links for $host"))
        }
        return result
    }

    private suspend fun linkQuery(serial: String, pkg: String, uri: String): Set<String> =
        network(serial, pkg, NetworkQuery(filter = io.yamsergey.dta.protocol.network.NetworkFilter(kinds = listOf(io.yamsergey.dta.protocol.network.EntryKind.LINK), url = uri), limit = 50))
            .entries.map { it.id }.toSet()

    /** `pm get-app-links` for [pkg] (feature 012 US3); unavailable before Android 12. */
    fun linksVerification(serial: String, pkg: String): io.yamsergey.dta.protocol.network.AppLinksState {
        val sdk = runAdb(listOf("-s", serial, "shell", "getprop", "ro.build.version.sdk")).stdout.trim().toIntOrNull() ?: 0
        if (sdk < 31) return io.yamsergey.dta.daemon.links.LinkParsers.appLinks("", sdk)
        val output = runAdb(listOf("-s", serial, "shell", "pm", "get-app-links", "--user", "current", pkg)).stdout
        return io.yamsergey.dta.daemon.links.LinkParsers.appLinks(output, sdk)
    }

    /** The host's network archive (feature 011, contracts/archive-ops.md). */
    fun archiveList(): io.yamsergey.dta.protocol.network.ArchiveListing = hostStore.listing()

    /** Deletes archived history; without [confirm] says what would go. The live history is refused. */
    fun archiveDelete(target: io.yamsergey.dta.daemon.store.DeleteTarget, confirm: Boolean): io.yamsergey.dta.protocol.network.ArchiveDeletion =
        io.yamsergey.dta.protocol.network.ArchiveDeletion(confirm, hostStore.delete(target, dryRun = !confirm))

    suspend fun shutdown() {
        background.cancel()
        connections.shutdown()
    }

    // --- Feature 008: what the daemon process serves besides the per-app operations.

    fun adbInfo(): AdbInfo = AdbInfo(path = adbPath, available = adbPath != null, reason = adbUnavailableReason)

    /** Connected devices with their state; the model for online ones. */
    fun devices(): List<DeviceInfo> = discovery.listDevices().map { device ->
        val online = device.state == Device.State.ONLINE
        DeviceInfo(
            serial = device.serial,
            state = device.state.name.lowercase(),
            model = if (online) {
                runAdb(listOf("-s", device.serial, "shell", "getprop", "ro.product.model")).stdout.trim().ifEmpty { null }
            } else {
                null
            },
        )
    }

    /** Apps on [serial] with a running sidekick, and the foreground app. */
    fun apps(serial: String): AppList {
        val foreground = foregroundDetector.foregroundPackage(serial)
        val withDta = discovery.listInstances().filter { it.serial == serial }.map { it.packageName }.distinct()
        return AppList(serial, foreground, withDta.map { AppInfo(it, it == foreground) })
    }

    fun foregroundPackage(serial: String): String? = foregroundDetector.foregroundPackage(serial)

    /** What the app's sidekick reports about its capabilities and libraries. */
    suspend fun capabilityReport(serial: String, pkg: String): IdentityReport = injectedTooling.annotate(
        DtaJson.decodeFromJsonElement(
            IdentityReport.serializer(),
            connections.connect(ConnectionKey(serial, pkg)).request("identity"),
        ),
    )

    /** Open app connections (for `daemon.status`). */
    fun openConnections(): List<ConnectionKey> = connections.openKeys()

    companion object {
        /** Larger response bodies are not searched for a shown text (feature 016, inferred `text-match`). */
        private const val MAX_MATCHED_BODY = 64 * 1024

        /** A daemon whose device operations fail with [reason] (adb not found); everything else works. */
        fun withoutAdb(reason: String, dataDir: java.io.File): DtaDaemon =
            DtaDaemon({ throw AdbUnavailableException(reason) }, null, dataDir = dataDir, adbUnavailableReason = reason)
    }
}

/** adb could not be found or run; the message says where DTA looked and how to fix it. */
class AdbUnavailableException(message: String) : Exception(message)

/** `$DTA_HOME`, else `-Ddta.home`, else `~/.dta` — as the clients resolve it. */
fun defaultHome(env: Map<String, String> = System.getenv(), property: String? = System.getProperty("dta.home")): java.io.File =
    java.io.File(env["DTA_HOME"]?.takeIf { it.isNotBlank() } ?: property?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.dta"))
