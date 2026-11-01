package io.yamsergey.dta.studio.layout

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.present.layout.LayoutTree
import io.yamsergey.dta.present.layout.Outline
import io.yamsergey.dta.present.layout.ScreenHit
import io.yamsergey.dta.present.layout.ScreenPick
import io.yamsergey.dta.present.layout.outline
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
    /** Feature 014: the screen pane is shown, so captures take the picture. */
    val screenShown: Boolean = true,
    /** What is under the pointer on the picture. */
    val hover: ScreenHit? = null,
    /** The last click on the picture: the nodes under it and which one is selected. */
    val pick: Pick? = null,
) {
    /** The selected node's outline on the picture. */
    val outline: Outline? get() = selected?.let { tree?.outline(it) }

    data class Pick(val paths: List<List<Int>>, val index: Int)
}

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
                    ("detail" to JsonPrimitive(s.detail.name.lowercase())) +
                    (if (s.screenShown) mapOf("screen" to JsonPrimitive(true)) else emptyMap()),
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
        return old.copy(tree = tree, selected = selected, note = reselected?.note, search = search, expanded = expanded, loading = false, error = null, hover = null, pick = null)
    }

    fun setDetail(level: DetailLevel) {
        mutable.update { it.copy(detail = level) }
        refresh()
    }

    fun select(path: List<Int>?) = mutable.update { it.copy(selected = path, note = null, expanded = it.expanded + ancestors(path), pick = null) }

    /** Shows or hides the screen pane; showing it recaptures when the capture has no picture. */
    fun setScreenShown(on: Boolean) {
        mutable.update { it.copy(screenShown = on) }
        if (on && mutable.value.tree?.screen == null && active) refresh()
    }

    /** The pointer is over image pixel ([x], [y]), or left the picture (null). */
    fun hover(x: Double?, y: Double? = null) = mutable.update { s ->
        val tree = s.tree
        s.copy(hover = if (x == null || y == null || tree == null) null else ScreenPick.at(tree, x, y))
    }

    /** A click on the picture: the deepest element under it, or why there is none. */
    fun pick(x: Double, y: Double) = mutable.update { s ->
        val tree = s.tree ?: return@update s
        when (val hit = ScreenPick.at(tree, x, y)) {
            is ScreenHit.Nodes -> {
                val first = hit.paths.first()
                s.copy(selected = first, note = null, expanded = s.expanded + ancestors(first), pick = LayoutViewState.Pick(hit.paths, 0))
            }
            else -> s.copy(selected = null, note = hit.statement, pick = null)
        }
    }

    /** The next element under the last click, from the deepest up to the window root, then round. */
    fun nextUnderPointer() = mutable.update { s ->
        val pick = s.pick ?: return@update s
        val next = pick.copy(index = (pick.index + 1) % pick.paths.size)
        val path = next.paths[next.index]
        s.copy(selected = path, note = null, expanded = s.expanded + ancestors(path), pick = next)
    }

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
