package io.yamsergey.dta.studio

import com.android.tools.idea.adb.AdbFileProvider
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import io.yamsergey.dta.client.ClasspathSpawner
import io.yamsergey.dta.client.DaemonLaunch
import io.yamsergey.dta.client.DaemonLauncher
import io.yamsergey.dta.client.VersionInfo
import io.yamsergey.dta.protocol.ops.ClientKind
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One daemon connection per IDE (research R4): the daemon starts from the `daemon/` directory
 * this plugin ships, on the IDE's Java, with the adb Android Studio uses. Closing the IDE
 * closes the connection; the daemon keeps running for its other clients.
 */
@Service(Service.Level.APP)
class DtaApplicationService(scope: CoroutineScope) : Disposable {
    val version: VersionInfo = VersionInfo.current()
    val session = DaemonSession(connect = { connect(ClientKind.IDE) }, scope = scope)
    private val started = AtomicBoolean(false)

    /** Starts (or joins) the daemon the first time any project needs it. */
    fun ensureStarted() {
        if (started.compareAndSet(false, true)) session.start()
    }

    /** Connects to (or starts) the shared daemon as a client of [kind]: the tool window, or the agent server. */
    suspend fun connect(kind: ClientKind): DaemonLauncher.Connected =
        DaemonLauncher(version = version, spawner = ClasspathSpawner(launch())).ensure(kind)

    private fun launch(): DaemonLaunch {
        val java = File(System.getProperty("java.home"), "bin/java")
        val adb = runCatching { AdbFileProvider.fromApplication().get() }.getOrNull()
        return DaemonLaunch.fromDirectory(java, File(pluginDir(), "daemon"), listOfNotNull(adb?.let { "DTA_ADB" to it.path }).toMap())
    }

    override fun dispose() = session.dispose()

    companion object {
        const val PLUGIN_ID = "io.yamsergey.dta"

        /** The plugin's directory (it holds `lib/` with this class's jar, and `daemon/`). */
        fun pluginDir(): File {
            val jar = PathManager.getJarPathForClass(DtaApplicationService::class.java) ?: error("plugin $PLUGIN_ID: no jar for its classes")
            return File(jar).parentFile.parentFile
        }

        fun get(): DtaApplicationService = ApplicationManager.getApplication().getService(DtaApplicationService::class.java)
    }
}
