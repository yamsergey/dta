package io.yamsergey.dta.plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.android.tools.idea.adb.AdbFileProvider
import io.yamsergey.dta.daemon.DaemonClient
import io.yamsergey.dta.daemon.DaemonLauncher
import io.yamsergey.dta.daemon.DtaDaemon
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager.Device
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager.SidekickSocket
import tools.jackson.databind.ObjectMapper
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
 * Connects to the shared DTA daemon via HTTP (same daemon used by MCP and CLI).
 * Uses [DaemonLauncher] to discover or start the daemon automatically.
 */
@Service(Service.Level.APP)
class DtaService : Disposable {

    private val log = Logger.getInstance(DtaService::class.java)
    private val mapper = ObjectMapper()
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { r ->
        Thread(r, "dta-service-scheduler").apply { isDaemon = true }
    }

    private var pollingFuture: ScheduledFuture<*>? = null
    private var daemon: DaemonClient? = null
    private var embeddedDaemon: DtaDaemon? = null

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
    // Daemon connection
    // ========================================================================

    private fun ensureDaemon(): DaemonClient {
        daemon?.let { return it }

        // Try external daemon first (dta-cli server), fall back to embedded
        val client = try {
            DaemonLauncher.ensureDaemonRunning()
        } catch (e: Exception) {
            log.info("External daemon not available (${e.message}), starting embedded daemon")
            startEmbeddedDaemon()
        }
        daemon = client
        log.info("Connected to DTA daemon at ${client.baseUrl}")
        return client
    }

    private fun startEmbeddedDaemon(): DaemonClient {
        val dtaDaemon = DtaDaemon()
        val port = dtaDaemon.start(0)
        embeddedDaemon = dtaDaemon
        return DaemonClient("http://localhost:$port")
    }

    // ========================================================================
    // Device and app discovery
    // ========================================================================

    fun refreshDevices() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = ensureDaemon()
                val json = client.devices()
                val node = mapper.readTree(json)
                val found = mutableListOf<Device>()
                node.get("devices")?.forEach { d ->
                    val serial = d.get("serial")?.asText() ?: return@forEach
                    val state = d.get("state")?.asText() ?: "unknown"
                    val model = d.get("model")?.asText()
                    val product = d.get("product")?.asText()
                    if (state == "device") {
                        found.add(Device(serial, state, model, product))
                    }
                }
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
                val client = ensureDaemon()
                val json = client.apps(device.serial())
                val node = mapper.readTree(json)
                val found = mutableListOf<SidekickSocket>()
                node.get("apps")?.forEach { a ->
                    val pkg = a.get("package")?.asText() ?: return@forEach
                    val socket = a.get("socket")?.asText() ?: ""
                    found.add(SidekickSocket(socket, pkg))
                }
                apps = found
                notifyOnEdt { it.onAppsChanged(found) }

                if (selectedApp == null && found.isNotEmpty()) {
                    selectApp(found[0])
                }
            } catch (e: Exception) {
                log.warn("Failed to find apps", e)
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
    // Connection — just verify daemon can reach the app
    // ========================================================================

    private fun connect() {
        val app = selectedApp ?: return
        val device = selectedDevice ?: return
        updateConnectionStatus("Connecting...")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = ensureDaemon()
                // Trigger a layout tree fetch to verify the connection works
                // This also triggers ensureCdpWatcher in the daemon
                client.layoutTree(app.packageName(), device.serial(), null, null, null, null)

                // Guard against stale background threads: if the user switched
                // to a different app while this thread was blocked on the
                // daemon call (e.g. a 30s timeout for a dead sidekick), don't
                // overwrite the status that the newer connect() already set.
                if (selectedApp?.packageName() != app.packageName()) return@executeOnPooledThread

                connected = true
                updateConnectionStatus("Connected")
                startPolling()
            } catch (e: Exception) {
                if (selectedApp?.packageName() != app.packageName()) return@executeOnPooledThread
                log.warn("Failed to connect to ${app.packageName()}", e)
                connected = false
                updateConnectionStatus("Connection failed")
            }
        }
    }

    fun reconnect() {
        connected = false
        stopPolling()
        clearDataCaches()
        connect()
    }

    // ========================================================================
    // Background polling — fetches data from daemon via HTTP
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
        val client = daemon ?: return
        if (!connected) return

        fetchLayoutData(client, app.packageName(), device.serial())
        fetchNetworkData(client, app.packageName(), device.serial())
        fetchWebSocketData(client, app.packageName(), device.serial())
    }

    private fun fetchLayoutData(client: DaemonClient, pkg: String, device: String) {
        try {
            val tree = client.layoutTree(pkg, device, null, null, null, null)

            // When a Chrome Custom Tab is in the foreground, the sidekick's
            // screenshot shows the app behind Chrome (wrong). Detect the
            // ChromeCustomTab node in the tree and use the device-level
            // screenshot instead — it captures whatever's actually on screen.
            val hasCustomTab = tree.contains("ChromeCustomTab")
            val screenshot = try {
                if (hasCustomTab) client.deviceScreenshot(device)
                else client.screenshot(pkg, device)
            } catch (_: Exception) { null }

            if (tree != layoutTreeJson || screenshot != null) {
                layoutTreeJson = tree
                screenshotBytes = screenshot
                notifyOnEdt { it.onLayoutDataChanged(tree, screenshot) }
            }
        } catch (e: Exception) {
            log.debug("Layout fetch failed: ${e.message}")
        }
    }

    private fun fetchNetworkData(client: DaemonClient, pkg: String, device: String) {
        try {
            val json = client.networkRequests(pkg, device)
            if (json != networkRequestsJson) {
                networkRequestsJson = json
                notifyOnEdt { it.onNetworkDataChanged(json) }
            }
        } catch (e: Exception) {
            log.debug("Network fetch failed: ${e.message}")
        }
    }

    private fun fetchWebSocketData(client: DaemonClient, pkg: String, device: String) {
        try {
            val json = client.websocketConnections(pkg, device)
            if (json != webSocketConnectionsJson) {
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
    // Detail fetchers (called from UI panels on selection)
    // ========================================================================

    fun fetchNetworkRequestDetail(pkg: String, requestId: String, device: String?): String {
        val client = ensureDaemon()
        return client.networkRequest(pkg, requestId, device)
    }

    fun fetchWebSocketDetail(pkg: String, connectionId: String, device: String?): String {
        val client = ensureDaemon()
        return client.websocketConnection(pkg, connectionId, device)
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
        listeners.clear()
        embeddedDaemon?.stop()
        embeddedDaemon = null
    }
}
