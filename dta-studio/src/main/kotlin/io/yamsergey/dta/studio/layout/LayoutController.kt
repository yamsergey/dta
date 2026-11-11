package io.yamsergey.dta.studio.layout

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.present.layout.LayoutTree
import io.yamsergey.dta.present.layout.Outline
import io.yamsergey.dta.present.layout.ScreenHit
import io.yamsergey.dta.present.layout.ScreenPick
import io.yamsergey.dta.present.layout.outline
import io.yamsergey.dta.present.layout.SearchResult
import io.yamsergey.dta.present.layout.TreeSearch
import io.yamsergey.dta.present.layout.RefreshPolicy
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
    /** The tree as shown (containers hidden or not); [capture] is the whole capture. */
    val tree: LayoutTree? = null,
    val capture: LayoutTree? = null,
    /** Feature 057: layout-only containers hidden from the shown tree. */
    val hideContainers: Boolean = true,
    /** Feature 057: Auto follows the app's screen changes; Manual waits for Refresh. */
    val refresh: RefreshPolicy.Mode = RefreshPolicy.Mode.MANUAL,
    /** The line under the Refresh choice, and whether it is a caveat. */
    val reason: Pair<String, Boolean> = "" to false,
    /** When the shown capture was taken (epoch ms), and its main-thread time. */
    val capturedAt: Long? = null,
    val mainThreadMs: Long? = null,
    /** "<package> is in front of <app>" (US4). */
    val front: String? = null,
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

/** Feature 057: what the Layout view remembers per project. */
interface LayoutPrefs {
    var hideContainers: Boolean
    var autoRefresh: Boolean

    class InMemory(override var hideContainers: Boolean = true, override var autoRefresh: Boolean = false) : LayoutPrefs
}

/**
 * The Layout view's source (FR-011/012): `layout.tree` for the context's device and app, always
 * the full capture (feature 057: composition detail; the level choice is for agents), taken when
 * the view is shown, on Refresh, and — in Auto — after the app's screen-change signal, as
 * [RefreshPolicy] says. Nothing is captured or watched while the view is hidden. Works for apps
 * without DTA too (the fallback).
 */
