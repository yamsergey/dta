package io.yamsergey.dta.plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.android.tools.idea.adb.AdbFileProvider
import io.yamsergey.dta.daemon.DtaOrchestrator
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager.Device
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager.SidekickSocket
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager.ConnectionInfo
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Application-level service that manages device discovery, connections,
 * and background data polling for the DTA Inspector tool window.
 *
 * Uses [DtaOrchestrator] for all data operations — the shared brain that
 * manages sidekick connections, CDP watchers, SSE events, and data enrichment.
 */
@Service(Service.Level.APP)
class DtaService : Disposable {

    private val log = Logger.getInstance(DtaService::class.java)
    private val orchestrator = DtaOrchestrator.getInstance()
    private val connectionManager = SidekickConnectionManager.getInstance()
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { r ->
        Thread(r, "dta-service-scheduler").apply { isDaemon = true }
    }

    private var pollingFuture: ScheduledFuture<*>? = null

    @Volatile var devices: List<Device> = emptyList(); private set
    @Volatile var selectedDevice: Device? = null
    @Volatile var apps: List<SidekickSocket> = emptyList(); private set
    @Volatile var selectedApp: SidekickSocket? = null
    @Volatile var connected: Boolean = false; private set

    @Volatile var connectionStatus: String = "Disconnected"; private set

    // Data caches
    @Volatile var layoutTreeJson: String? = null; private set
    @Volatile var screenshotBytes: ByteArray? = null; private set
    @Volatile var networkRequestsJson: String? = null; private set
    @Volatile var webSocketConnectionsJson: String? = null; private set

    private val listeners = CopyOnWriteArrayList<DtaServiceListener>()

    init {
        // Use Android Studio's SDK configuration to find ADB
        try {
            val adbFile = AdbFileProvider.fromApplication().get()
            if (adbFile != null) {
                SidekickConnectionManager.setAdbPath(adbFile.absolutePath)
            }
        } catch (e: Exception) {
            log.warn("Could not resolve ADB path from Android Studio", e)
        }
    }

    interface DtaServiceListener {
        fun onDevicesChanged(devices: List<Device>) {}
        fun onAppsChanged(apps: List<SidekickSocket>) {}
        fun onConnectionChanged(status: String, connected: Boolean) {}
        fun onLayoutDataChanged(treeJson: String?, screenshotBytes: ByteArray?) {}
        fun onNetworkDataChanged(json: String?) {}
        fun onWebSocketDataChanged(json: String?) {}
    }

    fun addListener(listener: DtaServiceListener) { listeners.add(listener) }
    fun removeListener(listener: DtaServiceListener) { listeners.remove(listener) }

    companion object {
        @JvmStatic
        fun getInstance(): DtaService =
            ApplicationManager.getApplication().getService(DtaService::class.java)
    }

    // ========================================================================
    // Device and app discovery
    // ========================================================================

    fun refreshDevices() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val found = orchestrator.listDevices()
                    .filter { it.state() == "device" }
                devices = found
                notifyOnEdt { it.onDevicesChanged(found) }

