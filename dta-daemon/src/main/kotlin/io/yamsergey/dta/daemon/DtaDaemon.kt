package io.yamsergey.dta.daemon

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.yamsergey.dta.daemon.adb.AdbExec
import io.yamsergey.dta.daemon.adb.PortForwards
import io.yamsergey.dta.daemon.connection.ForwardOps
import io.yamsergey.dta.daemon.connection.SidekickConnection
import io.yamsergey.dta.daemon.connection.SidekickConnectionManager
import io.yamsergey.dta.daemon.discovery.ConnectionKey
import io.yamsergey.dta.daemon.discovery.Device
import io.yamsergey.dta.protocol.ops.AdbInfo
import io.yamsergey.dta.protocol.ops.AppInfo
import io.yamsergey.dta.protocol.ops.AppList
import io.yamsergey.dta.protocol.ops.DeviceInfo
import io.yamsergey.dta.protocol.rpc.IdentityReport
import io.yamsergey.dta.daemon.discovery.SidekickDiscovery
import io.yamsergey.dta.daemon.layout.ForegroundDetector
import io.yamsergey.dta.daemon.layout.LayoutService
import io.yamsergey.dta.daemon.layout.UiAutomatorFallback
import io.yamsergey.dta.daemon.network.BodyOutput
import io.yamsergey.dta.daemon.network.BodyResult
import io.yamsergey.dta.daemon.network.NetworkScriptService
import io.yamsergey.dta.daemon.network.NetworkService
import io.yamsergey.dta.daemon.network.proto.BodyDecoding
import io.yamsergey.dta.daemon.network.proto.BodyMapping
import io.yamsergey.dta.daemon.network.proto.BodyMappingSpec
import io.yamsergey.dta.daemon.network.proto.SchemaInput
import io.yamsergey.dta.daemon.network.proto.SchemaRegistry
import io.yamsergey.dta.daemon.network.proto.SchemaSet
import io.yamsergey.dta.protocol.network.ScriptLogPage
import io.yamsergey.dta.protocol.network.ScriptStatus
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.layout.DetailLevel
import io.yamsergey.dta.protocol.layout.LayoutCapture
import io.yamsergey.dta.protocol.layout.LayoutFilter
import io.yamsergey.dta.protocol.layout.LayoutTreeParams
import io.yamsergey.dta.protocol.layout.Screen
import io.yamsergey.dta.protocol.network.ClearResult
import io.yamsergey.dta.protocol.network.NetworkEntryDetail
import io.yamsergey.dta.protocol.network.NetworkEntryEvent
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.NetworkQuery
import kotlinx.coroutines.flow.Flow
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Host-side entry point of the channel feature. In this feature the daemon
 * ships as a library (no server process yet — see plan.md); this class is the
 * production wiring of discovery + forwards + the connection manager.
 */
