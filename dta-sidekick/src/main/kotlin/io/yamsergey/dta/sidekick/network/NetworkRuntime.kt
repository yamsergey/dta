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
import io.yamsergey.dta.protocol.network.ScriptSummary
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
        val recorder = NetworkRecorder(created) { name, data -> SidekickServer.sessions.publish(name, data) }
        store = created
        if (!HookAttach.attached) return
        val loader = context.classLoader
        val initiators = Initiators()
        val wsScripting = WsScripting(recorder, scripts)
        libraries = listOf(
            { OkHttpCapture.install(loader, recorder, initiators, scripts) },
            { UrlConnectionCapture.install(recorder, initiators, scripts) },
            { OkHttpWebSocketCapture.install(loader, recorder, initiators, wsScripting) },
            { NvWebSocketCapture.install(loader, recorder, initiators, wsScripting) },
            { JavaWebSocketCapture.install(loader, recorder, initiators, wsScripting) },
        ).zip(NetworkLibraries.ALL).map { (install, name) ->
            try {
                install()
            } catch (t: Throwable) {
                LibraryState(name, present = true, installFailure = "${t.javaClass.simpleName}: ${t.message}")
            }
        }
        libraries.filter { it.installFailure != null }.forEach { Log.w(TAG, "${it.name} not captured: ${it.installFailure}") }
    }

    fun capability(): CapabilityStatus {
        val bounds = store?.bounds ?: io.yamsergey.dta.sidekick.network.store.StoreBounds()
        val script = scripts.current()?.let { ScriptSummary(it.id, it.installedAtMs, scripts.unavailable) }
        return NetworkCapability.status(HookAttach.reason, libraries, bounds, script)
    }
}
