package io.yamsergey.dta.studio.network

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.present.Statement
import io.yamsergey.dta.present.Statements
import io.yamsergey.dta.present.context.Connection
import io.yamsergey.dta.present.network.LiveEvent
import io.yamsergey.dta.present.network.LiveList
import io.yamsergey.dta.present.network.HitNavigator
import io.yamsergey.dta.present.network.KindChoice
import io.yamsergey.dta.present.network.QueryLineModel
import io.yamsergey.dta.protocol.network.SavedFilters
import io.yamsergey.dta.protocol.network.SavedFiltersApplied
import io.yamsergey.dta.protocol.network.SearchPage
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.studio.ContextController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Everything the Network view shows besides the selected entry. */
data class NetworkViewState(
    val device: String? = null,
    val app: String? = null,
    val list: LiveList = LiveList(),
    /** Feature 040: the query line — filters and search words — is the view's whole query. */
    val line: QueryLineModel = QueryLineModel(),
    /** D-021: the last line without problems — what the list, the watch and the search ask (an invalid line never reaches the daemon). */
    val applied: QueryLineModel = QueryLineModel(),
    /** Statements about the list as a whole: eviction, runs not searched, degradation. */
    val statements: List<Statement> = emptyList(),
    /** Feature 040: the search words' hits, in the list's order, and the current one. */
    val hits: HitNavigator = HitNavigator(),
    /** Hits run top to bottom as the list shows newest first (the list's order switch). */
    val hitsNewestFirst: Boolean = true,
    val searching: Boolean = false,
    /** What the search could not cover (not-searched bodies, an app that cannot search). */
    val searchStatements: List<Statement> = emptyList(),
    /** Feature 043: the kind switch, a filter of this session beside the line. */
    val kind: KindChoice = KindChoice.ALL,
    /** Feature 043: the app's saved filters (chips) and the ones it had before. */
    val saved: SavedFilters = SavedFilters(),
    /** Feature 043: what the saved filters did to the last list. */
    val savedApplied: SavedFiltersApplied? = null,
    val redact: Boolean = false,
    /** An archive scope (`all`, an epoch id…): the archived history instead of live traffic (feature 011). */
    val archive: String? = null,
    /** Feature 071: the Archive switch, off at each IDE start: off, the app's current run; on, everything this computer keeps. */
    val archiveOn: Boolean = false,
    /** Feature 071: the streamed search's state while one runs or ran for the line (null: a plain list). */
    val find: io.yamsergey.dta.protocol.network.FindState? = null,
    val findError: String? = null,
    /** Feature 071: the search holds back until the list is scrolled to its oldest row (it holds many rows). */
    val findHeld: Boolean = false,
    /** Feature 071: what the last answer covered (the caption's numbers). */
    val scope: io.yamsergey.dta.protocol.network.HistoryScope? = null,
    /** Feature 071: the app started again while the tab showed it (board 4); null when not. */
    val restart: String? = null,
    /** Feature 071: the archived epoch of each archived row shown (board 3's separators), by entry id. */
    val epochOf: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    /** D-022: an older page is being loaded (Load older, or the list scrolled to its oldest edge). */
    val loadingOlder: Boolean = false,
    /** D-023: bumped when the selected entry changed live (its own update, a message of its socket): the detail reads it again. */
    val selectedRevision: Long = 0,
    /** D-024: the app's network script while one is active (the view row's script control); null when none. */
    val script: io.yamsergey.dta.protocol.network.ScriptStatus? = null,
    val watching: Boolean = false,
    /** D-083: a daemon answers (not stopped, failed, in a version conflict or being replaced); off, the tab shows nothing of a run. */
    val online: Boolean = false,
    val error: String? = null,
    /** Feature 068: a wait of the archiving that lasts, for the IDE's background task; null when none. */
    val wait: BackgroundWait? = null,
)

/** Feature 068: what the IDE's background task says: [text], [detail] and how far ([fraction], 0 to 1; null when not known). */
data class BackgroundWait(val text: String, val detail: String?, val fraction: Double?)

/**
 * The Network view's live source (R7, FR-006/007/010): the newest page of `network.list`
 * (500 entries), then `network.watch` for the same filters; entries merge by id in [LiveList].
 * A filter change is a new query; a device or app change drops the list and its watch.
 */
