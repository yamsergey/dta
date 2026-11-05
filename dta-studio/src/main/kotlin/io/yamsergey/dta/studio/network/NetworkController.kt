package io.yamsergey.dta.studio.network

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.present.Statement
import io.yamsergey.dta.present.Statements
import io.yamsergey.dta.present.context.Connection
import io.yamsergey.dta.present.network.LiveEvent
import io.yamsergey.dta.present.network.LiveList
import io.yamsergey.dta.present.network.HitNavigator
import io.yamsergey.dta.present.network.KindChoice
import io.yamsergey.dta.present.network.LineChip
import io.yamsergey.dta.present.network.QueryLineModel
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
    /** Statements about the list as a whole: eviction, runs not searched, degradation. */
    val statements: List<Statement> = emptyList(),
    /** Feature 040: the search words' hits, in the list's order, and the current one. */
    val hits: HitNavigator = HitNavigator(),
    /** Hits run top to bottom as the list shows newest first (the list's order switch). */
    val hitsNewestFirst: Boolean = true,
    val searching: Boolean = false,
    /** What the search could not cover (not-searched bodies, an app that cannot search). */
    val searchStatements: List<Statement> = emptyList(),
    /** Feature 041: search words leave only the matching rows; off, every row stays with the hits marked. */
    val onlyMatches: Boolean = true,
    val redact: Boolean = false,
    /** An archive scope (`all`, an epoch id…): the archived history instead of live traffic (feature 011). */
    val archive: String? = null,
    val loading: Boolean = false,
    val watching: Boolean = false,
    val error: String? = null,
)

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
    }

    private data class Target(val device: String?, val app: String?, val hasDta: Boolean, val generation: Int, val client: DaemonClient?)

    fun list(event: LiveEvent) = mutable.update { it.copy(list = it.list.reduce(event)) }

    /**
     * The query line as typed. A line with problems changes nothing (the field shows them);
     * a changed filter part is a new query, a changed search part only a new search.
     */
    fun setQuery(text: String) {
        val before = mutable.value.line
        val line = before.withText(text)
        if (line.parsed.problems.isNotEmpty()) { mutable.update { it.copy(line = line) }; return }
        mutable.update { it.copy(line = line) }
        val sameFilters = before.parsed.problems.isEmpty() && before.parsed.filter == line.parsed.filter && before.parsed.exclude == line.parsed.exclude
        // Feature 041: with only the matches listed, other words are other rows.
        val sameRows = sameFilters && (!mutable.value.onlyMatches || before.parsed.terms == line.parsed.terms)
        if (sameRows) search() else requery()
    }

    /** The All / HTTP / WebSocket switch: the line's kind word. */
    fun setKind(choice: KindChoice) = setQuery(mutable.value.line.withKind(choice).text)

    fun removeChip(chip: LineChip) = setQuery(mutable.value.line.without(chip).text)

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
        val moved = mutable.value.hits.let { if (forward) it.next() else it.previous() }
        mutable.update { it.copy(hits = moved) }
        moved.current?.id?.let(::selectLoaded)
    }

    /** Selects [id], loading older pages (at most [REVEAL_PAGES]) while it is not loaded yet. */
    private fun selectLoaded(id: String) {
        val c = context.client
        if (mutable.value.list.rows.any { it.id == id } || c == null) { list(LiveEvent.Selected(id)); return }
        scope.launch {
            var pages = 0
            while (mutable.value.list.rows.none { it.id == id } && pages++ < REVEAL_PAGES) {
                val cursor = mutable.value.list.older ?: break
                val r = runCatching { c.call("network.list", params(mutable.value, before = cursor)) }.getOrNull() ?: break
                list(LiveEvent.Older(r.result.jsonObject))
            }
            list(LiveEvent.Selected(id))
        }
    }

    private var searchJob: Job? = null

    /** Runs the line's search words (`network.search`), or clears the hits when there are none. */
    fun search(debounceMs: Long = 0) {
        searchJob?.cancel()
        val s = mutable.value
        val c = context.client
        if (!s.line.hasSearch || c == null || s.device == null || s.app == null) {
            mutable.update { it.copy(hits = HitNavigator(), searching = false, searchStatements = emptyList()) }
            return
        }
        searchJob = scope.launch {
            if (debounceMs > 0) kotlinx.coroutines.delay(debounceMs)
            mutable.update { it.copy(searching = true) }
            val params = JsonObject(
                target(s) + s.line.params() + mapOf("limit" to JsonPrimitive(SEARCH_LIMIT), "order" to JsonPrimitive(if (s.hitsNewestFirst) "newest-first" else "oldest-first")) +
                    (if (s.redact) mapOf("redact" to JsonPrimitive(true)) else emptyMap()) + (s.archive?.let { mapOf("archive" to JsonPrimitive(it)) } ?: emptyMap()),
            )
            runCatching { DtaJson.decodeFromJsonElement(SearchPage.serializer(), c.call("network.search", params).result) }
                .onSuccess { page ->
                    mutable.update {
                        it.copy(
                            hits = it.hits.refreshed(page.entries, order = it.list.rows.map { r -> r.id }.let { ids -> if (it.hitsNewestFirst) ids.asReversed() else ids }),
                            searching = false, searchStatements = Statements.forSearch(page),
                        )
                    }
                }
                .onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) mutable.update { it.copy(searching = false, error = e.message) } }
        }
    }

    /** Feature 041: only the rows holding the search words, or every row with the hits marked. */
    fun setOnlyMatches(on: Boolean) {
        if (mutable.value.onlyMatches == on) return
        mutable.update { it.copy(onlyMatches = on) }
        if (mutable.value.line.hasSearch) requery()
    }

    private var relistJob: Job? = null

    /** Feature 041: new traffic while only matches are listed — listed again once it settles (an entry matches only once searched). */
    private fun relist() {
        relistJob?.cancel()
        val c = context.client ?: return
        relistJob = scope.launch {
            kotlinx.coroutines.delay(SEARCH_SETTLE_MS)
            runCatching { c.call("network.list", params(mutable.value)).result.jsonObject }
                .onSuccess { page -> mutable.update { it.copy(list = it.list.reduce(LiveEvent.Merged(page)), statements = Statements.forNetworkList(page)) }; search() }
        }
    }

    /** Feature 042: the next list answers the person's own change of query: a selection it leaves out is cleared quietly. */
    @Volatile private var quietNext = false

    private fun requery() {
        quietNext = true
        reload()
    }

    private fun loaded(page: JsonObject) = LiveEvent.Loaded(page, quiet = quietNext.also { quietNext = false })

    private fun filtering(s: NetworkViewState) = s.onlyMatches && s.line.hasSearch

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

    /** Older entries above the loaded ones (`before` cursor). */
    fun loadOlder() {
        val s = mutable.value
        val cursor = s.list.older ?: return
        val c = context.client ?: return
        scope.launch {
            runCatching { c.call("network.list", params(s, before = cursor)) }
                .onSuccess { r -> list(LiveEvent.Older(r.result.jsonObject)) }
        }
    }

    /** Deletes the app's captured history (`network.clear`). */
    fun clear() {
        val s = mutable.value
        val c = context.client ?: return
        scope.launch {
            runCatching { c.call("network.clear", target(s)) }
                .onSuccess { list(LiveEvent.Cleared) }
                .onFailure { e -> mutable.update { it.copy(error = e.message) } }
        }
    }

    /** Feature 026: `network.export` of what the list shows, to [path]; [done] on the UI thread. */
    fun export(path: String, done: (Result<JsonObject>) -> Unit) {
        val s = mutable.value
        val c = context.client ?: return done(Result.failure(IllegalStateException("No DTA daemon connected.")))
        scope.launch {
            val params = JsonObject(
                target(s) + s.line.params() + (if (s.redact) mapOf("redact" to JsonPrimitive(true)) else emptyMap()) +
                    (s.archive?.let { mapOf("archive" to JsonPrimitive(it)) } ?: emptyMap()) + ("output" to JsonPrimitive(path)),
            )
            val result = runCatching { c.call("network.export", params).result.jsonObject }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { done(result) }
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
        val previous = mutable.value
        val sameApp = previous.device == target.device && previous.app == target.app
        mutable.update {
            it.copy(
                device = target.device, app = target.app, watching = false, error = null,
                list = if (sameApp) it.list else LiveList(), statements = if (sameApp) it.statements else emptyList(),
            )
        }
        val client = target.client ?: return
        // The archive answers without the app running; it is a snapshot, not watched.
        if (mutable.value.archive != null && target.device != null && target.app != null) {
            source = scope.launch {
                mutable.update { it.copy(loading = true) }
                runCatching { client.call("network.list", params(mutable.value)).result.jsonObject }
                    .onSuccess { page -> val e = loaded(page); mutable.update { it.copy(list = it.list.reduce(e), statements = Statements.forNetworkList(page), loading = false) }; search() }
                    .onFailure { e -> mutable.update { it.copy(error = e.message ?: e.javaClass.simpleName, loading = false) } }
            }
            return
        }
        if (target.device == null || target.app == null || !target.hasDta) return
        source = scope.launch {
            mutable.update { it.copy(loading = true) }
            try {
                val s = mutable.value
                client.networkEvents(JsonObject(target(s) + s.line.params())).collect { data ->
                    if (data["selection"] != null) {
                        // Subscribed first, then listed: nothing between the two is missed; overlap merges by id.
                        val page = client.call("network.list", params(mutable.value)).result.jsonObject
                        val e = loaded(page)
                        mutable.update { it.copy(list = it.list.reduce(e), statements = Statements.forNetworkList(page), loading = false, watching = true) }
                        search()
                    } else if (Statements.forCleared(data) != null) {
                        // Whoever cleared the history, the rows it held are gone: never show entries that no longer exist.
                        val cleared = Statements.forCleared(data)!!
                        mutable.update { it.copy(list = it.list.reduce(LiveEvent.Cleared), statements = it.statements.filter { s -> s.word != "cleared" } + cleared) }
                    } else {
                        val event = data["event"]?.jsonObject ?: return@collect
                        val entry = event["entry"]?.jsonObject ?: return@collect
                        val change = (event["change"] as? JsonPrimitive)?.content
                        if (filtering(mutable.value)) {
                            // Only matches are listed: a listed row may change in place; anything new waits for the next list.
                            if (change == "updated" && mutable.value.list.rows.any { it.id == entry["id"]?.let { i -> (i as? JsonPrimitive)?.content } }) list(LiveEvent.Updated(entry))
                            relist()
                            return@collect
                        }
                        list(if (change == "updated") LiveEvent.Updated(entry) else LiveEvent.Arrived(entry))
                        // New traffic may hold the words: the hits are refreshed once it settles.
                        if (mutable.value.line.hasSearch) search(debounceMs = SEARCH_SETTLE_MS)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                mutable.update { it.copy(loading = false, watching = false) }
            }
        }
    }

    private fun target(s: NetworkViewState) = JsonObject(listOfNotNull(s.device?.let { "device" to JsonPrimitive(it) }, s.app?.let { "app" to JsonPrimitive(it) }).toMap())

    private fun params(s: NetworkViewState, before: String? = null) = JsonObject(
        target(s) + s.line.params() + mapOf("limit" to JsonPrimitive(PAGE)) +
            (if (!s.onlyMatches && s.line.hasSearch) mapOf("allRows" to JsonPrimitive(true)) else emptyMap()) + (before?.let { mapOf("before" to JsonPrimitive(it)) } ?: emptyMap()) + (if (s.redact) mapOf("redact" to JsonPrimitive(true)) else emptyMap()) +
            (s.archive?.let { mapOf("archive" to JsonPrimitive(it)) } ?: emptyMap()),
    )

    companion object {
        const val PAGE = 500
        const val SEARCH_LIMIT = 1000
        const val SEARCH_SETTLE_MS = 1000L
        const val REVEAL_PAGES = 10
    }
}

