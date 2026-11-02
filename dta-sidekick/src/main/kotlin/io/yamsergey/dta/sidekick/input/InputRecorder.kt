package io.yamsergey.dta.sidekick.input

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.SourceStatus
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.ui.SourceState
import io.yamsergey.dta.shim.EntryHandler
import io.yamsergey.dta.shim.ExitHandler
import io.yamsergey.dta.sidekick.hooks.Hooks
import io.yamsergey.dta.sidekick.timeline.HandlingScope
import io.yamsergey.dta.sidekick.timeline.TimelineBuffer

/**
 * Clicks on Views and back (feature 016 US7, research R3, R6): ENTRY/EXIT hooks on the methods
 * that run the app's handlers. The entry records the input and opens a [scope]; the exit closes
 * it. Observes only: the click is the app's own, never generated. Never throws into the app.
 */
class InputRecorder(
    private val buffer: TimelineBuffer,
    private val scope: HandlingScope,
    /** The journal's id of the window a root view belongs to (015), when it knows it. */
    private val windowIdOf: (View) -> String? = { null },
) {
    private val failures = LinkedHashMap<String, String>()

    fun install(loader: ClassLoader?) {
        hook("View#performClick", Hooks.entry(View::class.java, "performClick", "()Z", EntryHandler { self, _ -> guard { clicked(self as View) } }))
        hook("View#performClick exit", Hooks.exit(View::class.java, "performClick", "()Z", ExitHandler { r -> guard { scope.exit() }; r }))
        val dispatcher = runCatching { Class.forName(DISPATCHER, false, loader) }.getOrNull()
        if (dispatcher != null) {
            hook("OnBackPressedDispatcher#onBackPressed", Hooks.entry(dispatcher, "onBackPressed", "()V", EntryHandler { _, _ -> guard { back("dispatcher") } }))
            hook("OnBackPressedDispatcher#onBackPressed exit", Hooks.exit(dispatcher, "onBackPressed", "()V", ExitHandler { r -> guard { closeBack() }; r }))
        }
        // AndroidX activity 1.12+ hands back to the navigationevent library, not always to onBackPressed.
        val input = runCatching { Class.forName(NAVIGATION_EVENT_INPUT, false, loader) }.getOrNull()
        if (input != null) {
            hook("NavigationEventInput#dispatchOnBackCompleted", Hooks.entry(input, "dispatchOnBackCompleted", "()V", EntryHandler { _, _ -> guard { back("navigation-event") } }))
            hook("NavigationEventInput#dispatchOnBackCompleted exit", Hooks.exit(input, "dispatchOnBackCompleted", "()V", ExitHandler { r -> guard { closeBack() }; r }))
        }
        hook("Activity#onBackPressed", Hooks.entry(Activity::class.java, "onBackPressed", "()V", EntryHandler { _, _ -> guard { back("activity") } }))
        hook("Activity#onBackPressed exit", Hooks.exit(Activity::class.java, "onBackPressed", "()V", ExitHandler { r -> guard { closeBack() }; r }))
    }

    /** Why Compose clicks are not recorded, when they are not (set by the Compose recorder). */
    @Volatile
    var composeFailure: String? = null

    fun status(): SourceStatus {
        composeFailure?.let { failures["Compose"] = it }
        val clicks = failures.filterKeys { it.startsWith("View") }
        return when {
            clicks.isNotEmpty() -> SourceStatus(Sources.INPUT, SourceState.OFF, clicks.entries.joinToString("; ") { "${it.key}: ${it.value}" })
            failures.isNotEmpty() -> SourceStatus(Sources.INPUT, SourceState.DEGRADED, failures.entries.joinToString("; ") { "${it.key}: ${it.value}" }, DETAIL)
            else -> SourceStatus(Sources.INPUT, SourceState.ON, detail = DETAIL)
        }
    }

    /** Back scopes opened by this recorder, so a nested back (activity → dispatcher) closes once. */
    private val backOpened = ArrayList<Boolean>()

    private fun clicked(view: View) {
        val window = runCatching { view.rootView.layoutParams as? WindowManager.LayoutParams }.getOrNull()
        val secure = window != null && (window.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        val name = runCatching { if (view.id != View.NO_ID) view.resources.getResourceEntryName(view.id) else null }.getOrNull()
        val target = InputEvents.Target(
            name = name,
            className = view.javaClass.simpleName.ifEmpty { view.javaClass.name },
            text = (view as? TextView)?.text?.toString() ?: view.contentDescription?.toString(),
            role = if (view.isClickable) "button" else null,
            secure = secure,
            windowId = runCatching { windowIdOf(view.rootView) }.getOrNull(),
        )
        val event = buffer.append { id, seq -> InputEvents.click(id, seq, SystemClock.uptimeMillis(), System.currentTimeMillis(), target, via = "view") }
        scope.enter(event.id, Kinds.CLICK)
    }

    private fun back(via: String) {
        if (!InputEvents.recordBack(scope.currentKind())) {
            backOpened += false
            return
        }
        val event = buffer.append { id, seq -> InputEvents.back(id, seq, SystemClock.uptimeMillis(), System.currentTimeMillis(), via) }
        scope.enter(event.id, Kinds.BACK)
        backOpened += true
    }

    private fun closeBack() {
        if (backOpened.removeLastOrNull() == true) scope.exit()
    }

    private fun hook(name: String, result: Hooks.Install) {
        when (result) {
            is Hooks.Install.Installed -> Unit
            is Hooks.Install.ClassAbsent -> failures[name] = "class absent"
            else -> failures[name] = result.describe()
        }
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val DISPATCHER = "androidx.activity.OnBackPressedDispatcher"
        const val NAVIGATION_EVENT_INPUT = "androidx.navigationevent.NavigationEventInput"
        const val DETAIL = "clicks on Views and composables, and back; work moved to another thread is not linked exactly unless the app declares it"
    }
}