class NetworkController(private val context: ContextController, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(NetworkViewState())
    val state: StateFlow<NetworkViewState> = mutable.asStateFlow()
    private var source: Job? = null

    init {
        scope.launch {
            context.state
                .map { Target(it.device, it.app, it.connection != Connection.NO_DTA, it.generation, context.client) }
                .distinctUntilChanged()
                .collect { reload(it) }
        }
        // D-054: the app ended (or its link was lost) and connected again: perhaps a new run, which the
        // search or list must start over in at once, even when no entry of the new run ever matches.
        scope.launch {
            val last = HashMap<Pair<String, String>, String>()
            context.appConnections.collect { e ->
                val before = last.put(e.device to e.app, e.state)
                if (e.state == "connected" && (before == "ended" || before == "lost")) appStarted(e.device, e.app)
            }
        }
    }

    /** D-054 (071 restart rule): the shown app connected again; a new run is listed at once, the banner names the one before. */
    private fun appStarted(device: String, app: String) {
        val s = mutable.value
        if (s.device != device || s.app != app || s.archive != null) return
        // With the switch on the scope is the whole history: only a search that failed while the app was down starts again.
        if (s.archiveOn && s.find != io.yamsergey.dta.protocol.network.FindState.FAILED && s.error == null) return
        if (!s.archiveOn) {
            if (restartFrom == null) restartFrom = s.scope?.run
            restartAsks.set(RESTART_ASKS)
        }
        requery()
    }

    private data class Target(val device: String?, val app: String?, val hasDta: Boolean, val generation: Int, val client: DaemonClient?)

    fun list(event: LiveEvent) = mutable.update { it.copy(list = it.list.reduce(event)).followSelected(event) }

    /** D-023: a live event about the selected entry, or a message of the selected socket, is a new revision of the selection. */
    private fun NetworkViewState.followSelected(event: LiveEvent): NetworkViewState {
        val entry = when (event) { is LiveEvent.Arrived -> event.entry; is LiveEvent.Updated -> event.entry; else -> return this }
        val selected = list.selectedId ?: return this
        fun field(k: String) = (entry[k] as? JsonPrimitive)?.content
        return if (field("id") == selected || field("connectionId") == selected) copy(selectedRevision = selectedRevision + 1) else this
    }

    /**
     * The query line as typed. A line with problems changes nothing (the field shows them);
     * a changed filter part is a new query, a changed search part only a new search.
     */
    fun setQuery(text: String) {
        // D-021: compared with what the list asks now, not with an invalid line typed meanwhile.
        val before = mutable.value.applied
        val line = mutable.value.line.withText(text)
        if (line.parsed.problems.isNotEmpty()) { mutable.update { it.copy(line = line) }; return }
        // New words are being searched from now on: never "No matches" (or the old words' hits) while the list loads.
        val newWords = before.parsed.terms != line.parsed.terms
        mutable.update { if (newWords && line.hasSearch) it.copy(line = line, applied = line, hits = HitNavigator(), searching = true) else it.copy(line = line, applied = line) }
        val sameFilters = before.parsed.problems.isEmpty() && before.parsed.filter == line.parsed.filter && before.parsed.exclude == line.parsed.exclude
        // Feature 041: only the matches are listed, so other words are other rows.
        if (sameFilters && before.parsed.terms == line.parsed.terms) search() else requery()
    }

    /** The All / HTTP / WebSocket switch. */
    fun setKind(choice: KindChoice) {
        if (mutable.value.kind == choice) return
        mutable.update { it.copy(kind = choice) }
        requery()
    }

    /**
     * Feature 043, Enter: the line's filter words become the app's saved filters and leave the
     * line. False when it had none to save (Enter then steps to the next hit).
     */
    fun commitLine(text: String): Boolean {
        val commit = mutable.value.line.withText(text).commit()
        if (commit.words.isEmpty()) return false
        val rest = mutable.value.line.withText(commit.rest)
        mutable.update { it.copy(line = rest, applied = if (rest.parsed.problems.isEmpty()) rest else it.applied) }
        changeSaved("network.filters.add", "words", commit.words.joinToString(" "))
        return true
    }

    /** × on a chip: no longer applied; it stays among the recent ones. */
    fun removeSaved(word: String) = changeSaved("network.filters.remove", "word", word)

    /** A click on a chip: its word back in the line for editing; Enter saves it again. */
    fun editSaved(word: String) {
        val text = listOf(word, mutable.value.line.text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        mutable.update { it.line.withText(text).let { line -> it.copy(line = line, applied = if (line.parsed.problems.isEmpty()) line else it.applied) } }
        changeSaved("network.filters.remove", "word", word)
    }

    private fun changeSaved(op: String, param: String, value: String) {
        val c = context.client ?: return
        val s = mutable.value
        if (s.app == null) return
        scope.launch {
            runCatching { c.call(op, JsonObject(target(s) + mapOf(param to JsonPrimitive(value)))).result }
                .onSuccess { result -> mutable.update { it.copy(saved = DtaJson.decodeFromJsonElement(SavedFilters.serializer(), result), error = null) }; requery() }
                .onFailure { e -> mutable.update { it.copy(error = e.message ?: e.javaClass.simpleName) } }
        }
    }

    /**
     * Feature 043: what another client changed shows without a restart, and the count of what the
     * filters hide follows new traffic. Only while the app has saved filters or might get some.
     */
    private fun followSaved(client: DaemonClient) {
        savedJob?.cancel()
        savedClient = client
        savedJob = scope.launch {
            while (true) {
                val s = mutable.value
                if (s.app != null && s.device != null) {
                    val fresh = runCatching { DtaJson.decodeFromJsonElement(SavedFilters.serializer(), client.call("network.filters.list", target(s)).result) }.getOrNull()
                    if (fresh != null && fresh != s.saved) {
                        val rows = fresh.saved != s.saved.saved
                        mutable.update { it.copy(saved = fresh) }
                        if (rows) requery()
                    } else if (fresh != null && fresh.saved.isNotEmpty() && s.archive == null) {
                        runCatching { client.call("network.list", JsonObject(params(s).toMap() + ("limit" to JsonPrimitive(1)))).result.jsonObject }
                            .onSuccess { page -> mutable.update { it.copy(savedApplied = applied(page)) } }
                    }
                }
                kotlinx.coroutines.delay(SAVED_POLL_MS)
            }
        }
    }

    private var savedJob: Job? = null

    /** D-024: how often the app's network script is asked (it changes rarely: installed or stopped here or by an agent). */
    @Volatile internal var scriptPollMs = 5_000L

    /** Stops the app's network script (`network.script.clear`); the control goes. */
    fun stopScript() {
        val c = context.client ?: return
        val s = mutable.value
        if (s.app == null || s.device == null) return
        scope.launch {
            runCatching { c.call("network.script.clear", target(s)) }
                .onSuccess { mutable.update { it.copy(script = null) }; readScript(c) }
                .onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e; mutable.update { it.copy(error = e.message ?: e.javaClass.simpleName) } }
        }
    }

    private var scriptJob: Job? = null
    private var scriptClient: DaemonClient? = null

    /** D-024: the app's network script, followed while the tab shows live traffic of an app. */
    private fun followScript(client: DaemonClient) {
        scriptJob?.cancel()
        scriptClient = client
        scriptJob = scope.launch {
            while (true) {
                readScript(client)
                kotlinx.coroutines.delay(scriptPollMs)
            }
        }
    }

    private suspend fun readScript(client: DaemonClient) {
        val s = mutable.value
        val script = if (s.app != null && s.device != null && s.archive == null) runCatching {
            DtaJson.decodeFromJsonElement(io.yamsergey.dta.protocol.network.ScriptStatus.serializer(), client.call("network.script.status", target(s)).result)
        }.getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) throw e; s.script }?.takeIf { it.active } else null
        if (mutable.value.script != script) mutable.update { it.copy(script = script) }
    }
    private var waitJob: Job? = null
    private var waitClient: DaemonClient? = null

    /** Feature 068: how often the archiving's state is asked, and how long a wait lasts before it is shown. */
    @Volatile internal var waitPollMs = 1_000L
    @Volatile internal var waitShowAfterMs = 3_000L

    /**
     * Feature 068 (research R8): the archiving's waits (the app reading its history after it starts,
     * this computer catching up) are not marks in the tab; one that lasts is the IDE's background
     * task, with how far it is, and it ends when the wait does.
     */
    private fun followWait(client: DaemonClient) {
        waitJob?.cancel()
        waitClient = client
        waitJob = scope.launch {
            var since: Long? = null
            var most = 0L
            while (true) {
                val s = mutable.value
                val device = if (s.app != null && s.device != null && s.archive == null) runCatching {
                    DtaJson.decodeFromJsonElement(io.yamsergey.dta.protocol.network.HistorySummary.serializer(), client.call("network.history", target(s)).result).device
                }.getOrNull() else null
                val behind = device?.catchingUp
                val waiting = device != null && (device.reading || behind != null)
                if (!waiting) {
                    since = null
                    most = 0
                    if (mutable.value.wait != null) mutable.update { it.copy(wait = null) }
                } else {
                    val now = System.currentTimeMillis()
                    val start = since ?: now.also { since = it }
                    if (behind != null) most = maxOf(most, behind)
                    if (now - start >= waitShowAfterMs) {
                        val app = s.app!!
                        val wait = if (behind != null) {
                            BackgroundWait("DTA: catching up $app", "$behind entries to read from the device", if (most > 0) 1.0 - behind.toDouble() / most else 0.0)
                        } else {
                            BackgroundWait("DTA: reading the history of $app", "the app reads its earlier runs after it starts", null)
                        }
                        if (mutable.value.wait != wait) mutable.update { it.copy(wait = wait) }
                    }
                }
                kotlinx.coroutines.delay(waitPollMs)
            }
        }
    }
    private var savedClient: DaemonClient? = null

    private fun applied(page: JsonObject): SavedFiltersApplied? =
        page["saved"]?.let { DtaJson.decodeFromJsonElement(SavedFiltersApplied.serializer(), it) }

    /** The list's order switch: hits are stepped through top to bottom. */
    fun setHitsNewestFirst(newestFirst: Boolean) {
        if (mutable.value.hitsNewestFirst == newestFirst) return
        mutable.update { it.copy(hitsNewestFirst = newestFirst) }
        search()
    }

    /** Next / previous hit: selected in the list (older pages loaded until it is there). */
    fun nextHit() = moveHit(forward = true)

    fun previousHit() = moveHit(forward = false)

    private fun moveHit(forward: Boolean) {
        val s = mutable.value
        val h = s.hits
        // Feature 071: past the last hit found while it searches, the step waits for the next find.
        val atEnd = if (s.hitsNewestFirst) forward && h.index == h.total - 1 else !forward && h.index == 0
        if (s.find == io.yamsergey.dta.protocol.network.FindState.SEARCHING && (h.total == 0 || atEnd)) { pendingStep = forward; findStream?.takeIf { it.waitingForViewer }?.more(); return }
        // Feature 070: the hits are those of the rows loaded; past the oldest loaded one the list's next page loads (no wrap while more can load).
        val pastOldest = h.total > 0 && s.list.older != null && if (s.hitsNewestFirst) forward && h.index == h.total - 1 else !forward && h.index == 0
        if (pastOldest) { loadOlderThenStep(forward); return }
        val moved = h.let { if (forward) it.next() else it.previous() }
        mutable.update { it.copy(hits = moved) }
        moved.current?.id?.let(::selectLoaded)
    }

    /**
     * Feature 070: an older page shown above, with its rows' hits as `network.list` gives them (the line's
     * search words) — no search of every loaded row again. False when the page brought no hits to join
     * (no search words, or none given): the caller searches as before.
     */
    private fun older(page: JsonObject): Boolean {
        val hits = page["hits"] as? JsonObject
        if (!mutable.value.applied.finds || hits == null) { list(LiveEvent.Older(page)); return false }
        val times = (page["entries"] as? kotlinx.serialization.json.JsonArray).orEmpty().associate { e ->
            val o = e.jsonObject
            (o["id"] as JsonPrimitive).content to ((o["timeMs"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L)
        }
        val list = kotlinx.serialization.builtins.ListSerializer(io.yamsergey.dta.protocol.network.SearchHit.serializer())
        val joined = hits.map { (id, h) -> io.yamsergey.dta.protocol.network.EntryHits(id, times[id] ?: 0L, DtaJson.decodeFromJsonElement(list, h)) }
        mutable.update {
            val rows = it.list.reduce(LiveEvent.Older(page))
            it.copy(list = rows, hits = it.hits.withOlder(joined, it.hitsNewestFirst, more = rows.older != null))
        }
        return true
    }

    private var stepJob: Job? = null

    /** Loads the list's next older page, searches the rows loaded again, then steps onto the first hit it brought. */
    private fun loadOlderThenStep(forward: Boolean) {
        if (stepJob?.isActive == true) return
        val c = context.client ?: return
        stepJob = scope.launch {
            val s = mutable.value
            val cursor = s.list.older ?: return@launch
            val r = runCatching { c.call("network.list", params(s, before = cursor)) }.getOrNull() ?: return@launch
            if (!older(r.result.jsonObject)) { searchJob?.cancel(); runSearch(mutable.value, c) }
            val moved = mutable.value.hits.let { if (forward) it.next() else it.previous() }
            mutable.update { it.copy(hits = moved) }
            moved.current?.id?.let(::selectLoaded)
        }
    }

    /** Selects [id], loading older pages (at most [REVEAL_PAGES]) while it is not loaded yet. */
    private fun selectLoaded(id: String) {
        val c = context.client
        if (mutable.value.list.rows.any { it.id == id } || c == null) { list(LiveEvent.Selected(id)); return }
        scope.launch {
            var pages = 0
            var unsearched = false
            while (mutable.value.list.rows.none { it.id == id } && pages++ < REVEAL_PAGES) {
                val cursor = mutable.value.list.older ?: break
                val r = runCatching { c.call("network.list", params(mutable.value, before = cursor)) }.getOrNull() ?: break
                if (!older(r.result.jsonObject)) unsearched = true
            }
            list(LiveEvent.Selected(id))
            if (unsearched && mutable.value.applied.hasSearch) search()
        }
    }

    private var searchJob: Job? = null

    /** Runs the line's search words (`network.search`), or clears the hits when there are none. */
    fun search(debounceMs: Long = 0) {
        searchJob?.cancel()
        val s = mutable.value
        val c = context.client
        if (!s.applied.finds || c == null || s.device == null || s.app == null) {
            mutable.update { it.copy(hits = HitNavigator(), searching = false, searchStatements = emptyList()) }
            return
        }
        // Feature 071: a streamed search brings its hits with its rows; live entries bring theirs (067).
        if (streamed(s)) return
        searchJob = scope.launch {
            if (debounceMs > 0) kotlinx.coroutines.delay(debounceMs)
            runSearch(mutable.value, c)
        }
    }

    /** Feature 071: the line searches (words, or bodies by text or pattern): its rows stream in as found. */
    private fun streamed(s: NetworkViewState): Boolean {
        // D-021: what the list asks is the last valid line.
        if (s.applied.parsed.problems.isNotEmpty()) return false
        return s.applied.finds
    }

    private var findStream: SearchStream? = null
    /** The run the tab showed when the app started again, named by the banner once the new run is listed. */
    @Volatile private var restartFrom: io.yamsergey.dta.protocol.network.ScopeRun? = null

    /** Feature 071: the Archive switch; a search running in the old scope stops and starts over in the new one. */
    fun setArchiveOn(on: Boolean) {
        if (mutable.value.archiveOn == on) return
        restartFrom = null
        restartAsks.set(0)
        mutable.update { it.copy(archiveOn = on, restart = null) }
        requery()
    }

    /** Feature 071: the Stop link — the search ends here and on the daemon, and says stopped, never done. */
    fun stopSearch() {
        val stream = findStream ?: return
        findStream = null
        stream.stop()
        mutable.update { if (it.find == io.yamsergey.dta.protocol.network.FindState.SEARCHING || it.find == io.yamsergey.dta.protocol.network.FindState.WAITING) it.copy(find = io.yamsergey.dta.protocol.network.FindState.STOPPED, searching = false, findHeld = false, hits = it.hits.copy(more = false)) else it }
    }

    /** Feature 071: the banner's close. */
    fun dismissRestart() = mutable.update { it.copy(restart = null) }

    /**
     * Feature 071: [query]'s rows streamed into the list, newest first, with their hits; live entries keep
     * joining on top (067), merged by id. A newer query, the switch or the source stops it first.
     */
    private fun startFind(client: io.yamsergey.dta.client.DaemonClient) {
        findStream?.stop()
        val s = mutable.value
        val params = JsonObject(params(s).toMap() - "limit" - "before" - "view")
        val empty = JsonObject(mapOf("entries" to kotlinx.serialization.json.JsonArray(emptyList())))
        val e = loaded(empty)
        mutable.update { it.copy(list = it.list.reduce(e), find = io.yamsergey.dta.protocol.network.FindState.SEARCHING, findError = null, findHeld = false, searching = true, hits = HitNavigator(more = true), searchStatements = emptyList(), loading = false, epochOf = emptyMap()) }
        lateinit var stream: SearchStream
        stream = SearchStream(client, scope, params, onEvent = { ev -> if (findStream === stream) found(ev, stream) }, onError = { err ->
            if (findStream === stream) mutable.update { it.copy(find = io.yamsergey.dta.protocol.network.FindState.FAILED, findError = err.message, searching = false, error = if (superseded(err)) it.error else err.message ?: err.javaClass.simpleName) }
        })
        findStream = stream
        stream.start()
    }

    /** One event of the running stream. */
    private fun found(e: io.yamsergey.dta.protocol.network.FoundEvent, stream: SearchStream) {
        val items = e.items.orEmpty()
        val hitList = kotlinx.serialization.builtins.ListSerializer(io.yamsergey.dta.protocol.network.SearchHit.serializer())
        mutable.update { st ->
            var next = st
            if (items.isNotEmpty()) {
                val entries = items.map { DtaJson.encodeToJsonElement(io.yamsergey.dta.protocol.network.NetworkEntry.serializer(), it.entry) }
                val page = JsonObject(mapOf("entries" to kotlinx.serialization.json.JsonArray(entries)))
                val joined = items.mapNotNull { i -> i.hits?.let { io.yamsergey.dta.protocol.network.EntryHits(i.entry.id, i.entry.timeMs, it) } }
                val epochs = items.mapNotNull { i -> i.archived?.let { i.entry.id to it.epoch } }
                next = next.copy(list = next.list.reduce(LiveEvent.Older(page)), hits = next.hits.withOlder(joined, next.hitsNewestFirst, more = true), findHeld = stream.waitingForViewer,
                    epochOf = if (epochs.isEmpty()) next.epochOf else next.epochOf + epochs)
            }
            e.scope?.let { next = next.withScope(it.notBelow(next.scope)) }
            e.saved?.let { next = next.copy(savedApplied = it) }
            if (e.state == io.yamsergey.dta.protocol.network.FindState.WAITING) next = next.copy(find = e.state)
            if (e.state == io.yamsergey.dta.protocol.network.FindState.SEARCHING && next.find == io.yamsergey.dta.protocol.network.FindState.WAITING) next = next.copy(find = e.state)
            if (e.final) {
                val notSearched = JsonObject(listOfNotNull(e.notSearched?.let { "notSearched" to kotlinx.serialization.json.JsonArray(it.map(::JsonPrimitive)) }).toMap())
                next = next.copy(find = e.state, findError = e.error, findHeld = false, searching = false, hits = next.hits.copy(more = false),
                    searchStatements = Statements.forSearch(DtaJson.decodeFromJsonElement(SearchPage.serializer(), JsonObject(notSearched + ("entries" to kotlinx.serialization.json.JsonArray(emptyList()))))))
            }
            next
        }
        // Next past the last hit found waited for this one (FR-015).
        pendingStep?.let { forward -> if (items.any { it.hits != null } || e.final) { pendingStep = null; moveHit(forward) } }
        if (e.final && findStream === stream) findStream = null
    }

    /** Feature 071: a new scope; the app started again since the last one: the banner names the run before. */
    private fun NetworkViewState.withScope(next: io.yamsergey.dta.protocol.network.HistoryScope): NetworkViewState {
        // D-089: whatever moved the tab (a reload of its own, a missed connection event), an answer over a new run whose
        // previous run is the one shown is the restart.
        val shownRun = scope?.run
        val moved = shownRun?.takeIf { !archiveOn && archive == null && next.run != null && next.run!!.runId != it.runId && next.previous?.runId == it.runId }
        val from = restartFrom ?: moved
        if (from == null) restartAsks.set(0)
        if (from == null || next.run == null) return copy(scope = next)
        // D-054: still the run before: the daemon may name the new run a moment after the app connected (asked again a few
        // times), or the link came back to the same run (no restart; a later entry of a newer run still tells one).
        if (next.run!!.runId == from.runId) { askAgainForRestart(); return copy(scope = next) }
        restartAsks.set(0)
        restartFrom = null
        return copy(scope = next, restart = io.yamsergey.dta.present.network.ScopeCaption.restarted(next.run!!, next.previous ?: from))
    }

    /** D-054: how many more times the scope is asked for after the app connected again while it still names the run before. */
    private val restartAsks = java.util.concurrent.atomic.AtomicInteger()
    private val restartAsking = java.util.concurrent.atomic.AtomicBoolean()

    private fun askAgainForRestart() {
        if (restartAsks.get() <= 0 || !restartAsking.compareAndSet(false, true)) return
        restartAsks.decrementAndGet()
        scope.launch {
            kotlinx.coroutines.delay(RESTART_ASK_MS)
            restartAsking.set(false)
            if (restartFrom != null && !mutable.value.archiveOn) reload()
        }
    }

    /**
     * D-085: a search states the scope it started with, also in its final event; entries of the same run counted live since
     * are not taken back (a run only grows; a clear answers with a list page, which replaces the scope).
     */
    private fun io.yamsergey.dta.protocol.network.HistoryScope.notBelow(shown: io.yamsergey.dta.protocol.network.HistoryScope?): io.yamsergey.dta.protocol.network.HistoryScope {
        val mine = run ?: return this
        val had = shown?.run?.takeIf { it.runId == mine.runId && shown.archive == archive } ?: return this
        val more = had.entries - mine.entries
        return if (more <= 0) this else copy(entries = entries + more, run = mine.copy(entries = had.entries))
    }

    /** D-085 (gate 7): `runEntries` of a live event: each run's size, read by the daemon after the entry was stored. */
    private fun runSizes(data: JsonObject): Map<String, Long>? = (data["runEntries"] as? JsonObject)
        ?.mapNotNull { (run, n) -> (n as? JsonPrimitive)?.content?.toLongOrNull()?.let { run to it } }?.toMap()?.ifEmpty { null }

    /**
     * D-085 (gate 7): the shown run holds [size] entries, as the daemon read it after storing them: taken as is, so an
     * entry its list (or search) already counted is not counted again. Never lowered here: an older read may come late.
     */
    private fun sizeRun(run: String, size: Long) = mutable.update { st ->
        val sc = st.scope ?: return@update st
        val r = sc.run?.takeIf { it.runId == run } ?: return@update st
        if (size <= r.entries) st else st.copy(scope = sc.copy(entries = sc.entries + (size - r.entries), run = r.copy(entries = size)))
    }

    /** D-085: a new entry's event (or a count) without the run's size: the daemon broke the protocol; said, nothing guessed. */
    private fun noRunSize() = mutable.update { it.copy(error = "Protocol error: the DTA daemon sent a new entry without its run's size (runEntries).") }

    /** Feature 071: a live entry of another run while the tab shows the current one: the app started again. */
    private fun noteRun(entry: JsonObject, created: Boolean = false, sizes: Map<String, Long>? = null) {
        // The caption's M follows the run as entries arrive (the counters are read again at the next answer).
        // D-085 (gate 7): a new entry's event carries its run's size, read by the daemon after storing it; none is a protocol error.
        val runOf = (entry["runId"] as? JsonPrimitive)?.content
        if (created && runOf != null) sizes?.get(runOf)?.let { sizeRun(runOf, it) } ?: noRunSize()
        runOf?.let(::noteNewerRun)
    }

    /**
     * Feature 071 / D-089: an entry (listed, or only counted) of a run newer than the one shown: the app started again.
     * The view moves to it and the banner names the run before.
     */
    private fun noteNewerRun(run: String) {
        val s = mutable.value
        if (s.archiveOn || s.archive != null) return
        val shown = s.scope?.run?.runId ?: return
        // Each newer run is asked for once (its entries keep coming while the list is asked again).
        if (run == shown || run == notedRun) return
        // Only a newer run moves the view: an earlier run's late update stays out (the daemon drops it too).
        val started = run.removePrefix("r").substringBefore('-').toLongOrNull() ?: return
        val shownStarted = shown.removePrefix("r").substringBefore('-').toLongOrNull() ?: return
        if (started <= shownStarted) return
        notedRun = run
        if (restartFrom == null) restartFrom = s.scope?.run
        requery()
    }

    @Volatile private var notedRun: String? = null

    /** Feature 071: Next past the last hit found while the search runs waits for the next one; the direction. */
    @Volatile private var pendingStep: Boolean? = null

    /**
     * Feature 070: the hits of the rows the list has loaded — the newest as many as it holds, as the list
     * loads them (in search pages of at most [SEARCH_PAGE]) — stepped through in the list's order.
     */
    private suspend fun runSearch(s: NetworkViewState, c: io.yamsergey.dta.client.DaemonClient) {
        mutable.update { it.copy(searching = true) }
        // Rows that arrive while it runs are decided live (067): their hits are kept, not lost to this answer.
        val searched = s.list.rows.mapTo(HashSet()) { it.id }
        val need = s.list.rows.size.coerceAtLeast(1)
        // The search counts from the newest: matches newer than the list's rows push its oldest out of
        // the first `need`, so it goes on until it reaches the oldest row loaded.
        val oldest = s.list.rows.firstOrNull()
        val got = mutableListOf<io.yamsergey.dta.protocol.network.EntryHits>()
        var first: SearchPage? = null
        var before: String? = null
        val notSearched = linkedSetOf<String>()
        val done = runCatching {
            while (true) {
                val params = JsonObject(
                    target(s) + s.applied.params(s.kind) + mapOf("limit" to JsonPrimitive((need - got.size).let { if (it > 0) it.coerceAtMost(SEARCH_PAGE) else CATCH_UP_PAGE }), "order" to JsonPrimitive("newest-first")) +
                        (before?.let { mapOf("before" to JsonPrimitive(it)) } ?: emptyMap()) +
                        (if (s.redact) mapOf("redact" to JsonPrimitive(true)) else emptyMap()) + (s.archive?.let { mapOf("archiveScope" to JsonPrimitive(it)) } ?: mapOf("view" to JsonPrimitive(VIEW))),
                )
                val page = DtaJson.decodeFromJsonElement(SearchPage.serializer(), c.call("network.search", params).result)
                first = first ?: page
                got += page.entries
                page.notSearched?.let(notSearched::addAll)
                before = page.next ?: break
                val reached = oldest == null || page.entries.any { it.id == oldest.id } || (page.entries.lastOrNull()?.timeMs ?: Long.MAX_VALUE) < oldest.timeMs
                if (got.size >= need && reached) break
            }
        }
        done.onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e; if (superseded(e)) return; mutable.update { it.copy(searching = false, error = e.message) }; return }
        val page = first!!.copy(entries = got, notSearched = notSearched.toList().ifEmpty { null })
        mutable.update {
            val byId = HashMap<String, io.yamsergey.dta.protocol.network.EntryHits>()
            it.hits.entries.forEach { e -> if (e.id !in searched) byId[e.id] = e }
            got.forEach { e -> byId[e.id] = e }
            val mine = (if (it.hitsNewestFirst) it.list.rows.asReversed() else it.list.rows).mapNotNull { r -> byId[r.id] }
            it.copy(
                hits = it.hits.refreshed(mine, order = it.list.rows.map { r -> r.id }.let { ids -> if (it.hitsNewestFirst) ids.asReversed() else ids }, more = it.list.older != null),
                searching = false, searchStatements = Statements.forSearch(page),
            )
        }
    }

    private var relistJob: Job? = null

    /** Feature 041: new traffic while only matches are listed — listed again once it settles (an entry matches only once searched). */
    private fun relist() {
        // At most SEARCH_SETTLE_MS after the first new event, not after the last: traffic that never
        // pauses for that long (a busy socket) would otherwise postpone the list for ever.
        // Traffic that comes while the list is asked for is listed by one more round (the trigger is conflated).
        val c = context.client ?: return
        if (relistJob?.isActive != true) {
            val trigger = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
            relistTrigger = trigger
            relistJob = scope.launch {
                for (ignored in trigger) {
                    kotlinx.coroutines.delay(SEARCH_SETTLE_MS)
                    runCatching { c.call("network.list", params(mutable.value)).result.jsonObject }
                        .onSuccess { page -> mutable.update { it.copy(list = it.list.reduce(LiveEvent.Merged(page)), statements = Statements.forNetworkList(page).also { noteHistory(page) }, savedApplied = applied(page)).withPageScope(page) }; search() }
                }
            }
        }
        relistTrigger?.trySend(Unit)
    }

    private var relistTrigger: kotlinx.coroutines.channels.Channel<Unit>? = null

    /** Feature 042: the next list answers the person's own change of query: a selection it leaves out is cleared quietly. */
    @Volatile private var quietNext = false

    private fun requery() {
        quietNext = true
        reload()
    }

    private fun loaded(page: JsonObject) = LiveEvent.Loaded(page, quiet = quietNext.also { quietNext = false })

    private fun filtering(s: NetworkViewState) = s.applied.hasSearch

    fun setRedact(on: Boolean) {
        mutable.update { it.copy(redact = on) }
        reload()
    }

    /** Live traffic (null), or the archive the scope names: what the device no longer has (feature 011). */
    fun setArchive(scope: String?) {
        mutable.update { it.copy(archive = scope, list = LiveList(), statements = emptyList()) }
        reload()
    }

    /** Shows the live entry [id] selected (from another tab, feature 017): out of an archive scope if one was on. */
    fun reveal(id: String) {
        if (mutable.value.archive != null) setArchive(null)
        list(LiveEvent.Selected(id))
    }

    private var olderJob: Job? = null

    /**
     * Older entries above the loaded ones (`before` cursor), D-022: from the Load older row or the list
     * scrolled to its oldest edge — one page at a time, joined at the cursor (no gap, no duplicate).
     */
    fun loadOlder() {
        // Feature 071: a search that waits for the list to be scrolled goes on.
        findStream?.takeIf { it.waitingForViewer }?.let { it.more(); mutable.update { s -> s.copy(findHeld = false) }; return }
        if (olderJob?.isActive == true) return
        val s = mutable.value
        val cursor = s.list.older ?: return
        val c = context.client ?: return
        mutable.update { it.copy(loadingOlder = true) }
        olderJob = scope.launch {
            try {
                runCatching { c.call("network.list", params(s, before = cursor)) }
                    // A page for another query (changed meanwhile) is not joined.
                    .onSuccess { r -> if (mutable.value.list.older == cursor && !older(r.result.jsonObject) && mutable.value.applied.hasSearch) search() }
                    .onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e; if (!superseded(e)) mutable.update { it.copy(error = e.message ?: e.javaClass.simpleName) } }
            } finally {
                mutable.update { it.copy(loadingOlder = false) }
            }
        }
    }

    /** Deletes the app's captured history (`network.clear`). */
    fun clear() {
        val s = mutable.value
        val c = context.client ?: return
        scope.launch {
            runCatching { c.call("network.clear", target(s)) }
                .onSuccess { list(LiveEvent.Cleared); afterClear(c) }
                .onFailure { e -> mutable.update { it.copy(error = e.message) } }
        }
    }

    /** Where an export's [done] runs (the UI thread; tests have none). */
    @Volatile internal var uiContext: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.Main

    /**
     * D-057: after a clear the list is asked again: the caption's counts are the daemon's (not the run's
     * count before the clear), and what the history still holds (the Archive switch on) is listed.
     */
    private fun afterClear(client: DaemonClient) {
        scope.launch {
            runCatching { client.call("network.list", params(mutable.value)).result.jsonObject }
                .onSuccess { page -> mutable.update { it.copy(list = it.list.reduce(LiveEvent.Merged(page)), savedApplied = applied(page)).withPageScope(page) } }
                .onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e; if (!superseded(e)) mutable.update { it.copy(error = e.message ?: e.javaClass.simpleName) } }
        }
    }

    /** Feature 026: `network.export` of what the list shows, to [path]; [done] on the UI thread. */
    fun export(path: String, done: (Result<JsonObject>) -> Unit) {
        val s = mutable.value
        val c = context.client ?: return done(Result.failure(IllegalStateException("No DTA daemon connected.")))
        scope.launch {
            val params = JsonObject(
                target(s) + s.applied.params(s.kind) + (if (s.redact) mapOf("redact" to JsonPrimitive(true)) else emptyMap()) +
                    (s.archive?.let { mapOf("archiveScope" to JsonPrimitive(it)) } ?: emptyMap()) +
                    // D-053 (071 FR-004): the switch's scope, as the list has it.
                    (if (s.archiveOn && s.archive == null) mapOf("archive" to JsonPrimitive(true)) else emptyMap()) + ("output" to JsonPrimitive(path)),
            )
            val result = runCatching { c.call("network.export", params).result.jsonObject }
            kotlinx.coroutines.withContext(uiContext) { done(result) }
        }
    }

    /** Feature 041: the list read again as it is (a format rule changed how its messages read). */
    fun refresh() = reload()

    private fun reload() {
        val c = context.state.value
        reload(Target(c.device, c.app, c.connection != Connection.NO_DTA, c.generation, context.client))
    }

    private fun reload(target: Target) {
        source?.cancel()
        relistJob?.cancel()
        findStream?.let { findStream = null; it.stop() }
        val previous = mutable.value
        val sameApp = previous.device == target.device && previous.app == target.app
        mutable.update {
            it.copy(
                device = target.device, app = target.app, watching = false, error = null,
                list = if (sameApp) it.list else LiveList(), statements = if (sameApp) it.statements else emptyList(),
                saved = if (sameApp) it.saved else SavedFilters(), savedApplied = if (sameApp) it.savedApplied else null,
                script = if (sameApp) it.script else null,
            )
        }
        val client = target.client ?: run {
            // D-083: no daemon, nothing is watched: what the list showed of the run (its rows, "N in this run", the
            // restart banner) is not the run any more. The banner above says why; a daemon back lists it again.
            mutable.update {
                it.copy(online = false, list = LiveList(), statements = emptyList(), scope = null, restart = null, epochOf = emptyMap(),
                    savedApplied = null, find = null, findError = null, findHeld = false, searching = false, loading = false)
            }
            return
        }
        mutable.update { it.copy(online = true) }
        // A new daemon connection (restarted, replaced) is a new client: the old one answers nothing any more.
        if (!sameApp || savedJob == null || savedClient !== client) followSaved(client)
        if (!sameApp || waitJob == null || waitClient !== client) followWait(client)
        if (!sameApp || scriptJob == null || scriptClient !== client) followScript(client)
        // The archive answers without the app running; it is a snapshot, not watched.
        if (mutable.value.archive != null && target.device != null && target.app != null) {
            source = scope.launch {
                mutable.update { it.copy(loading = true, find = null) }
                if (streamed(mutable.value)) { startFind(client); return@launch }
                runCatching { client.call("network.list", params(mutable.value)).result.jsonObject }
                    .onSuccess { page -> val e = loaded(page); mutable.update { it.copy(list = it.list.reduce(e), statements = Statements.forNetworkList(page).also { noteHistory(page) }, savedApplied = applied(page), loading = false).withPageScope(page) }; search() }
                    .onFailure { e -> mutable.update { it.copy(error = if (superseded(e)) it.error else e.message ?: e.javaClass.simpleName, loading = false, searching = false) } }
            }
            return
        }
        if (target.device == null || target.app == null || !target.hasDta) return
        source = scope.launch {
            mutable.update { it.copy(loading = true) }
            try {
                val s = mutable.value
                // D-085: counts: the daemon also says how many new entries of a run the tab does not list (the caption's M).
                val live = mapOf("view" to JsonPrimitive(VIEW), "counts" to JsonPrimitive(true)) + (if (s.redact) mapOf("redact" to JsonPrimitive(true)) else emptyMap()) +
                    (if (s.archiveOn) mapOf("archive" to JsonPrimitive(true)) else emptyMap())
                client.networkEvents(JsonObject(target(s) + s.applied.params(s.kind) + live)).collect { data ->
                    if (data["selection"] != null) {
                        liveDecisions = ((data["selection"] as? JsonObject)?.get("result") as? JsonObject)?.get("liveQuery")?.let { (it as? JsonPrimitive)?.content == "true" } == true
                        // Feature 071: a search streams its rows (subscribed first: live ones merge by id).
                        if (streamed(mutable.value)) {
                            mutable.update { it.copy(watching = true, find = null) }
                            startFind(client)
                            return@collect
                        }
                        // Subscribed first, then listed: nothing between the two is missed; overlap merges by id.
                        val page = client.call("network.list", params(mutable.value)).result.jsonObject
                        val e = loaded(page)
                        mutable.update { it.copy(list = it.list.reduce(e), statements = Statements.forNetworkList(page).also { noteHistory(page) }, savedApplied = applied(page), loading = false, watching = true, find = null).withPageScope(page) }
                        search()
                    } else if (data["counted"] is JsonObject) {
                        // D-085 (gate 7): the run's size as the daemon has it; the plugin and its daemon are one build, so it is always there.
                        val sizes = runSizes(data)
                        if (sizes != null) sizes.forEach { (run, n) -> sizeRun(run, n) } else noRunSize()
                        // D-089: a newer run's entries that match nothing come only as a count: still the restart.
                        sizes?.keys?.forEach(::noteNewerRun)
                    } else if (Statements.forCleared(data) != null) {
                        // Whoever cleared the history, the rows it held are gone: never show entries that no longer exist.
                        val cleared = Statements.forCleared(data)!!
                        mutable.update { it.copy(list = it.list.reduce(LiveEvent.Cleared), statements = it.statements.filter { s -> s.word != "cleared" } + cleared) }
                        afterClear(client)
                    } else {
                        val event = data["event"]?.jsonObject ?: return@collect
                        val entry = event["entry"]?.jsonObject ?: return@collect
                        val change = (event["change"] as? JsonPrimitive)?.content
                        // A body filter the event's preview could not decide: the list (host copy) decides.
                        val unchecked = (data["bodyFilterUnchecked"] as? JsonPrimitive)?.content == "true"
                        if (liveDecisions && data["decision"] != null) {
                            decided(data, entry, change)
                            return@collect
                        }
                        if (filtering(mutable.value) || unchecked) {
                            // Only matches are listed: a listed row may change in place; anything new waits for the next list.
                            if (change == "updated" && mutable.value.list.rows.any { it.id == entry["id"]?.let { i -> (i as? JsonPrimitive)?.content } }) list(LiveEvent.Updated(entry))
                            // D-023: the selected socket's new message changes its detail even before it is listed.
                            else mutable.update { it.followSelected(LiveEvent.Arrived(entry)) }
                            relist()
                            return@collect
                        }
                        list(if (change == "updated") LiveEvent.Updated(entry) else LiveEvent.Arrived(entry))
                        noteRun(entry, created = change != "updated", sizes = runSizes(data))
                        // New traffic may hold the words: the hits are refreshed once it settles.
                        if (mutable.value.applied.hasSearch) search(debounceMs = SEARCH_SETTLE_MS)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A list that never came starts no search: nothing is being searched.
                mutable.update { it.copy(error = if (superseded(e)) it.error else e.message ?: e.javaClass.simpleName, searching = if (searchJob?.isActive == true) it.searching else false) }
            } finally {
                // Cancelled by a new query, the next list's search goes on: searching is left as it is.
                mutable.update { it.copy(loading = false, watching = false) }
            }
        }
    }

    /** Feature 066: one alert per app and growth episode, when a list says the device history is over its warning size. */
    data class HistoryAlert(val device: String, val app: String, val title: String, val text: String)

    private val episodes = io.yamsergey.dta.present.archive.HistoryEpisodes()
    private val alerts = kotlinx.coroutines.flow.MutableSharedFlow<HistoryAlert>(extraBufferCapacity = 8)
    val historyAlerts: kotlinx.coroutines.flow.SharedFlow<HistoryAlert> = alerts

    private fun noteHistory(page: JsonObject) {
        val s = mutable.value
        val device = s.device ?: return
        val app = s.app ?: return
        if (s.archive != null) return
        val coverage = page["coverage"] as? JsonObject ?: return
        fun long(k: String) = (coverage[k] as? JsonPrimitive)?.content?.toLongOrNull()
        val bytes = long("deviceBytes")
        if (!episodes.crossed(device, app, bytes, long("warnBytes"))) return
        val large = Statements.forNetworkList(page).firstOrNull { it.code == "history-large" } ?: return
        alerts.tryEmit(HistoryAlert(device, app, "${app.substringAfterLast('.')}: device history is ${io.yamsergey.dta.present.Units.bytes(bytes!!)}", large.sentence))
    }

    private fun target(s: NetworkViewState) = JsonObject(listOfNotNull(s.device?.let { "device" to JsonPrimitive(it) }, s.app?.let { "app" to JsonPrimitive(it) }).toMap())

    private fun params(s: NetworkViewState, before: String? = null) = JsonObject(
        target(s) + s.applied.params(s.kind) + mapOf("limit" to JsonPrimitive(PAGE)) +
            (before?.let { mapOf("before" to JsonPrimitive(it)) } ?: emptyMap()) + (if (s.redact) mapOf("redact" to JsonPrimitive(true)) else emptyMap()) +
            (s.archive?.let { mapOf("archiveScope" to JsonPrimitive(it)) } ?: mapOf("view" to JsonPrimitive(VIEW))) +
            (if (s.archiveOn && s.archive == null) mapOf("archive" to JsonPrimitive(true)) else emptyMap()),
    )

    /** Feature 071: a list page's scope (and the restart banner when the app started again). */
    private fun NetworkViewState.withPageScope(page: JsonObject): NetworkViewState {
        val epochs = (page["archived"] as? JsonObject)?.mapNotNull { (id, mark) -> ((mark as? JsonObject)?.get("epoch") as? JsonPrimitive)?.content?.let { id to it } }.orEmpty()
        val marked = if (epochs.isEmpty()) this else copy(epochOf = epochOf + epochs)
        return page["scope"]?.let { runCatching { DtaJson.decodeFromJsonElement(io.yamsergey.dta.protocol.network.HistoryScope.serializer(), it) }.getOrNull() }?.let { marked.withScope(it) } ?: marked
    }

    /** Feature 067: the daemon decides each live entry with the whole line (an older daemon does not: the list is asked again). */
    @Volatile private var liveDecisions = false

    /** A live decision: the row joins, changes or leaves, and the hits follow it — nothing is listed again. */
    private fun decided(data: JsonObject, entry: JsonObject, change: String?) {
        val decision = data["decision"] as? JsonObject
        val id = (entry["id"] as? JsonPrimitive)?.content ?: return
        val time = (entry["timeMs"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        if (decision?.get("left") != null) {
            mutable.update { it.copy(list = it.list.reduce(LiveEvent.Left(id)), hits = it.hits.live(id, time, null, it.hitsNewestFirst)) }
            return
        }
        val hits = (decision?.get("hits") as? kotlinx.serialization.json.JsonArray)?.let {
            runCatching { DtaJson.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(io.yamsergey.dta.protocol.network.SearchHit.serializer()), it) }.getOrNull()
        }
        val event = if (change == "updated") LiveEvent.Updated(entry) else LiveEvent.Arrived(entry)
        noteRun(entry, created = change != "updated", sizes = runSizes(data))
        mutable.update { s ->
            val withRow = s.copy(list = s.list.reduce(event)).followSelected(event)
            if (s.applied.finds && hits != null) withRow.copy(hits = s.hits.live(id, time, hits, s.hitsNewestFirst)) else withRow
        }
    }

    /** 070: an answer the daemon abandoned for a newer query of this tab: nobody needs it, nothing is said. */
    private fun superseded(e: Throwable) = e is io.yamsergey.dta.client.DaemonCallException && e.code == io.yamsergey.dta.protocol.ops.ClientErrorCodes.SUPERSEDED

    companion object {
        /** Feature 067: this tab's live view at the daemon (its match sets make the next query cheap). */
        const val VIEW = "network-tab"
        const val PAGE = 500
        /** At most this many entries with hits per `network.search` call (the protocol's page limit). */
        const val SEARCH_PAGE = 1000
        const val SEARCH_SETTLE_MS = 1000L

        /** Search page size past the rows' count: the matches that arrived while the list loaded. */
        const val CATCH_UP_PAGE = 200
        const val REVEAL_PAGES = 10
        const val SAVED_POLL_MS = 5000L
        /** D-054: after the app connected again, the scope is asked again up to this many times, this far apart, for the new run. */
        const val RESTART_ASKS = 5
        const val RESTART_ASK_MS = 1000L
    }
}

