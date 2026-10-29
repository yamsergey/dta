package io.yamsergey.dta.studio

import com.android.tools.idea.adb.AdbFileProvider
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.extensions.PluginId
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
    val session = DaemonSession(connect = { DaemonLauncher(version = version, spawner = ClasspathSpawner(launch())).ensure(ClientKind.IDE) }, scope = scope)
    private val started = AtomicBoolean(false)

    /** Starts (or joins) the daemon the first time any project needs it. */
    fun ensureStarted() {
        if (started.compareAndSet(false, true)) session.start()
    }

    private fun launch(): DaemonLaunch {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID)) ?: error("plugin $PLUGIN_ID is not loaded")
        val java = File(System.getProperty("java.home"), "bin/java")
        val adb = runCatching { AdbFileProvider.fromApplication().get() }.getOrNull()
        return DaemonLaunch.fromDirectory(java, plugin.pluginPath.resolve("daemon").toFile(), listOfNotNull(adb?.let { "DTA_ADB" to it.path }).toMap())
    }

    override fun dispose() = session.dispose()

    companion object {
        const val PLUGIN_ID = "io.yamsergey.dta"

        fun get(): DtaApplicationService = ApplicationManager.getApplication().getService(DtaApplicationService::class.java)
    }
}