class DtaDaemon(
    private val runAdb: (List<String>) -> AdbExec.Result,
    /** Resolved adb binary, or null with [adbUnavailableReason] (feature 008: the daemon runs without adb). */
    private val adbPath: String?,
    requestTimeout: Duration = 10.seconds,
    /** Daemon state on the developer's machine: protobuf schemas and mappings (feature 006). */
    dataDir: java.io.File = defaultHome(),
    private val adbUnavailableReason: String? = null,
) {
    constructor(
        adb: AdbExec = AdbExec(),
        requestTimeout: Duration = 10.seconds,
        dataDir: java.io.File = defaultHome(),
    ) : this({ args -> adb.run(*args.toTypedArray()) }, adb.binaryPath, requestTimeout, dataDir)

    private val schemas = SchemaRegistry(dataDir)
    private val injectedTooling = io.yamsergey.dta.daemon.inject.InjectedTooling(dataDir)

    /** The host's network store (feature 011): opened on first use, one per daemon. */
    private val hostStore by lazy { io.yamsergey.dta.daemon.store.HostStore(java.io.File(dataDir, "store")) }
    private val deviceIdentities = io.yamsergey.dta.daemon.device.DeviceIdentities { serial -> runAdb(listOf("-s", serial, "shell", "getprop")) }
    private val hostIndexes = java.util.concurrent.ConcurrentHashMap<ConnectionKey, io.yamsergey.dta.daemon.network.HostIndex>()

    /** Background work of the host store: copying bodies as soon as entries finish (011 US7). */
    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private fun hostIndex(serial: String, pkg: String): io.yamsergey.dta.daemon.network.HostIndex =
        hostIndexes.computeIfAbsent(ConnectionKey(serial, pkg)) { key ->
            io.yamsergey.dta.daemon.network.HostIndex(hostStore, deviceIdentities.of(serial), pkg, decoding = { BodyDecoding(schemas.rules(pkg)) }) {
                connections.connect(key)
            }.also { startCopying(key, it) }
        }

    /**
     * Catches up and copies bodies while the app is connected. It never opens a connection
     * itself: attempts on an app that is gone would leave a failure behind that a query right
     * after the app restarts would get.
     */
    private fun startCopying(key: ConnectionKey, index: io.yamsergey.dta.daemon.network.HostIndex) {
        val worker = io.yamsergey.dta.daemon.network.CopyWorker(index)
        background.launch {
            while (isActive) {
                if (key !in connections.openKeys()) {
                    delay(1_000)
                    continue
                }
                val copied = try {
                    index.refresh()
                    worker.runOnce()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    delay(5_000)
                    continue
                }
                if (copied == 0L) delay(1_000)
            }
        }
    }
    private val forwards = PortForwards(runAdb)

    init {
        // FR-011: forwards leaked by a previous run are cleaned at startup.
        // Never fatal — no adb/no devices just means nothing to sweep.
        if (adbPath != null) runCatching { forwards.startupSweep() }
    }

    /** Re-runs the leaked-forward cleanup; returns how many were removed. */
    fun startupSweep(): Int = forwards.startupSweep()

    val discovery = SidekickDiscovery(runAdb)

    val connections = SidekickConnectionManager(
        forwardOps = ForwardOps(
            forward = { forwards.forward(it) },
            remove = { serial, port -> forwards.remove(serial, port) },
        ),
        connector = { key, port ->
            SidekickConnection.connect(
                uri = URI("ws://127.0.0.1:$port/"),
                expectedPackage = key.packageName,
                requestTimeout = requestTimeout,
            )
        },
        probe = { connection ->
            runCatching { connection.request("ping", null, 2.seconds) }.isSuccess
        },
    )

    /** Keeps watched apps connected and says when that changes (feature 009, FR-017). */
    private val watcher = io.yamsergey.dta.daemon.connection.AppConnectionWatcher(
        instances = { serial -> discovery.listInstances().filter { serial == null || it.serial == serial }.map { ConnectionKey(it.serial, it.packageName) } },
        manager = connections,
    )

    /** App connection state changes on [serial] (every device when null), for as long as collected. */
    fun appEvents(serial: String?): Flow<io.yamsergey.dta.protocol.ops.AppConnectionEvent> = watcher.events(serial)

    private val foregroundDetector = ForegroundDetector(runAdb)
    private val fallback = UiAutomatorFallback(runAdb)

    /**
     * Capture the layout tree for [pkg] on [serial], applying the sidekick-vs-
     * fallback decision ladder with honest labeling (contract §4). [pkg] null
     * means "whatever is foreground" (fallback only).
     */
    suspend fun layout(
        serial: String,
        pkg: String?,
        filters: LayoutFilter? = null,
        detail: DetailLevel = DetailLevel.SEMANTIC,
        frameworkFrames: Boolean = false,
    ): LayoutCapture {
        val service = LayoutService(
            foregroundPackage = { foregroundDetector.foregroundPackage(it) },
            sidekickCapture = pkg?.let { p ->
                { params ->
                    val conn = connections.connect(ConnectionKey(serial, p))
                    val result = conn.request("layout_tree", params.toJson())
                    DtaJson.decodeFromJsonElement(LayoutCapture.serializer(), result)
                }
            },
            uiautomatorCapture = { s ->
                val screen = screenOf(s)
                fallback.capture(s, screen.widthPx, screen.heightPx)
            },
            screenOf = { screenOf(it) },
        )
        return service.capture(serial, pkg, LayoutTreeParams(filters, detail, frameworkFrames))
    }

    /** Network history of [pkg]: HTTP and WebSocket entries in one list (feature 004). */
    suspend fun network(serial: String, pkg: String, query: NetworkQuery = NetworkQuery()): NetworkPage =
        networkService(serial, pkg).list(query)

    suspend fun networkEntry(serial: String, pkg: String, id: String, redact: Boolean = false): NetworkEntryDetail =
        networkService(serial, pkg).entry(id, redact)

    /**
     * One body: decoded text, or bytes to a file. Protobuf bodies a rule selects (built-in
     * OTLP, user mappings) come back as JSON with [BodyResult.decoding]; other binary bodies
     * need [BodyOutput.File].
     * With [redact], bodies that cannot be redacted are withheld unless [allowUnredacted].
     */
    suspend fun networkBody(
        serial: String,
        pkg: String,
        id: String,
        part: BodyPart,
        output: BodyOutput = BodyOutput.Text,
        redact: Boolean = false,
        allowUnredacted: Boolean = false,
    ): BodyResult = networkService(serial, pkg).body(id, part, output, redact, allowUnredacted)

    suspend fun networkClear(serial: String, pkg: String): ClearResult =
        networkService(serial, pkg).clear()

    /** Live entry events; collect for as long as needed. */
    fun networkEvents(serial: String, pkg: String): Flow<NetworkEntryEvent> =
        networkService(serial, pkg).events()

    /**
     * Adds (or replaces) a protobuf schema set for [pkg]: a compiled descriptor set or
     * `.proto` sources (no protoc needed). Throws [io.yamsergey.dta.daemon.network.proto.SchemaException]
     * naming the problem. Schemas and mappings persist under the daemon's data directory.
     */
    fun networkSchemaAdd(pkg: String, name: String, input: SchemaInput): SchemaSet = schemas.addSchema(pkg, name, input)

    fun networkSchemas(pkg: String): List<SchemaSet> = schemas.schemas(pkg)

    /** Removes a schema set; returns the mappings that used it, now broken. */
    fun networkSchemaRemove(pkg: String, name: String): List<BodyMapping> = schemas.removeSchema(pkg, name)

    /** Replaces one origin's schema set and derived mappings (feature 013). */
    fun networkSchemaSync(
        pkg: String,
        origin: io.yamsergey.dta.daemon.network.proto.SchemaOrigin,
        files: Map<String, String>,
        mappings: List<io.yamsergey.dta.daemon.network.proto.DerivedMapping>,
        notes: List<String> = emptyList(),
    ): io.yamsergey.dta.daemon.network.proto.SyncResult = schemas.sync(pkg, origin, files, mappings, notes)

    /** Maps a message type to bodies: URL glob, part, and optionally method (HTTP) or direction (WebSocket). */
    fun networkMappingAdd(pkg: String, mapping: BodyMappingSpec): BodyMapping = schemas.addMapping(pkg, mapping)

    /** User mappings (with `broken` when unusable), then the built-in OTLP rules. */
    fun networkMappings(pkg: String): List<BodyMapping> = schemas.mappings(pkg)

    fun networkMappingRemove(pkg: String, id: String): Boolean = schemas.removeMapping(pkg, id)

    /** Installs (replaces) the app's network script; it persists until [networkScriptClear]. */
    suspend fun networkScriptSet(serial: String, pkg: String, source: String): ScriptStatus =
        scriptService(serial, pkg).set(source)

    suspend fun networkScriptClear(serial: String, pkg: String): Boolean = scriptService(serial, pkg).clear()

    suspend fun networkScriptStatus(serial: String, pkg: String): ScriptStatus = scriptService(serial, pkg).status()

    suspend fun networkScriptLogs(serial: String, pkg: String, since: Long = 0): ScriptLogPage =
        scriptService(serial, pkg).logs(since)

    private fun scriptService(serial: String, pkg: String) =
        NetworkScriptService { connections.connect(ConnectionKey(serial, pkg)) }

    private fun networkService(serial: String, pkg: String) =
        NetworkService(BodyDecoding(schemas.rules(pkg)), hostIndex(serial, pkg)) { connections.connect(ConnectionKey(serial, pkg)) }

    private fun screenOf(serial: String): Screen {
        // wm size / density via adb, best-effort.
        val size = runAdb(listOf("-s", serial, "shell", "wm", "size")).stdout
        val (w, h) = Regex("""(\d+)x(\d+)""").find(size)?.destructured
            ?.let { (a, b) -> a.toInt() to b.toInt() } ?: (0 to 0)
        val density = runAdb(listOf("-s", serial, "shell", "wm", "density")).stdout
            .let { Regex("""(\d+)""").find(it)?.value?.toFloat()?.div(160f) ?: 1f }
        return Screen(w, h, density)
    }

    /**
     * Opens [uri] on the device (feature 012 US2): who received it, and — when the app with DTA
     * did — the link's record, awaited for a few seconds; for an http(s) link the app did not
     * get, its host's App Links state.
     */
    suspend fun linksOpen(serial: String, uri: String, pkg: String?, only: Boolean = false): io.yamsergey.dta.protocol.network.LinkOpenResult {
        val quoted = io.yamsergey.dta.daemon.links.LinkParsers.shellQuote(uri)
        fun linkIds(app: String): Set<String> = runCatching {
            kotlinx.coroutines.runBlocking { linkQuery(serial, app, uri) }
        }.getOrDefault(emptySet())
        val before = pkg?.let(::linkIds).orEmpty()
        val command = "am start -W -a android.intent.action.VIEW -d $quoted" + (pkg?.takeIf { only }?.let { " -p $it" } ?: "") + " --user current"
        val output = runAdb(listOf("-s", serial, "shell", command)).let { it.stdout + it.stderr }
        val result = io.yamsergey.dta.daemon.links.LinkParsers.launch(output, pkg)
        if (result.outcome == io.yamsergey.dta.protocol.network.LinkOpenOutcome.RECEIVED) {
            val app = result.packageName ?: pkg ?: return result
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val now = runCatching { linkQuery(serial, app, uri) }.getOrDefault(emptySet())
                (now - before).firstOrNull()?.let { return result.copy(recordId = it) }
                kotlinx.coroutines.delay(300)
            }
            val why = runCatching { capabilityReport(serial, app).capabilities.firstOrNull { it.name == "links" }?.reason }.getOrNull()
            return result.copy(reason = listOfNotNull(result.reason, "received, but DTA has no record of it" + (why?.let { ": $it" } ?: " (no record arrived within 5 s)")).joinToString("; "))
        }
        val host = runCatching { java.net.URI(uri) }.getOrNull()?.takeIf { it.scheme == "http" || it.scheme == "https" }?.host
        if (host != null && pkg != null) {
            val state = runCatching { linksVerification(serial, pkg) }.getOrNull()
            return result.copy(appLinks = state?.hosts?.firstOrNull { it.host.equals(host, ignoreCase = true) } ?: io.yamsergey.dta.protocol.network.AppLinkHost(host, "not declared", "$pkg declares no App Links for $host"))
        }
        return result
    }

    private suspend fun linkQuery(serial: String, pkg: String, uri: String): Set<String> =
        network(serial, pkg, NetworkQuery(filter = io.yamsergey.dta.protocol.network.NetworkFilter(kinds = listOf(io.yamsergey.dta.protocol.network.EntryKind.LINK), url = uri), limit = 50))
            .entries.map { it.id }.toSet()

    /** `pm get-app-links` for [pkg] (feature 012 US3); unavailable before Android 12. */
    fun linksVerification(serial: String, pkg: String): io.yamsergey.dta.protocol.network.AppLinksState {
        val sdk = runAdb(listOf("-s", serial, "shell", "getprop", "ro.build.version.sdk")).stdout.trim().toIntOrNull() ?: 0
        if (sdk < 31) return io.yamsergey.dta.daemon.links.LinkParsers.appLinks("", sdk)
        val output = runAdb(listOf("-s", serial, "shell", "pm", "get-app-links", "--user", "current", pkg)).stdout
        return io.yamsergey.dta.daemon.links.LinkParsers.appLinks(output, sdk)
    }

    /** The host's network archive (feature 011, contracts/archive-ops.md). */
    fun archiveList(): io.yamsergey.dta.protocol.network.ArchiveListing = hostStore.listing()

    /** Deletes archived history; without [confirm] says what would go. The live history is refused. */
    fun archiveDelete(target: io.yamsergey.dta.daemon.store.DeleteTarget, confirm: Boolean): io.yamsergey.dta.protocol.network.ArchiveDeletion =
        io.yamsergey.dta.protocol.network.ArchiveDeletion(confirm, hostStore.delete(target, dryRun = !confirm))

    suspend fun shutdown() {
        background.cancel()
        connections.shutdown()
    }

    // --- Feature 008: what the daemon process serves besides the per-app operations.

    fun adbInfo(): AdbInfo = AdbInfo(path = adbPath, available = adbPath != null, reason = adbUnavailableReason)

    /** Connected devices with their state; the model for online ones. */
    fun devices(): List<DeviceInfo> = discovery.listDevices().map { device ->
        val online = device.state == Device.State.ONLINE
        DeviceInfo(
            serial = device.serial,
            state = device.state.name.lowercase(),
            model = if (online) {
                runAdb(listOf("-s", device.serial, "shell", "getprop", "ro.product.model")).stdout.trim().ifEmpty { null }
            } else {
                null
            },
        )
    }

    /** Apps on [serial] with a running sidekick, and the foreground app. */
    fun apps(serial: String): AppList {
        val foreground = foregroundDetector.foregroundPackage(serial)
        val withDta = discovery.listInstances().filter { it.serial == serial }.map { it.packageName }.distinct()
        return AppList(serial, foreground, withDta.map { AppInfo(it, it == foreground) })
    }

    fun foregroundPackage(serial: String): String? = foregroundDetector.foregroundPackage(serial)

    /** What the app's sidekick reports about its capabilities and libraries. */
    suspend fun capabilityReport(serial: String, pkg: String): IdentityReport = injectedTooling.annotate(
        DtaJson.decodeFromJsonElement(
            IdentityReport.serializer(),
            connections.connect(ConnectionKey(serial, pkg)).request("identity"),
        ),
    )

    /** Open app connections (for `daemon.status`). */
    fun openConnections(): List<ConnectionKey> = connections.openKeys()

    companion object {
        /** A daemon whose device operations fail with [reason] (adb not found); everything else works. */
        fun withoutAdb(reason: String, dataDir: java.io.File): DtaDaemon =
            DtaDaemon({ throw AdbUnavailableException(reason) }, null, dataDir = dataDir, adbUnavailableReason = reason)
    }
}

/** adb could not be found or run; the message says where DTA looked and how to fix it. */
class AdbUnavailableException(message: String) : Exception(message)

/** `$DTA_HOME`, else `-Ddta.home`, else `~/.dta` — as the clients resolve it. */
fun defaultHome(env: Map<String, String> = System.getenv(), property: String? = System.getProperty("dta.home")): java.io.File =
    java.io.File(env["DTA_HOME"]?.takeIf { it.isNotBlank() } ?: property?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.dta"))