                if (selectedDevice == null && found.isNotEmpty()) {
                    selectDevice(found[0])
                } else if (selectedDevice != null && found.none { it.serial() == selectedDevice!!.serial() }) {
                    selectDevice(found.firstOrNull())
                }
            } catch (e: Exception) {
                log.warn("Failed to list devices", e)
                devices = emptyList()
                notifyOnEdt { it.onDevicesChanged(emptyList()) }
            }
        }
    }

    fun selectDevice(device: Device?) {
        selectedDevice = device
        selectedApp = null
        connected = false
        updateConnectionStatus("Disconnected")
        if (device != null) {
            refreshApps()
        } else {
            apps = emptyList()
            notifyOnEdt { it.onAppsChanged(emptyList()) }
        }
    }

    fun refreshApps() {
        val device = selectedDevice ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val found = orchestrator.listApps(device.serial())
                apps = found
                notifyOnEdt { it.onAppsChanged(found) }

                if (selectedApp == null && found.isNotEmpty()) {
                    selectApp(found[0])
                }
            } catch (e: Exception) {
                log.warn("Failed to find sidekick sockets", e)
                apps = emptyList()
                notifyOnEdt { it.onAppsChanged(emptyList()) }
            }
        }
    }

    fun selectApp(app: SidekickSocket?) {
        selectedApp = app
        connected = false
        stopPolling()
        if (app != null) {
            connect()
        } else {
            updateConnectionStatus("Disconnected")
        }
    }

    // ========================================================================
    // Connection — delegates to DtaOrchestrator which handles CDP + SSE
    // ========================================================================

    private fun connect() {
        val app = selectedApp ?: return
        val device = selectedDevice ?: return
        updateConnectionStatus("Connecting...")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // DtaOrchestrator.getConnectionWithCdp handles:
                // - sidekick connection
                // - CDP port forwarding
                // - CDP watcher start
                // - SSE listener for Custom Tab events
                orchestrator.getConnectionWithCdp(app.packageName(), device.serial())
                connected = true
                updateConnectionStatus("Connected")
                startPolling()
            } catch (e: Exception) {
                log.warn("Failed to connect to ${app.packageName()}", e)
                connected = false
                updateConnectionStatus("Connection failed")
            }
        }
    }

    fun reconnect() {
        val app = selectedApp
        val device = selectedDevice
        if (app != null && device != null) {
            orchestrator.disconnect(app.packageName(), device.serial())
        }
        connected = false
        stopPolling()
        clearDataCaches()
        connect()
    }

    // ========================================================================
    // Background polling — uses DtaOrchestrator for enriched data
    // ========================================================================

    private fun startPolling() {
        stopPolling()
        pollingFuture = scheduler.scheduleWithFixedDelay(::pollData, 0, 3, TimeUnit.SECONDS)
    }

    private fun stopPolling() {
        pollingFuture?.cancel(false)
        pollingFuture = null
    }

    private fun pollData() {
        val app = selectedApp ?: return
        val device = selectedDevice ?: return
        if (!connected) return

        fetchLayoutData(app.packageName(), device.serial())
        fetchNetworkData(app.packageName(), device.serial())
        fetchWebSocketData(app.packageName(), device.serial())
    }

    private fun fetchLayoutData(packageName: String, device: String) {
        try {
            // Uses orchestrator for enriched tree (WebView/CustomTab DOM)
            val treeNode = orchestrator.getLayoutTree(packageName, device, null, null, null, null)
            val tree = treeNode?.toString()
            val screenshotResult = orchestrator.getScreenshot(packageName, device)
            val screenshot = if (screenshotResult is io.yamsergey.dta.tools.sugar.Success) screenshotResult.value() else null

            if (tree != layoutTreeJson || screenshot != null) {
                layoutTreeJson = tree
                screenshotBytes = screenshot
                notifyOnEdt { it.onLayoutDataChanged(tree, screenshot) }
            }
        } catch (e: Exception) {
            log.debug("Layout fetch failed: ${e.message}")
        }
    }

    private fun fetchNetworkData(packageName: String, device: String) {
        try {
            val json = orchestrator.getNetworkRequests(packageName, device)
            if (json != null && json != networkRequestsJson) {
                networkRequestsJson = json
                notifyOnEdt { it.onNetworkDataChanged(json) }
            }
        } catch (e: Exception) {
            log.debug("Network fetch failed: ${e.message}")
        }
    }

    private fun fetchWebSocketData(packageName: String, device: String) {
        try {
            val json = orchestrator.getWebSocketConnections(packageName, device)
            if (json != null && json != webSocketConnectionsJson) {
                webSocketConnectionsJson = json
                notifyOnEdt { it.onWebSocketDataChanged(json) }
            }
        } catch (e: Exception) {
            log.debug("WebSocket fetch failed: ${e.message}")
        }
    }

    private fun clearDataCaches() {
        layoutTreeJson = null
        screenshotBytes = null
        networkRequestsJson = null
        webSocketConnectionsJson = null
        notifyOnEdt {
            it.onLayoutDataChanged(null, null)
            it.onNetworkDataChanged(null)
            it.onWebSocketDataChanged(null)
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun updateConnectionStatus(status: String) {
        connectionStatus = status
        notifyOnEdt { it.onConnectionChanged(status, connected) }
    }

    private inline fun notifyOnEdt(crossinline action: (DtaServiceListener) -> Unit) {
        SwingUtilities.invokeLater {
            listeners.forEach { listener ->
                try {
                    action(listener)
                } catch (e: Exception) {
                    log.warn("Listener callback failed", e)
                }
            }
        }
    }

    override fun dispose() {
        stopPolling()
        scheduler.shutdownNow()
        orchestrator.shutdown()
        listeners.clear()
    }
}
