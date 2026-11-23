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
    /** The apps `apps.list` names (tests swap it). */
    @Volatile var appInfos: List<AppInfo> = listOf(AppInfo("com.example.shop", true))
    override fun apps(serial: String) = AppList(serial, "com.example.shop", appInfos)
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
    /** What network.list answers for a query (every entry unless a test narrows it). */
    var listed: (NetworkQuery) -> List<io.yamsergey.dta.protocol.network.NetworkEntry> = { entries }
    /** The cursor of the page after a network.list answer (null: everything listed). */
    var listNext: (NetworkQuery) -> String? = { null }
    /** Feature 070: the search hits a network.list page carries for its entries (null: none). */
    var listHits: (NetworkQuery, List<io.yamsergey.dta.protocol.network.NetworkEntry>) -> Map<String, List<io.yamsergey.dta.protocol.network.SearchHit>>? = { _, _ -> null }
    /** Feature 071: the scope a list page states (null: none, as before 071). */
    @Volatile var listScope: io.yamsergey.dta.protocol.network.HistoryScope? = io.yamsergey.dta.protocol.network.HistoryScope(archive = false, entries = 3, run = io.yamsergey.dta.protocol.network.ScopeRun("r1-1", 3, startedMs = 1_700_000_000_000), outside = 120)
    override suspend fun network(serial: String, pkg: String, query: NetworkQuery) =
        listed(query).let { NetworkPage(it.sortedByDescending { e -> e.timeMs }, next = listNext(query), coverage = Coverage(searched = 3, stored = 3, persisted = true), hits = listHits(query, it), scope = listScope?.copy(archive = query.archive)) }.also { queries += pkg to query }
    /** Feature 040: network.search calls, and the hits they answer with. */
    val searches = java.util.concurrent.CopyOnWriteArrayList<NetworkQuery>()
    var searchHits: List<io.yamsergey.dta.protocol.network.EntryHits> = emptyList()
    /** A search page per query, in place of [searchHits] (paging tests). */
    var searchPage: ((NetworkQuery) -> io.yamsergey.dta.protocol.network.SearchPage)? = null
    override suspend fun networkSearch(serial: String, pkg: String, query: NetworkQuery) =
        (searchPage?.invoke(query) ?: io.yamsergey.dta.protocol.network.SearchPage(searchHits)).also { searches += query }
    override suspend fun networkEntry(serial: String, pkg: String, id: String, redact: Boolean) = NetworkEntryDetail(entries.first { it.id == id })
    /** network.body calls: id, part and whether redaction was asked. */
    val bodyCalls = java.util.concurrent.CopyOnWriteArrayList<Triple<String, BodyPart, Boolean>>()
    override suspend fun networkBody(serial: String, pkg: String, id: String, part: BodyPart, output: BodyOutput, redact: Boolean, allowUnredacted: Boolean): BodyResult {
        bodyCalls += Triple(id, part, redact)
        return BodyResult(BodyInfo(part, 13, contentType = "application/json", completeness = Completeness.COMPLETE), text = "{\"items\":[]}", bytes = 12)
    }
    override suspend fun networkClear(serial: String, pkg: String) = ClearResult(clearedEntries = 3, clearedAtMs = 1L).also { cleared++ }
    @Volatile var history = io.yamsergey.dta.protocol.network.HistorySummary(
        device = io.yamsergey.dta.protocol.network.DeviceHistory(1_204, 212L shl 20, 1_000, 2_000, archivedEntries = 1_156, keepBytes = 256L shl 20, warnBytes = 512L shl 20),
        archive = io.yamsergey.dta.protocol.network.ArchiveHistory(8_930, 1_400L shl 20, 1_000, 2_000),
        hostOnly = 3_896,
    )
    override suspend fun networkHistory(serial: String, pkg: String) = history
    override suspend fun networkHistorySet(serial: String, pkg: String, keepBytes: Long?, warnBytes: Long?) =
        io.yamsergey.dta.protocol.network.HistorySettings(keepBytes ?: (256L shl 20), warnBytes ?: (512L shl 20))
    override fun networkEvents(serial: String, pkg: String, onSubscribed: suspend () -> Unit): Flow<NetworkEntryEvent> =
        events.onSubscription { onSubscribed() }
    /** D-079: the scopes live watches asked for; each answered as the current run with [outsideEntries] kept beside it. */
    val watchScopes = java.util.concurrent.CopyOnWriteArrayList<NetworkQuery>()
    @Volatile var outsideEntries: Long = 1_234
    override suspend fun networkScope(serial: String, pkg: String, query: NetworkQuery) =
        io.yamsergey.dta.protocol.network.HistoryScope(query.archive, 2, outside = outsideEntries.takeIf { !query.archive && it > 0 }).also { watchScopes += query }
    /** Feature 067: live decisions as a daemon with live views makes them (null: an older daemon, none). */
    @Volatile var decide: ((NetworkQuery, io.yamsergey.dta.protocol.network.NetworkEntry) -> Pair<io.yamsergey.dta.protocol.network.EntryDecision, Boolean>)? = null
    val views = java.util.concurrent.CopyOnWriteArrayList<String?>()
    override suspend fun network(serial: String, pkg: String, query: NetworkQuery, view: String?) = network(serial, pkg, query).also { views += view }
    override fun networkDecide(serial: String, pkg: String, view: String, query: NetworkQuery, entry: io.yamsergey.dta.protocol.network.NetworkEntry, stop: io.yamsergey.dta.protocol.network.PatternStop, socketUrl: (String) -> String?) =
        decide?.invoke(query, entry)

    /** Feature 071: streamed searches — the queries asked, and the walk each runs (default: the hits of [searchHits] as rows). */
    val finds = java.util.concurrent.CopyOnWriteArrayList<NetworkQuery>()
    val findRunner = io.yamsergey.dta.daemon.network.SearchRunner()
    @Volatile var findWalk: ((NetworkQuery, Int?) -> (io.yamsergey.dta.protocol.network.PatternStop, (io.yamsergey.dta.protocol.network.FoundItem) -> Unit) -> io.yamsergey.dta.protocol.network.FindEnd)? = null
    /** Walks that ended because they were stopped. */
    val findsStopped = java.util.concurrent.atomic.AtomicInteger()
    @Volatile var findScope = io.yamsergey.dta.protocol.network.HistoryScope(archive = false, entries = 3, run = io.yamsergey.dta.protocol.network.ScopeRun("r1-1", 3, startedMs = 1_700_000_000_000), outside = 120)
    override suspend fun networkFind(
        serial: String, pkg: String, query: NetworkQuery, limit: Int?,
        credit: io.yamsergey.dta.daemon.network.Credit,
        waitingAt: suspend (Int) -> Unit,
        started: suspend (io.yamsergey.dta.protocol.network.HistoryScope, io.yamsergey.dta.protocol.network.SavedFiltersApplied?) -> Unit,
        send: suspend (List<io.yamsergey.dta.protocol.network.FoundItem>) -> Unit,
    ): io.yamsergey.dta.daemon.DtaDaemon.FindResult {
        finds += query
        val scope = findScope.copy(archive = query.archive)
        // By default the rows a list of [query] gives here, newest first, with the hits a search gives here (the old answers, streamed).
        val walk = findWalk?.invoke(query, limit) ?: { _, onItem ->
            queries += pkg to query
            searches += query
            val rows = listed(query).sortedByDescending { e -> e.timeMs }
            val page = searchPage?.invoke(query)?.entries ?: searchHits
            val hitsOf = page.associate { it.id to it.hits } + (listHits(query, rows) ?: emptyMap())
            val given = if (searchPage != null || searchHits.isNotEmpty()) rows.filter { it.id in hitsOf } .ifEmpty { if (searchHits.isEmpty() && searchPage == null) rows else emptyList() } else rows
            given.forEach { e -> onItem(io.yamsergey.dta.protocol.network.FoundItem(e, hitsOf[e.id]?.ifEmpty { null })) }
            io.yamsergey.dta.protocol.network.FindEnd(null, null)
        }
        val end = try {
            io.yamsergey.dta.daemon.network.FindPipeline.run(findRunner, credit, waitingAt, walk, admitted = { started(scope, null) }, send = send)
        } catch (e: kotlinx.coroutines.CancellationException) {
            findsStopped.incrementAndGet(); throw e
        }
        return io.yamsergey.dta.daemon.DtaDaemon.FindResult(end, scope, null, null)
    }
    override suspend fun networkScriptSet(serial: String, pkg: String, source: String) = ScriptStatus(active = true, id = "abc123")
    /** What network.script.status answers; a clear makes it inactive. */
    @Volatile var scriptStatus = ScriptStatus(active = false)
    override suspend fun networkScriptClear(serial: String, pkg: String) = true.also { scriptStatus = ScriptStatus(active = false) }
    override suspend fun networkScriptStatus(serial: String, pkg: String) = scriptStatus
    override suspend fun networkScriptLogs(serial: String, pkg: String, since: Long) = ScriptLogPage(emptyList(), next = since)
    override suspend fun networkWsPush(serial: String, pkg: String, connection: String?, text: String?, base64: String?, waitMs: Long?) =
        io.yamsergey.dta.protocol.network.WsPushResult("r1-1:9", connection ?: "r1-1:4", io.yamsergey.dta.protocol.network.InjectionDelivery.READING_THREAD, 3)
    override fun networkSchemaAdd(pkg: String, name: String, input: SchemaInput) = SchemaSet(name, io.yamsergey.dta.daemon.network.proto.SchemaSource.PROTO_SOURCES, listOf("a.proto"), listOf("fixture.v1.Order"), 0)
    override fun networkSchemas(pkg: String) = emptyList<SchemaSet>()
    override fun networkSchemaRemove(pkg: String, name: String) = emptyList<BodyMapping>()
    override fun networkMappingAdd(pkg: String, mapping: BodyMappingSpec) = BodyMapping("m1", mapping.urlPattern, mapping.part, mapping.schema, mapping.type)
    override fun networkMappings(pkg: String) = emptyList<BodyMapping>()
    override fun networkMappingRemove(pkg: String, id: String) = id == "m1"
    override fun networkFormatAdd(pkg: String, spec: io.yamsergey.dta.protocol.network.FormatRuleSpec) = spec.toRule("f1", 1)
    override fun networkFormats(pkg: String) = emptyList<io.yamsergey.dta.protocol.network.FormatRule>()
    override fun networkFormatRemove(pkg: String, id: String) = id == "f1"
    /** Feature 043: the saved set, as a client changes it. */
    val savedWords = java.util.concurrent.CopyOnWriteArrayList<String>()
    override fun networkFilters(pkg: String) = io.yamsergey.dta.protocol.network.SavedFilters(saved = savedWords.toList())
    override fun networkFiltersAdd(pkg: String, words: String): io.yamsergey.dta.protocol.network.SavedFilters {
        savedWords += io.yamsergey.dta.protocol.network.QueryLine.commit(words).words
        return networkFilters(pkg)
    }
    override fun networkFiltersRemove(pkg: String, word: String) = savedWords.remove(word)
    override suspend fun networkFormatTest(serial: String, pkg: String, spec: io.yamsergey.dta.protocol.network.FormatRuleSpec, connection: String?) =
        io.yamsergey.dta.protocol.network.FormatTestResult(0, 0, emptyList(), emptyList())
    override fun networkSchemaSync(pkg: String, origin: io.yamsergey.dta.daemon.network.proto.SchemaOrigin, files: Map<String, String>, mappings: List<io.yamsergey.dta.daemon.network.proto.DerivedMapping>, notes: List<String>) =
        io.yamsergey.dta.daemon.network.proto.SyncResult(notes = notes + "synced ${files.size} files")
    override suspend fun linksOpen(serial: String, uri: String, pkg: String?, only: Boolean) =
        io.yamsergey.dta.protocol.network.LinkOpenResult(io.yamsergey.dta.protocol.network.LinkOpenOutcome.NOT_HANDLED, reason = "fake")
    override fun linksVerification(serial: String, pkg: String) = io.yamsergey.dta.protocol.network.AppLinksState(available = true)
    /** The app's timeline (features 015/016): tests feed it with [timeline] `ingest`. */
    val timeline = io.yamsergey.dta.daemon.timeline.Timeline(session = "t1")
    val timelineReads = java.util.concurrent.CopyOnWriteArrayList<String?>()
    /** Feature 065: the opt-out each timeline call was made with. */
    val timelineIgnored = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
    val sequenceIgnored = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
    /** Whether each `timeline.sequence` was asked redacted. */
    val sequenceRedacted = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
    val hiddenAsks = java.util.concurrent.atomic.AtomicInteger()
    /** When set, `timeline.events` fails with it (the summary must never fail a tool). */
    @Volatile var timelineFailure: Exception? = null
    override suspend fun timelineEvents(serial: String, pkg: String, since: String?, sinceMs: Long?, sources: Set<String>, kinds: Set<String>, limit: Int, ignoreSavedFilters: Boolean): io.yamsergey.dta.protocol.timeline.TimelinePage {
        timelineReads += since
        timelineIgnored += ignoreSavedFilters
        timelineFailure?.let { throw it }
        return timeline.page(if (since == "end") timeline.end() else since, limit, sinceMs, sources, kinds)
    }
    /** Live timeline events for watchers; feed with [pushTimeline]. */
    val timelineLive = kotlinx.coroutines.flow.MutableSharedFlow<io.yamsergey.dta.protocol.timeline.TimelineEvent>(extraBufferCapacity = 1_000)

    /** An event the app's sidekick pushed: kept and sent to watchers. */
    fun pushTimeline(event: io.yamsergey.dta.protocol.timeline.TimelineEvent) {
        if (timeline.pushed(event)) timelineLive.tryEmit(event)
    }

    /** Feature 057: the screen signal a test drives; [signalWatchers] counts who listens now. */
    val signal = kotlinx.coroutines.flow.MutableSharedFlow<io.yamsergey.dta.daemon.layout.LayoutSignalWatch.Update>(extraBufferCapacity = 64)
    val signalWatchers = java.util.concurrent.atomic.AtomicInteger()
    override fun layoutSignal(serial: String, pkg: String) = kotlinx.coroutines.flow.flow {
        signalWatchers.incrementAndGet()
        try {
            emit(io.yamsergey.dta.daemon.layout.LayoutSignalWatch.Update.State(io.yamsergey.dta.protocol.layout.ScreenSignal.WATCHING))
            signal.collect { emit(it) }
        } finally { signalWatchers.decrementAndGet() }
    }
    override fun timelineWatch(serial: String, pkg: String, history: Boolean, sources: Set<String>, kinds: Set<String>, ignoreSavedFilters: Boolean) =
        kotlinx.coroutines.flow.flow<io.yamsergey.dta.daemon.timeline.TimelineWatchItem> {
            watchIgnored += ignoreSavedFilters
            if (history) timeline.page(null, 500, null, sources, kinds).events.forEach { emit(io.yamsergey.dta.daemon.timeline.TimelineWatchItem.Event(it)) }
            timelineLive.collect { e -> if ((sources.isEmpty() || e.source in sources) && (kinds.isEmpty() || e.kind in kinds)) emit(io.yamsergey.dta.daemon.timeline.TimelineWatchItem.Event(e)) }
        }
    /** Feature 065: the opt-out each `timeline.watch` was asked with. */
    val watchIgnored = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
    var sequence = io.yamsergey.dta.protocol.timeline.SequencePage(emptyList(), 0, emptyList())
    /** Feature 065: what `timeline.hidden` answers; an opt-out answers nothing hidden. */
    @Volatile var hidden = io.yamsergey.dta.protocol.timeline.TimelineHidden()
    override suspend fun timelineHidden(serial: String, pkg: String, ignoreSavedFilters: Boolean): io.yamsergey.dta.protocol.timeline.TimelineHidden {
        hiddenAsks.incrementAndGet()
        return if (ignoreSavedFilters) io.yamsergey.dta.protocol.timeline.TimelineHidden(hidden.saved?.copy(ignored = true, hidden = null)) else hidden
    }
    override suspend fun timelineSequence(serial: String, pkg: String, limit: Int, q: String?, sinceMs: Long?, flow: String?, ignoreSavedFilters: Boolean, redact: Boolean): io.yamsergey.dta.protocol.timeline.SequencePage {
        sequenceIgnored += ignoreSavedFilters
        sequenceRedacted += redact
        return sequence
    }
    private val flowStore by lazy { io.yamsergey.dta.daemon.timeline.FlowStore(java.nio.file.Files.createTempDirectory("test-flows").toFile()) }
    override suspend fun timelineFlowStart(serial: String, pkg: String, name: String?) = flowStore.start(serial, pkg, name, sequence.steps.lastOrNull()?.wallMs)
    override suspend fun timelineFlowEnd(serial: String, pkg: String) = flowStore.end(serial, pkg, sequence.steps.lastOrNull()?.wallMs)
    override suspend fun timelineFlowList(serial: String, pkg: String) = io.yamsergey.dta.protocol.timeline.FlowList(flowStore.list(serial, pkg).map { io.yamsergey.dta.protocol.timeline.SequenceFlow(it) })
    override fun timelineFlowRename(serial: String, pkg: String, flow: String, name: String) = flowStore.rename(serial, pkg, flow, name)
    override fun timelineFlowRemove(serial: String, pkg: String, flow: String) = flowStore.remove(serial, pkg, flow)
    override suspend fun timelineCauses(serial: String, pkg: String, event: String, direction: io.yamsergey.dta.protocol.timeline.ChainDirection, depth: Int, inferred: Boolean, ignoreSavedFilters: Boolean) =
        io.yamsergey.dta.daemon.timeline.CauseGraph(timeline.kept()).walk(event, direction, depth, inferred)
    override suspend fun stateCurrent(serial: String, pkg: String) = timeline.facets.current(timeline.sources)
    var timelineOn = false
    override suspend fun timelineEnabled(serial: String, pkg: String, on: Boolean?): io.yamsergey.dta.protocol.timeline.TimelineSwitch {
        on?.let { timelineOn = it }
        return io.yamsergey.dta.protocol.timeline.TimelineSwitch(timelineOn, timelineOn)
    }

    // Feature 060: browser capture switches kept in memory.
    var webSwitch = io.yamsergey.dta.protocol.web.WebSwitch(webview = false, customTabs = false)
    override suspend fun webCapture(serial: String, pkg: String, webview: Boolean?, customTabs: Boolean?): io.yamsergey.dta.protocol.web.WebSwitch {
        webSwitch = webSwitch.copy(webview = webview ?: webSwitch.webview, customTabs = customTabs ?: webSwitch.customTabs)
        return webSwitch
    }
    override suspend fun webStatus(serial: String, pkg: String) = io.yamsergey.dta.protocol.web.WebStatus(
        io.yamsergey.dta.protocol.web.WebSourceStatus(if (webSwitch.webview) "waiting" else "off"),
        io.yamsergey.dta.protocol.web.WebSourceStatus(if (webSwitch.customTabs) "waiting" else "off"),
        switch = webSwitch,
    )
    override suspend fun webReload(serial: String, pkg: String, page: String) = false
    override suspend fun webDevtools(serial: String, pkg: String) = io.yamsergey.dta.protocol.web.WebDevtools(emptyList(), "no WebView")
    private val selections = io.yamsergey.dta.daemon.layout.SelectionStore()
    override suspend fun layoutSelection(serial: String, pkg: String, fresh: Boolean) = selections.read(serial, pkg)
    override suspend fun layoutSelect(serial: String, pkg: String, mode: String, ids: List<Long>, at: String?, elements: List<io.yamsergey.dta.protocol.layout.SelectedElement>, by: String) =
        selections.change(serial, pkg, mode, elements, by)
    override fun layoutSelections(serial: String, pkg: String) = selections.flow(serial, pkg)
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
class TestDaemon(
    val ops: CannedOperations = CannedOperations(),
    val identity: VersionIdentity = VersionIdentity("0.1.0", "2026-10-28T10:00:00Z", 1),
) : AutoCloseable {
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
