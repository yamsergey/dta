package io.yamsergey.dta.sidekick.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.protocol.ui.ContentSummary
import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.UiSource
import io.yamsergey.dta.protocol.ui.WindowKind
import io.yamsergey.dta.protocol.ui.WindowRef
import io.yamsergey.dta.shim.EntryHandler
import io.yamsergey.dta.shim.ShimReflect
import io.yamsergey.dta.sidekick.hooks.Hooks
import io.yamsergey.dta.sidekick.timeline.FacetState
import io.yamsergey.dta.protocol.timeline.FacetChange
import io.yamsergey.dta.protocol.timeline.Facets
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Records the app's windows as they are added and removed (feature 015 US1, research R1, R3,
 * R4): observe-only entry hooks on WindowManagerGlobal, a summary at addView and once the
 * window settles, a draw listener per window as its change signal. Runs on the main thread;
 * every handler swallows its own failures (constitution I). Each later settle of a window goes
 * to [elements], for what came and went inside it (US4).
 */
class WindowRecorder(
    private val buffer: UiJournalBuffer,
    private val walker: WindowWalker,
    private val links: (Long) -> List<io.yamsergey.dta.protocol.ui.EventLink>,
    private val isToastView: (View) -> Boolean,
    /** Elements that come and go inside the windows (US4); null when that source is off. */
    private val elements: ElementTracker? = null,
    /** Where the `ui.windows` facet is kept (016); null in tests that do not look at it. */
    private val facets: FacetState? = null,
    /** The top activity window settled with this content (016, the screen fingerprint). */
    private val onScreen: ((WindowRef, ContentSummary) -> Unit)? = null,
) {
    /** One window the recorder follows. */
    inner class Window internal constructor(
        ref: WindowRef,
        val view: WeakReference<View>,
        val appearedId: String,
        val addedMono: Long,
    ) {
        /** The window as known so far; its title is filled in once it settles. */
        var ref: WindowRef = ref
            internal set
        internal val settle = Settle()
        internal var listener: ViewTreeObserver.OnDrawListener? = null
        internal var checkPending = false
        internal var settledOnce = false
        var last: Summarized? = null
            internal set
    }

    private val main = Handler(Looper.getMainLooper())
    private val windows = WeakHashMap<View, Window>()
    private var counter = 0
    private var lastActivityGone: Pair<String, String>? = null
    private var lastActivityGoneAt = 0L

    /** Null when both hooks are in place; otherwise why windows are not recorded. */
    var failure: String? = null
        private set

    fun install(): String? {
        val wmg = try {
            Class.forName("android.view.WindowManagerGlobal")
        } catch (t: Throwable) {
            return "WindowManagerGlobal not found".also { failure = it }
        }
        val known = runCatching { ShimReflect.descriptors(wmg, "addView").toList() }.getOrDefault(emptyList())
        val add = ADD_VIEW.firstOrNull { it in known } ?: ADD_VIEW.first()
        val added = Hooks.entry(wmg, "addView", add, EntryHandler { _, params -> guard { onAdd(params) } })
        // removeView hands the view itself; doRemoveView also covers windows closed without it
        // (closeAll), when the root still holds its view.
        val removing = Hooks.entry(wmg, "removeView", REMOVE_VIEW_BY_VIEW, EntryHandler { _, params -> guard { (params[0] as? View)?.let(::removed) } })
        val removed = Hooks.entry(wmg, "doRemoveView", REMOVE_VIEW, EntryHandler { _, params -> guard { onRemove(params[0]) } })
        failure = listOf("addView" to added, "removeView" to removing, "doRemoveView" to removed).mapNotNull { (name, result) ->
            when (result) {
                is Hooks.Install.Installed -> null
                is Hooks.Install.ClassAbsent -> "$name: class absent"
                is Hooks.Install.Failed -> "$name: ${result.reason}"
            }
        }.takeIf { it.isNotEmpty() }?.joinToString("; ")
        return failure
    }

    /** The id of the window whose root view is [root] (016: a click's window). */
    fun windowIdOf(root: View): String? = windows[root]?.ref?.windowId

    /** The windows already open when recording starts (research R12). */
    fun presentAtStart() = onMain {
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val global = ShimReflect.call(wmg, null, "getInstance") ?: return@onMain
        @Suppress("UNCHECKED_CAST")
        val views = (ShimReflect.field(global, "mViews") as? List<View>)?.toList().orEmpty()
        @Suppress("UNCHECKED_CAST")
        val params = (ShimReflect.field(global, "mParams") as? List<WindowManager.LayoutParams>)?.toList().orEmpty()
        views.forEachIndexed { i, view ->
            if (view !in windows) appear(view, params.getOrNull(i), owner = null, kind = UiEventKind.WINDOW_PRESENT_AT_START)
        }
    }

    private fun onAdd(params: Array<Any?>) {
        val view = params[0] as? View ?: return
        val lp = params[1] as? WindowManager.LayoutParams
        val parent = params.getOrNull(3) as? android.view.Window
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // A window of another looper: its views are not ours to walk from here.
            main.post { guard { appear(view, lp, parent?.callback, UiEventKind.WINDOW_APPEARED, offMain = true) } }
            return
        }
        appear(view, lp, parent?.callback, UiEventKind.WINDOW_APPEARED)
    }

    private fun appear(view: View, lp: WindowManager.LayoutParams?, owner: Any?, kind: UiEventKind, offMain: Boolean = false) {
        if (isToastView(view)) return
        val type = lp?.type ?: 0
        val ownerObject = owner ?: activityOf(view.context)
        val hierarchy = generateSequence(ownerObject?.javaClass as Class<*>?) { it.superclass }.map { it.name }.toList()
        val windowKind = WindowKinds.kind(type, hierarchy)
        val secure = lp != null && (lp.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        val now = SystemClock.uptimeMillis()
        val summarized = if (offMain) null else timed { ContentSummaries.summarize(walker.walk(view), secure, settled = false, takenMonoMs = now) }
        val windowId = "w${++counter}"
        val ownerName = (ownerObject as? Activity)?.javaClass?.name ?: activityOf(view.context)?.javaClass?.name
        val recreated = if (windowKind == WindowKind.ACTIVITY && ownerName != null) {
            lastActivityGone?.takeIf { it.first == ownerName && now - lastActivityGoneAt < RECREATE_MS }?.second
        } else {
            null
        }
        val ref = WindowRef(
            windowId = windowId,
            kind = windowKind,
            title = WindowKinds.title(windowKind, ownerName, summarized?.heading, summarized?.firstText),
            owner = ownerName,
            type = type,
            secure = secure,
            recreatedFrom = recreated,
        )
        val event = buffer.append({ windowsFacet(windows.values.map { it.ref } + ref) }) { id, seq ->
            UiEvent(
                id, seq, now, System.currentTimeMillis(), kind, UiSource.WINDOW,
                window = ref,
                content = summarized?.summary,
                completeness = markers(summarized?.summary, secure, settled = false, offMain = offMain),
                links = links(System.currentTimeMillis()),
            )
        }
        attribute(event.id)
        val window = Window(ref, WeakReference(view), event.id, now)
        window.last = summarized
        windows[view] = window
        window.settle.added(now)
        val listener = ViewTreeObserver.OnDrawListener { guard { drew(window) } }
        if (runCatching { view.viewTreeObserver.addOnDrawListener(listener) }.isSuccess) window.listener = listener
        schedule(window)
    }

    private fun drew(window: Window) {
        window.settle.drew(SystemClock.uptimeMillis())
        schedule(window)
    }

    private fun schedule(window: Window) {
        if (window.checkPending) return
        val due = window.settle.dueAt() ?: return
        window.checkPending = true
        main.postAtTime({ guard { check(window) } }, due)
    }

    private fun check(window: Window) {
        window.checkPending = false
        val view = window.view.get() ?: return
        if (windows[view] !== window) return
        val now = SystemClock.uptimeMillis()
        val fired = window.settle.fire(now) ?: return schedule(window)
        val started = System.nanoTime()
        val summarized = timed { ContentSummaries.summarize(walker.walk(view), window.ref.secure, settled = true, takenMonoMs = now) }
        val costMs = (System.nanoTime() - started) / 1_000_000
        if (!window.settledOnce) {
            window.settledOnce = true
            val title = WindowKinds.title(window.ref.kind, window.ref.owner, summarized.heading, summarized.firstText) ?: window.ref.title
            window.ref = window.ref.copy(title = title)
            val settledEvent = buffer.append({ windowsFacet(windows.values.map { it.ref }) }) { id, seq ->
                UiEvent(
                    id, seq, now, System.currentTimeMillis(), UiEventKind.WINDOW_SETTLED, UiSource.WINDOW,
                    window = window.ref,
                    content = summarized.summary,
                    appearedId = window.appearedId,
                    completeness = markers(summarized.summary, window.ref.secure, settled = true, offMain = false),
                )
            }
            attribute(settledEvent.id)
        } else {
            window.last?.let { before -> elements?.settled(window.ref, before, summarized, fired.byCap, costMs, now) }
        }
        window.last = summarized
        if (topActivity() === window) onScreen?.invoke(window.ref, summarized.summary)
    }

    /** The app's activity window on top, by add order. */
    private fun topActivity(): Window? =
        windows.values.filter { it.ref.kind == WindowKind.ACTIVITY }.maxByOrNull { it.ref.windowId.removePrefix("w").toIntOrNull() ?: 0 }

    private fun onRemove(root: Any?) {
        root ?: return
        val view = try {
            ShimReflect.field(root, "mView") as? View
        } catch (_: Throwable) {
            null
        } ?: return
        removed(view)
    }

    private fun removed(view: View) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { guard { disappear(view) } }
        } else {
            disappear(view)
        }
    }

    private fun disappear(view: View) {
        val window = windows.remove(view) ?: return
        elements?.windowGone(window.ref.windowId)
        window.listener?.let { l -> runCatching { view.viewTreeObserver.removeOnDrawListener(l) } }
        val now = SystemClock.uptimeMillis()
        val owner = window.ref.owner
        if (window.ref.kind == WindowKind.ACTIVITY && owner != null) {
            lastActivityGone = owner to window.ref.windowId
            lastActivityGoneAt = now
        }
        val gone = buffer.append({ windowsFacet(windows.values.map { it.ref }) }) { id, seq ->
            UiEvent(
                id, seq, now, System.currentTimeMillis(), UiEventKind.WINDOW_DISAPPEARED, UiSource.WINDOW,
                window = window.ref,
                durationMs = now - window.addedMono,
                appearedId = window.appearedId,
            )
        }
        attribute(gone.id)
        if (window.ref.kind == WindowKind.ACTIVITY) topActivity()?.let { top -> top.last?.let { onScreen?.invoke(top.ref, it.summary) } }
    }

    /** The `ui.windows` change these windows make, bottom first (window ids are in add order). */
    private fun windowsFacet(open: List<WindowRef>): List<FacetChange> {
        val state = facets ?: return emptyList()
        val ordered = open.sortedBy { it.windowId.removePrefix("w").toIntOrNull() ?: Int.MAX_VALUE }
        return listOfNotNull(state.change(Facets.UI_WINDOWS, WindowsFacet.value(ordered), null))
    }

    private fun attribute(eventId: String) {
        facets?.attribute(listOf(Facets.UI_WINDOWS), eventId)
    }

    private val costsUs = ArrayDeque<Long>()

    private inline fun <T> timed(block: () -> T): T {
        val started = System.nanoTime()
        return block().also {
            synchronized(costsUs) {
                costsUs.addLast((System.nanoTime() - started) / 1_000)
                if (costsUs.size > COSTS_KEPT) costsUs.removeFirst()
            }
        }
    }

    /** The cost of the latest summaries on the main thread (SC-003). */
    fun costs(): io.yamsergey.dta.protocol.ui.UiCosts {
        val sorted = synchronized(costsUs) { costsUs.sorted() }
        if (sorted.isEmpty()) return io.yamsergey.dta.protocol.ui.UiCosts()
        return io.yamsergey.dta.protocol.ui.UiCosts(sorted.size, sorted[sorted.size / 2], sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)], sorted.last())
    }

    private fun markers(summary: ContentSummary?, secure: Boolean, settled: Boolean, offMain: Boolean) = buildList {
        if (offMain) add(DegradationMarker("ui", "${DegradationCodes.UI_FIELD_UNREADABLE}: the window was added off the main thread; its content was not read"))
        else if (!settled) add(DegradationMarker("ui", "${DegradationCodes.UI_NOT_SETTLED}: content as it was when the window was added"))
        if (secure) add(DegradationMarker("ui", "${DegradationCodes.UI_SECURE_WINDOW}: texts not recorded"))
        if ((summary?.omitted ?: 0) > 0) add(DegradationMarker("ui", "${DegradationCodes.UI_CONTENT_TRUNCATED}: ${summary!!.omitted} elements left out"))
    }

    private fun activityOf(context: Context?): Activity? {
        var c = context
        while (c != null) {
            if (c is Activity) return c
            c = (c as? ContextWrapper)?.baseContext
        }
        return null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) guard(block) else main.post { guard(block) }
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            errors++
            lastError = t
        }
    }

    @Volatile
    var errors = 0
        private set

    @Volatile
    var lastError: Throwable? = null
        private set

    companion object {
        /** API 30+, then API 28–29 (research R1). */
        val ADD_VIEW = listOf(
            "(Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;Landroid/view/Display;Landroid/view/Window;I)V",
            "(Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;Landroid/view/Display;Landroid/view/Window;)V",
        )
        const val REMOVE_VIEW = "(Landroid/view/ViewRootImpl;)V"
        const val REMOVE_VIEW_BY_VIEW = "(Landroid/view/View;Z)V"
        private const val RECREATE_MS = 2_000L
        private const val COSTS_KEPT = 500
    }
}
