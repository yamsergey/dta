package io.yamsergey.dta.testing

import kotlinx.coroutines.flow.onSubscription
import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.client.DaemonLauncher
import io.yamsergey.dta.daemon.network.BodyOutput
import io.yamsergey.dta.daemon.network.BodyResult
import io.yamsergey.dta.daemon.network.proto.BodyMapping
import io.yamsergey.dta.daemon.network.proto.BodyMappingSpec
import io.yamsergey.dta.daemon.network.proto.SchemaInput
import io.yamsergey.dta.daemon.network.proto.SchemaSet
import io.yamsergey.dta.daemon.server.DaemonOperations
import io.yamsergey.dta.daemon.server.DaemonServer
import io.yamsergey.dta.daemon.server.OperationDispatcher
import io.yamsergey.dta.daemon.server.Selector
import io.yamsergey.dta.daemon.server.StateFile
import io.yamsergey.dta.protocol.layout.CaptureSource
import io.yamsergey.dta.protocol.layout.DetailLevel
import io.yamsergey.dta.protocol.layout.LayoutCapture
import io.yamsergey.dta.protocol.layout.LayoutFilter
import io.yamsergey.dta.protocol.layout.Rect
import io.yamsergey.dta.protocol.layout.Screen
import io.yamsergey.dta.protocol.layout.ViewNode
import io.yamsergey.dta.protocol.layout.Window
import io.yamsergey.dta.protocol.layout.WindowType
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.ClearResult
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.Coverage
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.InterventionRecord
import io.yamsergey.dta.protocol.network.NetworkEntryDetail
import io.yamsergey.dta.protocol.network.NetworkEntryEvent
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.ScriptLogPage
import io.yamsergey.dta.protocol.network.ScriptStatus
import io.yamsergey.dta.protocol.ops.AdbInfo
import io.yamsergey.dta.protocol.ops.AppInfo
import io.yamsergey.dta.protocol.ops.AppList
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.ops.DaemonStatus
import io.yamsergey.dta.protocol.ops.DeviceInfo
import io.yamsergey.dta.protocol.ops.VersionIdentity
import io.yamsergey.dta.protocol.rpc.IdentityReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File
import java.nio.file.Files

/** Canned library answers for CLI, MCP and plugin tests (one device, one app with DTA in front). */
class CannedOperations(var deviceList: List<DeviceInfo> = listOf(DeviceInfo("emulator-5554", "online", "Pixel 8"))) : DaemonOperations {
    val events = MutableSharedFlow<NetworkEntryEvent>(extraBufferCapacity = 100)
    val entries = listOf(
        HttpEntry("r1-1:1", "r1-1", 1_700_000_000_000, "okhttp3", method = "GET", url = "https://api.example.com/v1/catalog", state = HttpState.COMPLETE, status = 200, durationMs = 142,
            responseBody = BodyInfo(BodyPart.RESPONSE, 18_841, completeness = Completeness.COMPLETE)),
        HttpEntry("r1-1:2", "r1-1", 1_700_000_001_000, "okhttp3", method = "POST", url = "https://api.example.com/v1/cart", state = HttpState.COMPLETE, status = 503, durationMs = 2000,
            intervention = InterventionRecord("abc123")),
        HttpEntry("r1-1:3", "r1-1", 1_700_000_002_000, "okhttp3", method = "GET", url = "https://cdn.example.net/a.png", state = HttpState.PENDING),
    )

