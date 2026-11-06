package io.yamsergey.dta.studio.timeline

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.present.context.Connection
import io.yamsergey.dta.present.timeline.ChainView
import io.yamsergey.dta.present.timeline.EpisodesView
import io.yamsergey.dta.present.timeline.TimelineChange
import io.yamsergey.dta.present.timeline.TimelineFilter
import io.yamsergey.dta.present.timeline.TimelineView
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.timeline.CauseChain
import io.yamsergey.dta.protocol.timeline.CurrentState
import io.yamsergey.dta.protocol.timeline.EpisodesPage
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.timeline.TimelinePage
import io.yamsergey.dta.studio.ContextController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Everything the Timeline tab shows (feature 017). */
data class TimelineTabState(
    val device: String? = null,
    val app: String? = null,
    val view: TimelineView = TimelineView(),
    /** The selected event's chain, from `timeline.causes`. */
    val chain: ChainView = ChainView.EMPTY,
    /** Inferred links asked for (the toolbar's toggle; off by default). */
    val inferred: Boolean = false,
    /** The chain shown was asked for with inferred links. */
    val chainInferred: Boolean = false,
    val chainError: String? = null,
    val loading: Boolean = false,
    val watching: Boolean = false,
    val error: String? = null,
    /** Feature 045: the Episodes view is the one shown (else the list). */
    val showEpisodes: Boolean = true,
    val episodes: EpisodesView = EpisodesView(),
    val episodesError: String? = null,
)

/**
 * The Timeline tab's source (feature 017, research R1, R4): `timeline.watch` first, then every
 * kept event paged in with `timeline.events`; `state.current` for the sources and facets; for the
 * selected event, `timeline.causes` in both directions. A device or app change starts over.
 * The tab only reduces and draws; causes are never decided here.
 */