class LayoutController(
    private val context: ContextController,
    private val scope: CoroutineScope,
    private val prefs: LayoutPrefs = LayoutPrefs.InMemory(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow(
        LayoutViewState(hideContainers = prefs.hideContainers, refresh = if (prefs.autoRefresh) RefreshPolicy.Mode.AUTO else RefreshPolicy.Mode.MANUAL),
    )
    val state: StateFlow<LayoutViewState> = mutable.asStateFlow()
    private var capture: Job? = null
    private var watching: Job? = null
    private var scheduled: Job? = null
    @Volatile private var active = false
    @Volatile private var stale = true
    private val policy = RefreshPolicy().apply { mode = mutable.value.refresh }

    init {
        scope.launch {
            context.state
                .map { Target(it.device, it.app, it.generation, context.client, it.connection == io.yamsergey.dta.present.context.Connection.NO_DTA) }
                .distinctUntilChanged()
                .collect { t ->
                    val moved = mutable.value.device != t.device || mutable.value.app != t.app
                    mutable.update {
                        if (moved) LayoutViewState(device = t.device, app = t.app, hideContainers = it.hideContainers, refresh = it.refresh, hideNonMatching = it.hideNonMatching) else it
                    }
                    stale = true
                    synchronized(policy) {
                        if (moved) policy.reset()
                        policy.signal = if (t.noDta) RefreshPolicy.Signal.NO_DTA else if (policy.signal == RefreshPolicy.Signal.NO_DTA) RefreshPolicy.Signal.WAITING else policy.signal
                    }
                    watch(t)
                    if (active) refresh()
                    reason()
                }
        }
        scope.launch {
            context.state.map { it.inFront to it.frontLine }.distinctUntilChanged().collect { (pkg, line) ->
                synchronized(policy) { policy.inFront = pkg }
                mutable.update { it.copy(front = line) }
                reason()
                schedule()
            }
        }
    }

    private data class Target(val device: String?, val app: String?, val generation: Int, val client: DaemonClient?, val noDta: Boolean)

    /** The view became visible ([on]) or hidden; showing it captures a stale screen. */
    fun setActive(on: Boolean) {
        active = on
        synchronized(policy) { policy.shown = on }
        watch(null)
        if (on && stale) refresh()
        if (!on) scheduled?.cancel()
    }

    /** Feature 057: Auto or Manual, remembered for the project. */
    fun setRefreshMode(mode: RefreshPolicy.Mode) {
        prefs.autoRefresh = mode == RefreshPolicy.Mode.AUTO
        synchronized(policy) { policy.mode = mode }
        mutable.update { it.copy(refresh = mode) }
        reason()
        schedule()
    }

    /** Feature 057: hides or shows layout-only containers in the shown tree, from the same capture. */
    fun setHideContainers(on: Boolean) {
        prefs.hideContainers = on
        mutable.update { s ->
            val shown = s.capture?.visible(on)
            val selected = s.selected?.takeIf { shown?.node(it) != null }
            s.copy(hideContainers = on, tree = shown, selected = selected, search = s.search?.let { r -> shown?.let { TreeSearch.find(it, r.query) } }, hover = null, pick = null)
        }
    }

    /** Subscribes to the app's screen signal while the view is shown and the app has DTA; else stops. */
    private fun watch(target: Target?) {
        val s = mutable.value
        val client = context.client
        val noDta = context.state.value.connection == io.yamsergey.dta.present.context.Connection.NO_DTA
        if (target != null) watching?.cancel()
        if (!active || client == null || s.device == null || s.app == null || noDta) {
            watching?.cancel(); watching = null
            return
        }
        if (watching?.isActive == true) return
        val params = JsonObject(mapOf("device" to JsonPrimitive(s.device), "app" to JsonPrimitive(s.app)))
        watching = scope.launch {
            runCatching {
                client.layoutChanges(params).collect { data ->
                    if (data["selection"] != null) return@collect
                    val state = (data["state"] as? JsonPrimitive)?.content
                    synchronized(policy) {
                        if (state != null) {
                            policy.signal = when (state) {
                                io.yamsergey.dta.protocol.layout.ScreenSignal.WATCHING -> RefreshPolicy.Signal.WATCHING
                                io.yamsergey.dta.protocol.layout.ScreenSignal.UNSUPPORTED -> RefreshPolicy.Signal.UNSUPPORTED
                                else -> RefreshPolicy.Signal.RECONNECTING
                            }
                        } else {
                            val at = (data["at"] as? JsonPrimitive)?.content?.toLongOrNull() ?: clock()
                            val why = (data["reasons"] as? kotlinx.serialization.json.JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
                            policy.changed(at, why)
                        }
                    }
                    reason()
                    schedule()
                }
            }
        }
    }

    /** Arms the next Auto capture when one is due. */
    private fun schedule() {
        val due = synchronized(policy) { policy.due(clock()) } ?: return
        scheduled?.cancel()
        scheduled = scope.launch {
            kotlinx.coroutines.delay((due - clock()).coerceAtLeast(0))
            val now = synchronized(policy) { policy.due(clock()) }
            if (now != null && now <= clock()) refresh() else schedule()
        }
    }

    private fun reason() {
        val line = synchronized(policy) { policy.line(clock()) }
        mutable.update { it.copy(reason = line) }
    }

    fun refresh() {
        val client = context.client ?: return
        val s = mutable.value
        val device = s.device ?: return
        stale = false
        capture?.cancel()
        scheduled?.cancel()
        synchronized(policy) { policy.captureStarted(clock()) }
        mutable.update { it.copy(loading = true, error = null) }
        capture = scope.launch {
            val params = JsonObject(
                listOfNotNull("device" to JsonPrimitive(device), s.app?.let { "app" to JsonPrimitive(it) }).toMap() +
                    ("detail" to JsonPrimitive("composition")) +
                    (if (s.screenShown) mapOf("screen" to JsonPrimitive(true)) else emptyMap()),
            )
            runCatching { client.call("layout.tree", params).result.jsonObject }
                .onSuccess { result ->
                    val mainMs = (result["mainThreadMs"] as? JsonPrimitive)?.content?.toLongOrNull()
                    val at = clock()
                    synchronized(policy) {
                        policy.captureDone(at, mainMs)
                        if (result["source"]?.let { (it as JsonPrimitive).content } == "uiautomator") policy.signal = RefreshPolicy.Signal.NO_DTA
                    }
                    val full = LayoutTree.of(result)
                    mutable.update { captured(it, full).copy(capturedAt = at, mainThreadMs = mainMs) }
                    reason()
                    schedule()
                }
                .onFailure { e ->
                    synchronized(policy) { policy.captureFailed() }
                    if (e !is kotlinx.coroutines.CancellationException) mutable.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
                }
        }
    }

    /** The new capture with the selection, expansion and search carried over. */
    private fun captured(old: LayoutViewState, full: LayoutTree): LayoutViewState {
        val tree = full.visible(old.hideContainers)
        val previous = old.tree
        val reselected = if (previous != null && old.selected != null) TreeSearch.reselect(previous, old.selected, tree) else null
        val search = old.search?.let { TreeSearch.find(tree, it.query) }
        val selected = reselected?.path
        val expanded = (if (previous == null) tree.initiallyExpanded else old.expanded) +
            ancestors(selected) + search?.ancestors.orEmpty()
        return old.copy(tree = tree, capture = full, selected = selected, note = reselected?.note, search = search, expanded = expanded, loading = false, error = null, hover = null, pick = null)
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