    override fun devices() = deviceList
    override fun apps(serial: String) = AppList(serial, "com.example.shop", listOf(AppInfo("com.example.shop", true)))
    val appEventFlow = MutableSharedFlow<io.yamsergey.dta.protocol.ops.AppConnectionEvent>(extraBufferCapacity = 100)
    override fun appEvents(serial: String?): Flow<io.yamsergey.dta.protocol.ops.AppConnectionEvent> = appEventFlow
    override suspend fun capabilityReport(serial: String, pkg: String) = IdentityReport(1, "0.1.0", pkg, emptyList())
    /** The capture `layout.tree` returns; tests swap it to change the screen. */
    @Volatile var capture: LayoutCapture = LayoutCapture(
        Screen(1080, 2400, 2.6f, "MainActivity"), CaptureSource.SIDEKICK,
        listOf(Window(type = WindowType.APPLICATION, zOrder = 0, root = ViewNode(1, Rect(0, 0, 1080, 2400), "android.widget.FrameLayout", resourceId = "android:id/content",
            children = listOf(ViewNode(2, Rect(0, 0, 100, 50), "android.widget.Button", text = "Pay"))))),
    )
    val layoutRequests = java.util.concurrent.CopyOnWriteArrayList<Pair<String?, DetailLevel>>()
    /** What layout returns when the screen is asked for; [capture] otherwise. */
    var screenCapture: LayoutCapture? = null
    override suspend fun layout(serial: String, pkg: String?, filters: LayoutFilter?, detail: DetailLevel, frameworkFrames: Boolean, screen: io.yamsergey.dta.daemon.layout.ScreenRequest?) =
        (screen?.let { screenCapture } ?: capture).also { layoutRequests += pkg to detail; screenRequests += screen }
    val screenRequests = java.util.concurrent.CopyOnWriteArrayList<io.yamsergey.dta.daemon.layout.ScreenRequest?>()
    val queries = java.util.concurrent.CopyOnWriteArrayList<Pair<String, NetworkQuery>>()
    var cleared = 0
    override suspend fun network(serial: String, pkg: String, query: NetworkQuery) =
        NetworkPage(entries, total = entries.size, coverage = Coverage(searched = 3, stored = 3, persisted = true)).also { queries += pkg to query }
    override suspend fun networkEntry(serial: String, pkg: String, id: String, redact: Boolean) = NetworkEntryDetail(entries.first { it.id == id })
    override suspend fun networkBody(serial: String, pkg: String, id: String, part: BodyPart, output: BodyOutput, redact: Boolean, allowUnredacted: Boolean) =
        BodyResult(BodyInfo(part, 13, contentType = "application/json", completeness = Completeness.COMPLETE), text = "{\"items\":[]}", bytes = 12)
    override suspend fun networkClear(serial: String, pkg: String) = ClearResult(clearedEntries = 3, clearedAtMs = 1L).also { cleared++ }
    override fun networkEvents(serial: String, pkg: String, onSubscribed: suspend () -> Unit): Flow<NetworkEntryEvent> =
        events.onSubscription { onSubscribed() }
    override suspend fun networkScriptSet(serial: String, pkg: String, source: String) = ScriptStatus(active = true, id = "abc123")
    override suspend fun networkScriptClear(serial: String, pkg: String) = true
    override suspend fun networkScriptStatus(serial: String, pkg: String) = ScriptStatus(active = false)
    override suspend fun networkScriptLogs(serial: String, pkg: String, since: Long) = ScriptLogPage(emptyList(), next = since)
    override suspend fun networkWsPush(serial: String, pkg: String, connection: String?, text: String?, base64: String?, waitMs: Long?) =
        io.yamsergey.dta.protocol.network.WsPushResult("r1-1:9", connection ?: "r1-1:4", io.yamsergey.dta.protocol.network.InjectionDelivery.READING_THREAD, 3)
    override fun networkSchemaAdd(pkg: String, name: String, input: SchemaInput) = SchemaSet(name, io.yamsergey.dta.daemon.network.proto.SchemaSource.PROTO_SOURCES, listOf("a.proto"), listOf("fixture.v1.Order"), 0)
    override fun networkSchemas(pkg: String) = emptyList<SchemaSet>()
    override fun networkSchemaRemove(pkg: String, name: String) = emptyList<BodyMapping>()
    override fun networkMappingAdd(pkg: String, mapping: BodyMappingSpec) = BodyMapping("m1", mapping.urlPattern, mapping.part, mapping.schema, mapping.type)
    override fun networkMappings(pkg: String) = emptyList<BodyMapping>()
    override fun networkMappingRemove(pkg: String, id: String) = id == "m1"
    override fun networkSchemaSync(pkg: String, origin: io.yamsergey.dta.daemon.network.proto.SchemaOrigin, files: Map<String, String>, mappings: List<io.yamsergey.dta.daemon.network.proto.DerivedMapping>, notes: List<String>) =
        io.yamsergey.dta.daemon.network.proto.SyncResult(notes = notes + "synced ${files.size} files")
    override suspend fun linksOpen(serial: String, uri: String, pkg: String?, only: Boolean) =
        io.yamsergey.dta.protocol.network.LinkOpenResult(io.yamsergey.dta.protocol.network.LinkOpenOutcome.NOT_HANDLED, reason = "fake")
    override fun linksVerification(serial: String, pkg: String) = io.yamsergey.dta.protocol.network.AppLinksState(available = true)
    /** The app's timeline (features 015/016): tests feed it with [timeline] `ingest`. */
    val timeline = io.yamsergey.dta.daemon.timeline.Timeline(session = "t1")
    val timelineReads = java.util.concurrent.CopyOnWriteArrayList<String?>()
    /** When set, `timeline.events` fails with it (the summary must never fail a tool). */
    @Volatile var timelineFailure: Exception? = null
    override suspend fun timelineEvents(serial: String, pkg: String, since: String?, sinceMs: Long?, sources: Set<String>, kinds: Set<String>, limit: Int): io.yamsergey.dta.protocol.timeline.TimelinePage {
        timelineReads += since
        timelineFailure?.let { throw it }
        return timeline.page(if (since == "end") timeline.end() else since, limit, sinceMs, sources, kinds)
    }
    /** Live timeline events for watchers; feed with [pushTimeline]. */
    val timelineLive = kotlinx.coroutines.flow.MutableSharedFlow<io.yamsergey.dta.protocol.timeline.TimelineEvent>(extraBufferCapacity = 1_000)

