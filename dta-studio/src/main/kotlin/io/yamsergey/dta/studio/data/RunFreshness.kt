package io.yamsergey.dta.studio.data

import io.yamsergey.dta.protocol.ops.AppConnectionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * D-122: what a tab that reads the running app once (Runtime) last read it for. It is stale when the chosen app is another,
 * and also when the same app ran again: an app restart changes nothing in the device and app chosen, only the run, which
 * the app's connection events tell (`connected` for the chosen app is a new run; [onNewRun] is called then).
 */
class RunFreshness(
    connections: Flow<AppConnectionEvent>,
    private val chosen: () -> Pair<String?, String?>,
    scope: CoroutineScope,
    private val onNewRun: () -> Unit = {},
) {
    private var loadedFor: Triple<String?, String?, Int>? = null
    @Volatile private var runs = 0

    init {
        scope.launch {
            connections.collect { e ->
                if (e.state == "connected" && (e.device to e.app) == chosen()) { runs++; onNewRun() }
            }
        }
    }

    fun stale(device: String?, app: String?): Boolean = loadedFor != Triple(device, app, runs)

    /** The tab is reading for the chosen app's current run. */
    fun mark(device: String?, app: String?) { loadedFor = Triple(device, app, runs) }
}
