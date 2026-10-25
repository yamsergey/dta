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

    @Volatile
    private var libraries: List<LibraryState> = NetworkLibraries.ALL.map { LibraryState(it, present = false, installFailure = null) }

    @Synchronized
    fun start(context: Context) {
        if (store != null) return
        val created = NetworkStore(StorageDir(File(context.noBackupFilesDir, "dta/network")), pid = Process.myPid())
        val recorder = NetworkRecorder(created) { name, data -> SidekickServer.sessions.publish(name, data) }
        store = created
        if (!HookAttach.attached) return
        val loader = context.classLoader
        val initiators = Initiators()
        libraries = listOf(
            { OkHttpCapture.install(loader, recorder, initiators) },
            { UrlConnectionCapture.install(recorder, initiators) },
            { OkHttpWebSocketCapture.install(loader, recorder, initiators) },
            { NvWebSocketCapture.install(loader, recorder, initiators) },
            { JavaWebSocketCapture.install(loader, recorder, initiators) },
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
        return NetworkCapability.status(HookAttach.reason, libraries, bounds)
    }
}
