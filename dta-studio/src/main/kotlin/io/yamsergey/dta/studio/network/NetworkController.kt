package io.yamsergey.dta.studio.network

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.present.Statement
import io.yamsergey.dta.present.Statements
import io.yamsergey.dta.present.context.Connection
import io.yamsergey.dta.present.context.ContextEvent
import io.yamsergey.dta.present.network.LiveEvent
import io.yamsergey.dta.present.network.LiveList
import io.yamsergey.dta.present.network.NetworkFilters
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
    val filters: NetworkFilters = NetworkFilters(),
    /** Statements about the list as a whole: eviction, runs not searched, degradation. */
    val statements: List<Statement> = emptyList(),
    val redact: Boolean = false,
    val loading: Boolean = false,
    val watching: Boolean = false,
    val error: String? = null,
    val run: String? = null,
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

    fun setFilter(name: String, value: JsonElement) {
        mutable.update { it.copy(filters = it.filters.with(name, value)) }
        reload()
    }

    /** The All / HTTP / WebSocket switch: the `kinds` filter. */
    fun setKind(choice: io.yamsergey.dta.present.network.KindChoice) {
        mutable.update { it.copy(filters = it.filters.withKind(choice)) }
        reload()
    }

    fun removeFilter(name: String) {
        mutable.update { it.copy(filters = it.filters.without(name)) }
        reload()
    }

    fun setRedact(on: Boolean) {
        mutable.update { it.copy(redact = on) }
        reload()
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

    private fun reload() {
        val c = context.state.value
        reload(Target(c.device, c.app, c.connection != Connection.NO_DTA, c.generation, context.client))
    }

    private fun reload(target: Target) {
        source?.cancel()
        val previous = mutable.value
        val sameApp = previous.device == target.device && previous.app == target.app
        mutable.update {
            it.copy(
                device = target.device, app = target.app, watching = false, error = null,
                list = if (sameApp) it.list else LiveList(), statements = if (sameApp) it.statements else emptyList(),
            )
        }
        val client = target.client ?: return
        if (target.device == null || target.app == null || !target.hasDta) return
        source = scope.launch {
            mutable.update { it.copy(loading = true) }
            try {
                val s = mutable.value
                client.networkEvents(JsonObject(target(s) + s.filters.params())).collect { data ->
                    if (data["selection"] != null) {
                        // Subscribed first, then listed: nothing between the two is missed; overlap merges by id.
                        val page = client.call("network.list", params(mutable.value)).result.jsonObject
                        mutable.update { it.copy(list = it.list.reduce(LiveEvent.Loaded(page)), statements = Statements.forNetworkList(page), loading = false, watching = true) }
                        seenRun()
                    } else {
                        val event = data["event"]?.jsonObject ?: return@collect
                        val entry = event["entry"]?.jsonObject ?: return@collect
                        val change = (event["change"] as? JsonPrimitive)?.content
                        list(if (change == "updated") LiveEvent.Updated(entry) else LiveEvent.Arrived(entry))
                        if (change != "updated") seenRun()
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

    /** The newest row's run is the context's run label. */
    private fun seenRun() {
        val run = (mutable.value.list.rows.lastOrNull()?.entry?.get("runId") as? JsonPrimitive)?.content ?: return
        if (run != mutable.value.run) {
            mutable.update { it.copy(run = run) }
            if (context.state.value.run != run) context.apply(ContextEvent.RunSeen(run))
        }
    }

    private fun target(s: NetworkViewState) = JsonObject(listOfNotNull(s.device?.let { "device" to JsonPrimitive(it) }, s.app?.let { "app" to JsonPrimitive(it) }).toMap())

    private fun params(s: NetworkViewState, before: String? = null) = JsonObject(
        target(s) + s.filters.params(limit = PAGE, before = before) + (if (s.redact) mapOf("redact" to JsonPrimitive(true)) else emptyMap()),
    )

    companion object {
        const val PAGE = 500
    }
}

