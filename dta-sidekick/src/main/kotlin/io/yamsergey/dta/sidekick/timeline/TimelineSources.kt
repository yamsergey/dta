package io.yamsergey.dta.sidekick.timeline

import android.content.Context
import android.util.Log
import io.yamsergey.dta.protocol.timeline.SourceStatus
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineCosts
import io.yamsergey.dta.protocol.ui.SourceState
import io.yamsergey.dta.sidekick.hooks.HookAttach
import io.yamsergey.dta.sidekick.app.AppStateBridge
import io.yamsergey.dta.sidekick.input.InputRecorder
import io.yamsergey.dta.sidekick.network.NetworkTimeline
import io.yamsergey.dta.sidekick.server.SidekickServer
import io.yamsergey.dta.sidekick.ui.UiRuntime

/**
 * Starts the timeline's sources beyond the UI journal (feature 016): input with its handling
 * scope, network exchanges. Idempotent, observation only, never throws into the app.
 */
object TimelineSources {
    private const val TAG = "DtaTimeline"

    val scope = HandlingScope()

    @Volatile
    var network: NetworkTimeline? = null
        private set

    @Volatile
    private var started = false

    /** Feature 056: whether the sources run in this process. */
    val running: Boolean get() = started

    /** Feature 056: where this app keeps its timeline switch. */
    fun store(context: Context) = TimelineSwitchStore(java.io.File(context.noBackupFilesDir, "dta"))

    /** Feature 056: the app-start entry: the sources when this app's switch is on, else the off statement. */
    fun startIfOn(context: Context) {
        if (store(context).on) start(context)
        else if (!started) TimelineRuntime.register(TimelineSwitchMethods.SOURCE) { TimelineSwitchMethods.offStatus() }
    }

    @Volatile
    private var app: AppStateBridge? = null

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        TimelineRuntime.unregister(TimelineSwitchMethods.SOURCE)
        val buffer = TimelineRuntime.start { name, data -> SidekickServer.sessions.publish(name, data) }
        TimelineRuntime.scopeCause = { scope.current() }
        network = NetworkTimeline(buffer)
        TimelineRuntime.register(Sources.NETWORK) {
            SourceStatus(Sources.NETWORK, SourceState.ON, detail = "HTTP requests and their responses; bodies with network.entry")
        }
        TimelineMethods.costs = {
            val (count, median, max) = scope.costs()
            TimelineCosts(UiRuntime.costs(), count, median * 1_000, max * 1_000, scope.staleDropped, app?.decoder?.calls ?: 0)
        }
        if (!HookAttach.attached) {
            val reason = HookAttach.reason ?: io.yamsergey.dta.sidekick.capability.Reasons.HOOKS_STARTING
            TimelineRuntime.register(Sources.INPUT) { SourceStatus(Sources.INPUT, SourceState.OFF, io.yamsergey.dta.sidekick.capability.Reasons.offBecauseHooks(io.yamsergey.dta.sidekick.capability.Reasons.INPUT, reason)) }
            TimelineRuntime.register(Sources.APP) { SourceStatus(Sources.APP, SourceState.OFF, io.yamsergey.dta.sidekick.capability.Reasons.offBecauseHooks(io.yamsergey.dta.sidekick.capability.Reasons.APP_SOURCE, reason)) }
            TimelineRuntime.register(Sources.NAV) { SourceStatus(Sources.NAV, SourceState.OFF, io.yamsergey.dta.sidekick.capability.Reasons.offBecauseHooks(io.yamsergey.dta.sidekick.capability.Reasons.NAVIGATION, reason)) }
            return
        }
        try {
            val events = io.yamsergey.dta.sidekick.nav.NavigationEvents(
                buffer, TimelineRuntime.facets, android.os.SystemClock::uptimeMillis, System::currentTimeMillis, scope::current,
            )
            val nav = io.yamsergey.dta.sidekick.nav.NavigationAdapters(events)
            nav.install(context.classLoader)
            TimelineRuntime.register(Sources.NAV, nav::status)
        } catch (t: Throwable) {
            Log.w(TAG, "navigation not read: $t")
            TimelineRuntime.register(Sources.NAV) { SourceStatus(Sources.NAV, SourceState.OFF, "${io.yamsergey.dta.sidekick.capability.Reasons.NAVIGATION}: ${io.yamsergey.dta.sidekick.capability.Reasons.SOURCE_FAILED}") }
        }
        try {
            val bridge = AppStateBridge(buffer, TimelineRuntime.facets, scope)
            bridge.install(context.classLoader)
            app = bridge
            TimelineRuntime.register(Sources.APP, bridge::status)
        } catch (t: Throwable) {
            Log.w(TAG, "the app's own source not read: $t")
            TimelineRuntime.register(Sources.APP) { SourceStatus(Sources.APP, SourceState.OFF, "${io.yamsergey.dta.sidekick.capability.Reasons.APP_SOURCE}: ${io.yamsergey.dta.sidekick.capability.Reasons.SOURCE_FAILED}") }
        }
        try {
            val input = InputRecorder(buffer, scope) { root -> UiRuntime.windowIdOf(root) }
            input.install(context.classLoader)
            if (io.yamsergey.dta.sidekick.layout.compose.ComposePresence().composePresent) {
                val compose = io.yamsergey.dta.sidekick.input.ComposeClickRecorder(buffer, scope) { root -> UiRuntime.windowIdOf(root) }
                compose.install(context.classLoader)
                input.composeFailure = compose.failure
                input.composeLimit = compose.limit
            }
            TimelineRuntime.register(Sources.INPUT, input::status)
        } catch (t: Throwable) {
            Log.w(TAG, "input not recorded: $t")
            TimelineRuntime.register(Sources.INPUT) { SourceStatus(Sources.INPUT, SourceState.OFF, "${io.yamsergey.dta.sidekick.capability.Reasons.INPUT}: ${io.yamsergey.dta.sidekick.capability.Reasons.SOURCE_FAILED}") }
        }
    }

    /** For the network recorder: every stored entry. */
    fun observe(entry: io.yamsergey.dta.protocol.network.NetworkEntry) {
        runCatching { network?.observe(entry) }
    }
}
