package io.yamsergey.dta.sidekick.network

import android.content.Context
import android.os.Process
import android.util.Log
import io.yamsergey.dta.protocol.network.NetworkLibraries
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.sidekick.hooks.HookAttach
import io.yamsergey.dta.sidekick.network.javaws.JavaWebSocketCapture
import io.yamsergey.dta.sidekick.network.nv.NvWebSocketCapture
import io.yamsergey.dta.sidekick.network.okhttp.OkHttpCapture
import io.yamsergey.dta.sidekick.network.script.ScriptRuntime
import io.yamsergey.dta.sidekick.network.script.ScriptStore
import io.yamsergey.dta.sidekick.network.okhttp.OkHttpWebSocketCapture
import io.yamsergey.dta.sidekick.network.store.NetworkStore
import io.yamsergey.dta.sidekick.network.store.StorageDir
import io.yamsergey.dta.sidekick.network.urlconnection.UrlConnectionCapture
import io.yamsergey.dta.sidekick.server.SidekickServer
import java.io.File

/**
 * Process-wide network capture: the store, the recorder and the per-library hooks.
 * Started from [io.yamsergey.dta.sidekick.hooks.HooksProvider] right after the agent
 * attaches — capture is observation and runs without a client (constitution v1.3.0).
 * The store exists even when hooks are unavailable, so methods answer with the stated
 * reason instead of failing.
 */
object NetworkRuntime {

    private const val TAG = "DtaNetwork"

    @Volatile
    var store: NetworkStore? = null
        private set

    /** Records into the app's history; links (feature 012) use it too. */
    @Volatile
    var recorder: NetworkRecorder? = null
        private set

    /** Pushes messages into the app's WebSocket connections (feature 020); null without hooks. */
    @Volatile
    var injector: WsInjector? = null
        private set

    /** The one network script of this process (feature 005). */
    val scripts = ScriptRuntime()

    @Volatile
    var scriptStore: ScriptStore = ScriptStore(null)
        private set

    @Volatile
    private var libraries: List<LibraryState> = NetworkLibraries.ALL.map { LibraryState(it, present = false, installFailure = null) }

    @Synchronized
    fun start(context: Context) {
        if (store != null) return
        val root = File(context.noBackupFilesDir, "dta/network")
        scriptStore = ScriptStore(root)
        val created = NetworkStore(StorageDir(root), pid = Process.myPid())
        val recorder = NetworkRecorder(created, observe = { entry ->
            io.yamsergey.dta.sidekick.ui.UiRuntime.observe(entry)
            io.yamsergey.dta.sidekick.timeline.TimelineSources.observe(entry)
        }) { name, data -> SidekickServer.sessions.publish(name, data) }
        store = created
        this.recorder = recorder
        restoreScript()
        if (!HookAttach.attached) return
        val loader = context.classLoader
        val initiators = Initiators()
        val wsScripting = WsScripting(recorder, scripts)
        val injector = WsInjector(recorder.pushRecorder(), io.yamsergey.dta.sidekick.util.ThreadScheduler("dta-ws-push"))
        this.injector = injector
        scripts.host = ScriptPushes(injector)
        libraries = listOf(
            { OkHttpCapture.install(loader, recorder, initiators, scripts) },
            { UrlConnectionCapture.install(recorder, initiators, scripts) },
            { OkHttpWebSocketCapture.install(loader, recorder, initiators, wsScripting, injector) },
            { NvWebSocketCapture.install(loader, recorder, initiators, wsScripting, injector) },
            { JavaWebSocketCapture.install(loader, recorder, initiators, wsScripting, injector) },
            { NetworkCapability.probeGrpc(loader) },
            { NetworkCapability.probeKtor(loader) },
        ).zip(NetworkLibraries.ALL).map { (install, name) ->
            try {
                install()
            } catch (t: Throwable) {
                LibraryState(name, present = true, installFailure = io.yamsergey.dta.sidekick.capability.Reasons.LIBRARY_SETUP_FAILED, installFailureDetail = "${t.javaClass.name}: ${t.message}")
            }
        }
        // One line per library not fully captured, naming each hook not installed (FR-011).
        libraries.forEach { lib ->
            lib.installFailure?.let { Log.w(TAG, "${lib.name}: nothing is captured: $it (${lib.installFailureDetail})") }
            lib.events.filterNot { it.captured }.forEach { e ->
                Log.w(TAG, "${lib.name}: ${e.event} not captured: " + e.tried.joinToString("; ") { (sig, r) -> "${sig.method}${sig.descriptor}: ${r.code} ${r.describe()}" + ((r as? io.yamsergey.dta.sidekick.hooks.Hooks.Install.Failed)?.detail?.let { " ($it)" } ?: "") })
            }
        }
    }

    /**
     * Re-activates the persisted network script before any library hook exists, so the
     * first request of this process is already scripted (feature 005 FR-004), and says so
     * in the device log at every start (constitution v1.4.0, FR-019).
     */
    private fun restoreScript() {
        val stored = scriptStore.load() ?: return
        scripts.restore(stored, hooksUnavailable = if (HookAttach.attached) null else HookAttach.reason)
        val summary = scripts.summary() ?: return
        if (summary.unavailable == null) {
            Log.w(TAG, "network script ${summary.id} active since ${io.yamsergey.dta.sidekick.util.IsoTime.format(summary.installedAtMs)}; it changes this app's network traffic; remove it with network_script_clear")
        } else {
            Log.w(TAG, "network script ${summary.id} is installed but NOT applied: ${summary.unavailable}")
        }
    }

    fun capability(): CapabilityStatus {
        val bounds = store?.bounds ?: io.yamsergey.dta.sidekick.network.store.StoreBounds()
        return NetworkCapability.status(HookAttach.reason, libraries, bounds, scripts.summary(), HookAttach.detail)
    }
}
