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

    /** Latest /api/version JSON from the active daemon, or null when not connected. */
    @Volatile var daemonInfo: DaemonInfo? = null; private set
    private var daemonInfoFuture: java.util.concurrent.ScheduledFuture<*>? = null

    /** Snapshot of the sidekick on the currently-selected app, or null when not connected. */
    @Volatile var sidekickInfo: SidekickInfo? = null; private set

    /** Snapshot of /api/version. */
    data class DaemonInfo(
        val version: String,
        val pid: Long,
        val port: Int,
        val startedAt: Long,
        val baseUrl: String,
    )

    /**
     * Snapshot of the sidekick's self-reported version + the dta-tools
     * version baked into the AAR at build time. The latter is what was used
     * to assemble the APK; if it diverges from the running daemon you've got
     * a build/runtime mismatch.
     */
    data class SidekickInfo(
        val packageName: String,
        val deviceSerial: String,
        val sidekickVersion: String,
        val toolVersion: String,
    )

    // Data caches
    @Volatile var layoutTreeJson: String? = null; private set
    @Volatile var screenshotBytes: ByteArray? = null; private set
    @Volatile var networkRequestsJson: String? = null; private set
    @Volatile var webSocketConnectionsJson: String? = null; private set

    private val listeners = CopyOnWriteArrayList<DtaServiceListener>()

    private var adbPath: String? = null

    init {
        // Use Android Studio's SDK configuration to find ADB
        try {
            val adbFile = AdbFileProvider.fromApplication().get()
            if (adbFile != null) {
                adbPath = adbFile.absolutePath
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
        fun onDataStoreChanged(databasesJson: String?, prefsJson: String?) {}
        fun onRuntimeChanged(lifecycleJson: String?, memoryJson: String?, threadsJson: String?, navBackstackJson: String?, navGraphJson: String?) {}
        /** Called when device-side selections change (from MCP/CLI/inspector-web). */
        fun onDeviceSelectionsChanged(elements: String?, networkRequests: String?, wsMessages: String?) {}
        /** Called whenever the daemon-info snapshot changes (or null when disconnected). */
        fun onDaemonInfoChanged(info: DaemonInfo?) {}
        /** Called whenever the sidekick-info snapshot changes (or null when not connected to an app). */
        fun onSidekickInfoChanged(info: SidekickInfo?) {}
        /** Called whenever a fresh /api/runtime/viewmodels response is fetched. */
        fun onViewModelsChanged(json: String?) {}
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

        // Prefer an already-running compatible daemon (shared with dta-cli).
        // If none found, start an embedded daemon in-process. We intentionally
        // do NOT spawn an external dta-cli process from the plugin — an old
        // dta-cli on PATH might start the Spring Boot dta-server (wrong routes).
        val client = try {
            DaemonLauncher.connectIfCompatible() ?: startEmbeddedDaemon()
        } catch (e: Exception) {
            log.info("No compatible daemon found (${e.message}), starting embedded daemon")
            startEmbeddedDaemon()
        }
        daemon = client
        log.info("Connected to DTA daemon at ${client.baseUrl}")

        // Propagate ADB path to the daemon — critical when the daemon runs
        // in a separate process (external dta-cli daemon) that doesn't have
        // access to AS's SDK configuration. Without this, the daemon falls
        // back to bare "adb" on PATH which may not exist.
        if (adbPath != null) {
            try {
                client.setAdbPath(adbPath!!)
            } catch (e: Exception) {
                log.debug("Failed to set ADB path on daemon: ${e.message}")
            }
        }

        refreshDaemonInfo(client)
        startDaemonInfoPolling()
        return client
    }

    // ========================================================================
    // Daemon info (header panel)
    // ========================================================================

    /** Polls /api/version every 30s. Cheap call, lets the header tick uptime. */
    private fun startDaemonInfoPolling() {
        daemonInfoFuture?.cancel(false)
        daemonInfoFuture = scheduler.scheduleWithFixedDelay({
            val client = daemon ?: return@scheduleWithFixedDelay
            refreshDaemonInfo(client)
        }, 30, 30, TimeUnit.SECONDS)
    }

    private fun stopDaemonInfoPolling() {
        daemonInfoFuture?.cancel(false)
        daemonInfoFuture = null
    }

    private fun refreshDaemonInfo(client: DaemonClient) {
        val info = try {
            val json = client.version()
            val node = tools.jackson.databind.ObjectMapper().readTree(json)
            DaemonInfo(
                version = node.path("version").asText(""),
                pid = node.path("pid").asLong(),
                port = node.path("port").asInt(),
                startedAt = node.path("startedAt").asLong(),
                baseUrl = client.baseUrl,
            )
        } catch (e: Exception) {
            log.debug("refreshDaemonInfo failed: ${e.message}")
            null
        }
        if (info != daemonInfo) {
            daemonInfo = info
            notifyOnEdt { it.onDaemonInfoChanged(info) }
        }
    }

    /**
     * Asks the daemon to shut down, waits for it to be unreachable, then
     * re-runs ensureDaemon() to spawn a new one. Returns once the new daemon
     * is responding to /api/version, or after a hard timeout.
     *
     * <p>Safe to call from any thread; runs the work on a pooled thread.
     * If the daemon was an external process (started via dta-cli), we can
     * only ask it to stop politely — we don't have a Process handle. The
     * subsequent ensureDaemon() will start a fresh embedded one if the old
     * external daemon doesn't come back.</p>
     */
    fun restartDaemon() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val client = daemon ?: return@executeOnPooledThread
            val oldBase = client.baseUrl
            log.info("Restarting daemon at $oldBase")
            updateConnectionStatus("Restarting daemon...")
            stopPolling()
            stopDaemonInfoPolling()

            try { client.shutdownDaemon() } catch (_: Exception) { /* connection drops mid-response — expected */ }

            // Wait for the old daemon to actually be gone (max 5s).
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                try {
                    DaemonClient(oldBase).version()
                } catch (_: Exception) { break }
                Thread.sleep(150)
            }

            // Drop our cached client + embedded-daemon handle so ensureDaemon()
            // is forced to do a fresh discover/spawn.
            embeddedDaemon?.let { try { it.stop() } catch (_: Exception) {} }
            embeddedDaemon = null
            daemon = null
            daemonInfo = null
            notifyOnEdt { it.onDaemonInfoChanged(null) }

            try {
                ensureDaemon()
                if (selectedApp != null) connect() else updateConnectionStatus("Disconnected")
            } catch (e: Exception) {
                log.warn("Failed to start replacement daemon", e)
                updateConnectionStatus("Restart failed: ${e.message}")
            }
        }
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
                    val serial = d.get("serial")?.stringValue() ?: return@forEach
                    val state = d.get("state")?.stringValue() ?: "unknown"
                    val model = d.get("model")?.stringValue()
                    val product = d.get("product")?.stringValue()
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
                    val pkg = a.get("package")?.stringValue() ?: return@forEach
                    val socket = a.get("socket")?.stringValue() ?: ""
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
                // Fetch sidekick version for display + mismatch check. Cache
                // the result so the Daemon panel can render it without re-
                // querying.
                var statusText = "Connected"
                try {
                    val connJson = client.connectionStatus(app.packageName(), device.serial())
                    val node = tools.jackson.databind.ObjectMapper().readTree(connJson)
                    val skVersion = node.get("sidekickVersion")?.stringValue() ?: ""
                    val toolVersion = node.get("toolVersion")?.stringValue() ?: ""
                    val info = SidekickInfo(
                        packageName = app.packageName(),
                        deviceSerial = device.serial(),
                        sidekickVersion = skVersion,
                        toolVersion = toolVersion,
                    )
                    sidekickInfo = info
                    notifyOnEdt { it.onSidekickInfoChanged(info) }
                    if (skVersion.isNotEmpty()) {
                        statusText = "Connected (sidekick $skVersion)"
                        // Compare major.minor.patch only — strip SNAPSHOT build
                        // numbers since Maven uses mutable X.Y.Z-SNAPSHOT while
                        // plugin/CLI use unique X.Y.Z-SNAPSHOT.N
                        val skBase = skVersion.replace(Regex("-SNAPSHOT\\.\\d+$"), "-SNAPSHOT")
                            .replace("-SNAPSHOT", "").trimEnd('.')
                        val toolBase = toolVersion.replace(Regex("-SNAPSHOT\\.\\d+$"), "-SNAPSHOT")
                            .replace("-SNAPSHOT", "").trimEnd('.')
                        if (toolBase.isNotEmpty() && toolBase != "standalone"
                            && skBase.isNotEmpty() && skBase != toolBase) {
                            statusText += " ⚠ version mismatch (tools $toolVersion) — rebuild the app"
                        }
                    }
                } catch (_: Exception) {}
                updateConnectionStatus(statusText)
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
        fetchDataStoreData(client, app.packageName(), device.serial())
        fetchRuntimeData(client, app.packageName(), device.serial())
        fetchDeviceSelections(client, app.packageName(), device.serial())
    }

    private var lastSelectionsJson: String? = null

    private fun fetchDeviceSelections(client: DaemonClient, pkg: String, device: String) {
        try {
            val elements = try { client.getSelectedElements(pkg, device) } catch (_: Exception) { null }
            val network = try { client.getSelectedNetworkRequests(pkg, device) } catch (_: Exception) { null }
            val ws = try { client.getSelectedWebSocketMessages(pkg, device) } catch (_: Exception) { null }
            val combined = "$elements|$network|$ws"
            if (combined != lastSelectionsJson) {
                lastSelectionsJson = combined
                notifyOnEdt { it.onDeviceSelectionsChanged(elements, network, ws) }
            }
        } catch (_: Exception) {}
    }

    private fun fetchLayoutData(client: DaemonClient, pkg: String, device: String) {
        try {
            val tree = client.layoutTree(pkg, device, null, null, null, null)

            // When Chrome (Custom Tab or standalone Intent.ACTION_VIEW) is in
            // the foreground, sidekick's screenshot shows the host app behind
            // Chrome (wrong). Detect the synthetic Chrome node and use the
            // device-level screenshot instead — it captures whatever's
            // actually on screen.
            val chromeForeground = tree.contains("ChromeCustomTab") || tree.contains("ChromeBrowser")
            val screenshot = try {
                if (chromeForeground) client.deviceScreenshot(device)
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

    private var databasesJson: String? = null
    private var prefsJson: String? = null

    private fun fetchDataStoreData(client: DaemonClient, pkg: String, device: String) {
        try {
            val dbs = client.listDatabases(pkg, device)
            val prefs = client.listSharedPrefs(pkg, device)
            if (dbs != databasesJson || prefs != prefsJson) {
                databasesJson = dbs
                prefsJson = prefs
                notifyOnEdt { it.onDataStoreChanged(dbs, prefs) }
            }
        } catch (e: Exception) {
            log.debug("Data store fetch failed: ${e.message}")
        }
    }

    private fun fetchRuntimeData(client: DaemonClient, pkg: String, device: String) {
        try {
            val lifecycle = try { client.lifecycle(pkg, device) } catch (_: Exception) { null }
            val memory = try { client.memory(pkg, device) } catch (_: Exception) { null }
            val threads = try { client.threads(pkg, device, false) } catch (_: Exception) { null }
            val backstack = try { client.navigationBackstack(pkg, device) } catch (_: Exception) { null }
            val graph = try { client.navigationGraph(pkg, device) } catch (_: Exception) { null }
            val viewModels = try { client.viewModels(pkg, device) } catch (_: Exception) { null }
            notifyOnEdt {
                it.onRuntimeChanged(lifecycle, memory, threads, backstack, graph)
                it.onViewModelsChanged(viewModels)
            }
        } catch (e: Exception) {
            log.debug("Runtime fetch failed: ${e.message}")
        }
    }

    /**
     * Pulls SavedStateHandle contents for a specific ViewModel. Synchronous —
     * used by the ViewModels sub-panel when the user expands a row.
     */
    fun fetchSavedState(pkg: String, viewModelId: String, device: String): String {
        val client = ensureDaemon()
        return client.viewModelSavedState(pkg, viewModelId, device)
    }

    private fun clearDataCaches() {
        layoutTreeJson = null
        screenshotBytes = null
        networkRequestsJson = null
        webSocketConnectionsJson = null
        sidekickInfo = null
        notifyOnEdt {
            it.onLayoutDataChanged(null, null)
            it.onNetworkDataChanged(null)
            it.onWebSocketDataChanged(null)
            it.onSidekickInfoChanged(null)
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

    fun clearNetworkRequests(pkg: String, device: String?) {
        val client = ensureDaemon()
        client.clearNetworkRequests(pkg, device)
        networkRequestsJson = null
    }

    fun fetchDatabaseSchema(pkg: String, dbName: String, device: String?): String {
        val client = ensureDaemon()
        return client.databaseSchema(pkg, dbName, device ?: "")
    }

    fun fetchDatabaseQuery(pkg: String, dbName: String, body: String, device: String?): String {
        val client = ensureDaemon()
        return client.databaseQuery(pkg, dbName, body, device ?: "")
    }

    fun fetchSharedPrefs(pkg: String, prefsName: String, device: String?): String {
        val client = ensureDaemon()
        return client.readSharedPrefs(pkg, prefsName, device ?: "")
    }

    fun clearWebSocketConnections(pkg: String, device: String?) {
        val client = ensureDaemon()
        client.clearWebsocketConnections(pkg, device)
        webSocketConnectionsJson = null
    }

    // ========================================================================
    // Selection sync (bidirectional with daemon)
    // ========================================================================

    // Add to selection (multi-select)
    fun addElementSelection(pkg: String, device: String?, json: String) {
        try { ensureDaemon().addSelectedElement(pkg, device, json) } catch (_: Exception) {}
    }
    fun removeElementSelection(pkg: String, device: String?, json: String) {
        try { ensureDaemon().removeSelectedElement(pkg, device, json) } catch (_: Exception) {}
    }
    fun clearElementSelection(pkg: String, device: String?) {
        try { ensureDaemon().clearSelectedElements(pkg, device) } catch (_: Exception) {}
    }

    fun addNetworkSelection(pkg: String, device: String?, json: String) {
        try { ensureDaemon().addSelectedNetworkRequest(pkg, device, json) } catch (_: Exception) {}
    }
    fun removeNetworkSelection(pkg: String, device: String?, json: String) {
        try { ensureDaemon().removeSelectedNetworkRequest(pkg, device, json) } catch (_: Exception) {}
    }
    fun clearNetworkSelection(pkg: String, device: String?) {
        try { ensureDaemon().clearSelectedNetworkRequests(pkg, device) } catch (_: Exception) {}
    }

    fun addWebSocketSelection(pkg: String, device: String?, json: String) {
        try { ensureDaemon().addSelectedWebSocketMessage(pkg, device, json) } catch (_: Exception) {}
    }
    fun removeWebSocketSelection(pkg: String, device: String?, json: String) {
        try { ensureDaemon().removeSelectedWebSocketMessage(pkg, device, json) } catch (_: Exception) {}
    }
    fun clearWebSocketSelection(pkg: String, device: String?) {
        try { ensureDaemon().clearSelectedWebSocketMessages(pkg, device) } catch (_: Exception) {}
    }

    // Old sync methods removed — use add/remove/clear granular methods above

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
