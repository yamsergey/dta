package io.yamsergey.dta.studio.layout

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.present.layout.LayoutTree
import io.yamsergey.dta.present.layout.SearchResult
import io.yamsergey.dta.present.layout.TreeSearch
import io.yamsergey.dta.protocol.layout.DetailLevel
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Everything the Layout view shows. */
data class LayoutViewState(
    val device: String? = null,
    val app: String? = null,
    val tree: LayoutTree? = null,
    val detail: DetailLevel = DetailLevel.LAYOUT,
    val expanded: Set<List<Int>> = emptySet(),
    val selected: List<Int>? = null,
    val search: SearchResult? = null,
    val hideNonMatching: Boolean = false,
    /** Why the selection is gone after a refresh. */
    val note: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * The Layout view's source (FR-011/012): `layout.tree` for the context's device and app, captured
 * when the view is shown, on refresh, and on a detail-level change — a layout is a snapshot, so
 * nothing is captured while the view is hidden. Works for apps without DTA too (the fallback).
 */
class LayoutController(private val context: ContextController, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(LayoutViewState())
    val state: StateFlow<LayoutViewState> = mutable.asStateFlow()
    private var capture: Job? = null
    @Volatile private var active = false
    @Volatile private var stale = true

    init {
        scope.launch {
            context.state
                .map { Target(it.device, it.app, it.generation, context.client) }
                .distinctUntilChanged()
                .collect { t ->
                    val moved = mutable.value.device != t.device || mutable.value.app != t.app
                    mutable.update {
                        if (moved) LayoutViewState(device = t.device, app = t.app, detail = it.detail, hideNonMatching = it.hideNonMatching) else it
                    }
                    stale = true
                    if (active) refresh()
                }
        }
    }

    private data class Target(val device: String?, val app: String?, val generation: Int, val client: DaemonClient?)

    /** The view became visible ([on]) or hidden; showing it captures a stale screen. */
    fun setActive(on: Boolean) {
        active = on
        if (on && stale) refresh()
    }

    fun refresh() {
        val client = context.client ?: return
        val s = mutable.value
        val device = s.device ?: return
        stale = false
        capture?.cancel()
        mutable.update { it.copy(loading = true, error = null) }
        capture = scope.launch {
            val params = JsonObject(
                listOfNotNull("device" to JsonPrimitive(device), s.app?.let { "app" to JsonPrimitive(it) }).toMap() +
                    ("detail" to JsonPrimitive(s.detail.name.lowercase())),
            )
            runCatching { LayoutTree.of(client.call("layout.tree", params).result.jsonObject) }
                .onSuccess { tree -> mutable.update { captured(it, tree) } }
                .onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) mutable.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) } }
        }
    }

    /** The new capture with the selection, expansion and search carried over. */
    private fun captured(old: LayoutViewState, tree: LayoutTree): LayoutViewState {
        val previous = old.tree
        val reselected = if (previous != null && old.selected != null) TreeSearch.reselect(previous, old.selected, tree) else null
        val search = old.search?.let { TreeSearch.find(tree, it.query) }
        val selected = reselected?.path
        val expanded = (if (previous == null) tree.initiallyExpanded else old.expanded) +
            ancestors(selected) + search?.ancestors.orEmpty()
        return old.copy(tree = tree, selected = selected, note = reselected?.note, search = search, expanded = expanded, loading = false, error = null)
    }

    fun setDetail(level: DetailLevel) {
        mutable.update { it.copy(detail = level) }
        refresh()
    }

    fun select(path: List<Int>?) = mutable.update { it.copy(selected = path, note = null, expanded = it.expanded + ancestors(path)) }

    fun setExpanded(path: List<Int>, expanded: Boolean) =
        mutable.update { it.copy(expanded = if (expanded) it.expanded + listOf(path) else it.expanded - listOf(path)) }

    fun search(query: String) = mutable.update { s ->
        val tree = s.tree
        if (query.isBlank() || tree == null) s.copy(search = null)
        else TreeSearch.find(tree, query).let { r -> s.copy(search = r, expanded = s.expanded + r.ancestors) }
    }

    fun setHideNonMatching(on: Boolean) = mutable.update { it.copy(hideNonMatching = on) }

    fun nextMatch() = mutable.value.search?.next(mutable.value.selected)?.let { select(it) }

    fun previousMatch() = mutable.value.search?.previous(mutable.value.selected)?.let { select(it) }

    private fun ancestors(path: List<Int>?): Set<List<Int>> = path?.let { p -> (1 until p.size).map { p.take(it) }.toSet() }.orEmpty()
}