    /** An event the app's sidekick pushed: kept and sent to watchers. */
    fun pushTimeline(event: io.yamsergey.dta.protocol.timeline.TimelineEvent) {
        if (timeline.pushed(event)) timelineLive.tryEmit(event)
    }

    override fun timelineWatch(serial: String, pkg: String, history: Boolean, sources: Set<String>, kinds: Set<String>) =
        kotlinx.coroutines.flow.flow {
            if (history) timeline.page(null, 500, null, sources, kinds).events.forEach { emit(it) }
            timelineLive.collect { e -> if ((sources.isEmpty() || e.source in sources) && (kinds.isEmpty() || e.kind in kinds)) emit(e) }
        }
    override suspend fun timelineCauses(serial: String, pkg: String, event: String, direction: io.yamsergey.dta.protocol.timeline.ChainDirection, depth: Int, inferred: Boolean) =
        io.yamsergey.dta.daemon.timeline.CauseGraph(timeline.kept()).walk(event, direction, depth, inferred)
    override suspend fun stateCurrent(serial: String, pkg: String) = timeline.facets.current(timeline.sources)
    override suspend fun stateGraph(serial: String, pkg: String, facets: List<String>?, minHoldMs: Long, inferred: Boolean): io.yamsergey.dta.protocol.timeline.StateGraph {
        val events = timeline.currentEpochEvents()
        val current = timeline.facets.snapshot()
        return io.yamsergey.dta.daemon.timeline.StateGrouper(
            events, io.yamsergey.dta.daemon.timeline.StateGrouper.initialValues(events, current),
            current.map { it.name }.toSet() + events.flatMap { e -> e.facets.map { it.name } },
        ).graph(facets, minHoldMs)
    }
    override suspend fun statePath(serial: String, pkg: String, from: String, to: String?, facets: List<String>?) =
        io.yamsergey.dta.daemon.timeline.StatePaths(stateGraph(serial, pkg, facets, 0, false)).path(from, to)
    override fun archiveList() = io.yamsergey.dta.protocol.network.ArchiveListing(emptyList(), 0)
    override fun data(serial: String, pkg: String): io.yamsergey.dta.daemon.data.DataService = throw UnsupportedOperationException("app data")
    override fun archiveDelete(target: io.yamsergey.dta.daemon.store.DeleteTarget, confirm: Boolean) =
        io.yamsergey.dta.protocol.network.ArchiveDeletion(confirm, io.yamsergey.dta.protocol.network.ArchiveDeleted(0, 0, 0, 0))
}

/** An in-process daemon over [ops] with a connect function for [Runner]. */
class TestDaemon(val ops: CannedOperations = CannedOperations()) : AutoCloseable {
    val identity = VersionIdentity("0.1.0", "2026-10-28T10:00:00Z", 1)
    private val home: File = Files.createTempDirectory("dta-cli").toFile()
    private val state = StateFile(File(home, "daemon"))
    private val selector = Selector(ops::devices, ops::apps)
    val server = DaemonServer(
        identity, state, OperationDispatcher(ops, selector), ops, selector,
        status = { clients -> DaemonStatus(identity, 42, 65_000, home.path, AdbInfo("/sdk/adb", true), clients, ops.devices(), emptyList()) },
        onStop = {},
    ).also { it.start() }

    suspend fun connect(kind: ClientKind = ClientKind.CLI) =
        DaemonLauncher.Connected(DaemonClient.connect(server.port, state.token()!!, kind, identity), emptyList())

    override fun close() = server.stop(null)
}