class TimelineController(private val context: ContextController, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(TimelineTabState())
    val state: StateFlow<TimelineTabState> = mutable.asStateFlow()
    private var source: Job? = null
    private var chainJob: Job? = null
    @Volatile private var active = false

    init {
        scope.launch {
            context.state
                .map { Target(it.device, it.app, it.connection != Connection.NO_DTA, it.generation, context.client) }
                .distinctUntilChanged()
                .collect { reload(it) }
        }
    }

    private data class Target(val device: String?, val app: String?, val hasDta: Boolean, val generation: Int, val client: DaemonClient?)

    /** The tab is shown: the current state is refreshed while it is. */
    fun setActive(on: Boolean) {
        active = on
    }

    fun setShowEpisodes(on: Boolean) {
        mutable.update { it.copy(showEpisodes = on) }
        if (on) refreshEpisodes()
    }

    fun toggleEpisode(id: String) = mutable.update { it.copy(episodes = it.episodes.toggle(id)) }

    private fun refreshEpisodes() {
        val client = context.client ?: return
        scope.launch { runCatching { readEpisodes(client) } }
    }

    private suspend fun readEpisodes(client: DaemonClient) {
        val params = target(mutable.value) + mapOf("limit" to JsonPrimitive(EPISODES), "steps" to JsonPrimitive(true))
        try {
            val page = DtaJson.decodeFromJsonElement(EpisodesPage.serializer(), client.call("timeline.episodes", JsonObject(params)).result)
            mutable.update { it.copy(episodes = it.episodes.copy(page = page), episodesError = null) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            mutable.update { it.copy(episodesError = e.message ?: e.javaClass.simpleName) }
        }
    }

    fun change(change: TimelineChange) = mutable.update { it.copy(view = it.view.reduce(change)) }

    fun setFilter(filter: TimelineFilter) = change(TimelineChange.Filter(filter))

    fun select(id: String?) {
        change(TimelineChange.Select(id))
        loadChain()
    }

    fun setInferred(on: Boolean) {
        mutable.update { it.copy(inferred = on) }
        loadChain()
    }

    private fun loadChain() {
        chainJob?.cancel()
        val s = mutable.value
        val id = s.view.selectedId
        val client = context.client
        if (id == null || client == null) {
            mutable.update { it.copy(chain = ChainView.EMPTY, chainError = null) }
            return
        }
        chainJob = scope.launch {
            try {
                fun params(direction: String) = JsonObject(
                    target(s) + mapOf("event" to JsonPrimitive(id), "direction" to JsonPrimitive(direction), "inferred" to JsonPrimitive(s.inferred)),
                )
                val causes = DtaJson.decodeFromJsonElement(CauseChain.serializer(), client.call("timeline.causes", params("causes")).result)
                val effects = DtaJson.decodeFromJsonElement(CauseChain.serializer(), client.call("timeline.causes", params("effects")).result)
                mutable.update {
                    if (it.view.selectedId != id) it else it.copy(chain = ChainView.of(causes, effects), chainInferred = s.inferred, chainError = null)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(chain = ChainView.EMPTY, chainError = "The chain of $id could not be read: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private fun reload(target: Target) {
        source?.cancel()
        chainJob?.cancel()
        val moved = mutable.value.device != target.device || mutable.value.app != target.app
        mutable.update {
            val base = if (moved) TimelineTabState(inferred = it.inferred, showEpisodes = it.showEpisodes) else it
            base.copy(device = target.device, app = target.app, watching = false, error = null)
        }
        val client = target.client ?: return
        if (target.device == null || target.app == null || !target.hasDta) return
        source = scope.launch {
            mutable.update { it.copy(loading = true) }
            val refresher = launch {
                while (isActive) {
                    delay(STATE_EVERY_MS)
                    if (active) runCatching { readState(client) }
                }
            }
            val episodes = launch {
                while (isActive) {
                    if (active && mutable.value.showEpisodes) readEpisodes(client)
                    delay(EPISODES_EVERY_MS)
                }
            }
            try {
                client.timelineEvents(JsonObject(target(mutable.value) + ("history" to JsonPrimitive("none")))).collect { data ->
                    if (data["selection"] != null) {
                        // Subscribed first, then paged: nothing between the two is missed; overlap merges by id.
                        pageAll(client)
                        readState(client)
                        mutable.update { it.copy(loading = false, watching = true) }
                    } else {
                        val event = data["event"]?.jsonObject ?: return@collect
                        val e = DtaJson.decodeFromJsonElement(TimelineEvent.serializer(), event)
                        change(TimelineChange.Arrived(e))
                        if (e.kind == Kinds.PROCESS_ENDED) runCatching { readState(client) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                refresher.cancel()
                episodes.cancel()
                mutable.update { it.copy(loading = false, watching = false) }
            }
        }
    }

    private suspend fun pageAll(client: DaemonClient) {
        var since: String? = null
        while (true) {
            val params = target(mutable.value) + listOfNotNull(since?.let { "since" to JsonPrimitive(it) }, "limit" to JsonPrimitive(PAGE)).toMap()
            val page = DtaJson.decodeFromJsonElement(TimelinePage.serializer(), client.call("timeline.events", JsonObject(params)).result)
            change(TimelineChange.Loaded(page.events))
            if (page.sources.isNotEmpty()) change(TimelineChange.Sources(page.sources))
            if (!page.more || page.next == since) return
            since = page.next
        }
    }

    private suspend fun readState(client: DaemonClient) {
        val state = DtaJson.decodeFromJsonElement(CurrentState.serializer(), client.call("state.current", JsonObject(target(mutable.value))).result)
        change(TimelineChange.State(state.facets))
        if (state.sources.isNotEmpty()) change(TimelineChange.Sources(state.sources))
    }

    private fun target(s: TimelineTabState) =
        listOfNotNull(s.device?.let { "device" to JsonPrimitive(it) }, s.app?.let { "app" to JsonPrimitive(it) }).toMap()

    companion object {
        const val PAGE = 500
        const val STATE_EVERY_MS = 5_000L
        const val EPISODES = 500
        const val EPISODES_EVERY_MS = 1_500L
    }
}
