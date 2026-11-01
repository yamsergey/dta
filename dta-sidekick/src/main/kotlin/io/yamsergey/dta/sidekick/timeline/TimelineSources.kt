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

    private var started = false

    @Volatile
    private var app: AppStateBridge? = null

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
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
            val reason = HookAttach.reason ?: "hooks not attached"
            TimelineRuntime.register(Sources.INPUT) { SourceStatus(Sources.INPUT, SourceState.OFF, "hooks unavailable: $reason") }
            TimelineRuntime.register(Sources.APP) { SourceStatus(Sources.APP, SourceState.OFF, "hooks unavailable: $reason") }
            return
        }
        try {
            val bridge = AppStateBridge(buffer, TimelineRuntime.facets, scope)
            bridge.install(context.classLoader)
            app = bridge
            TimelineRuntime.register(Sources.APP, bridge::status)
        } catch (t: Throwable) {
            Log.w(TAG, "the app's own source not read: $t")
            TimelineRuntime.register(Sources.APP) { SourceStatus(Sources.APP, SourceState.OFF, "${t.javaClass.simpleName}: ${t.message}") }
        }
        try {
            val input = InputRecorder(buffer, scope) { root -> UiRuntime.windowIdOf(root) }
            input.install(context.classLoader)
            TimelineRuntime.register(Sources.INPUT, input::status)
        } catch (t: Throwable) {
            Log.w(TAG, "input not recorded: $t")
            TimelineRuntime.register(Sources.INPUT) { SourceStatus(Sources.INPUT, SourceState.OFF, "${t.javaClass.simpleName}: ${t.message}") }
        }
    }

    /** For the network recorder: every stored entry. */
    fun observe(entry: io.yamsergey.dta.protocol.network.NetworkEntry) {
        runCatching { network?.observe(entry) }
    }
}
