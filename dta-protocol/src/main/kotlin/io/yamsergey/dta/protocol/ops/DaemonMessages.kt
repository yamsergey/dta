package io.yamsergey.dta.protocol.ops

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Which DTA build a client or the daemon is (research R4). */
@Serializable
data class VersionIdentity(
    val version: String,
    /** UTC build time, ISO-8601; orders two builds of one version. */
    val build: String,
    val protocolVersion: Int,
    /** D-096: sha256 of the build's sources (dta-version.properties `content`); empty when unknown (a dev run). */
    val content: String = "",
) {
    enum class Comparison { SAME, CLIENT_NEWER, CLIENT_OLDER }

    val display: String get() = "$version (build $build)"

    companion object {
        /**
         * One known content is one build (D-096: its build time follows file times, so a touch-and-revert gives
         * another); otherwise protocol, then semantic version (a release outranks its snapshots; snapshots by number),
         * then build.
         */
        fun compare(client: VersionIdentity, daemon: VersionIdentity): Comparison {
            if (client.content.isNotEmpty() && client.content == daemon.content) return Comparison.SAME
            val order = compareValuesBy(client, daemon, { it.protocolVersion }).takeIf { it != 0 }
                ?: compareSemver(client.version, daemon.version).takeIf { it != 0 }
                ?: client.build.compareTo(daemon.build)
            return when {
                order > 0 -> Comparison.CLIENT_NEWER
                order < 0 -> Comparison.CLIENT_OLDER
                else -> Comparison.SAME
            }
        }

        private fun compareSemver(a: String, b: String): Int {
            val (coreA, preA) = split(a)
            val (coreB, preB) = split(b)
            for (i in 0 until maxOf(coreA.size, coreB.size)) {
                val c = (coreA.getOrElse(i) { 0 }).compareTo(coreB.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return when {
                preA == null && preB == null -> 0
                preA == null -> 1
                preB == null -> -1
                else -> comparePre(preA, preB)
            }
        }

        private fun split(version: String): Pair<List<Int>, String?> {
            val core = version.substringBefore('-')
            val pre = version.substringAfter('-', "").ifEmpty { null }
            return core.split('.').map { it.toIntOrNull() ?: 0 } to pre
        }

        private fun comparePre(a: String, b: String): Int {
            val na = a.substringAfterLast('.').toIntOrNull()
            val nb = b.substringAfterLast('.').toIntOrNull()
            if (na != null && nb != null && a.substringBeforeLast('.') == b.substringBeforeLast('.')) return na.compareTo(nb)
            return a.compareTo(b)
        }
    }
}

@Serializable
enum class ClientKind {
    @SerialName("cli") CLI,
    @SerialName("mcp") MCP,
    @SerialName("ide") IDE,
    /** The agent server over HTTP hosted by the Android Studio plugin (009 US4). */
    @SerialName("mcp-http") MCP_HTTP,
}

/** First request of every client connection (contract daemon-protocol §Connect). */
@Serializable
data class DaemonHello(
    val client: ClientKind,
    val identity: VersionIdentity,
    /** Feature 073: what the client calls itself ("dta CLI" for the CLI); null when it does not say. */
    val name: String? = null,
)

@Serializable
data class DaemonHelloResult(val identity: VersionIdentity, val pid: Long)

/** Sent by a newer client to make the daemon step aside. */
@Serializable
data class ReplaceParams(val by: VersionIdentity)

@Serializable
data class ClientInfo(
    val kind: ClientKind,
    /** The DTA build of the client's connection. */
    val version: String,
    val connectedAtMs: Long,
    /** Feature 073: the agent's name (MCP clientInfo, "dta CLI"); null when the client did not say. */
    val name: String? = null,
    /** Feature 073: the agent's own version (an MCP session's clientInfo); null for the CLI and unnamed clients. */
    val agentVersion: String? = null,
    /** Feature 073: for an agent served by an IDE's server, which server (its url); null otherwise. */
    val server: String? = null,
)

/** Feature 073: one agent session of the plugin's HTTP server, as `client.sessions` reports it. */
@Serializable
data class ClientSession(val name: String, val version: String? = null, val since: Long)

/** Feature 073: `client.sessions` params: every live session of the reporting server (a report replaces the last). */
@Serializable
data class ClientSessions(val sessions: List<ClientSession>, val server: String? = null)

/** Feature 073: `client.name` params: the stdio MCP client's name, from its `initialize`. */
@Serializable
data class ClientName(val name: String, val version: String? = null)

@Serializable
data class AdbInfo(val path: String? = null, val available: Boolean, val reason: String? = null)

@Serializable
data class DeviceInfo(val serial: String, val state: String, val model: String? = null)

@Serializable
data class AppInfo(
    val packageName: String,
    val foreground: Boolean,
    /** D-069: why the daemon's last connect to the app failed (none since it connected, or never tried). */
    val connectError: String? = null,
)

@Serializable
data class AppList(val device: String, val foreground: String? = null, val apps: List<AppInfo>)

@Serializable
data class ConnectionInfo(val device: String, val app: String, val state: String)

@Serializable
data class DaemonStatus(
    val identity: VersionIdentity,
    val pid: Long,
    val uptimeMs: Long,
    val home: String,
    val adb: AdbInfo,
    val clients: List<ClientInfo>,
    val devices: List<DeviceInfo>,
    val connections: List<ConnectionInfo>,
    /** Feature 071: why the archive was made anew when this daemon opened it (its format's rule); null when it was not, or not opened yet. */
    val archiveStartedOver: String? = null,
)

/** Events the daemon pushes to its clients. */
object DaemonEvents {
    const val NETWORK_ENTRY = "network.entry"
    /** An app's captured history was cleared, by any client: data `subscription`, `device`, `app`, `cleared` (ClearResult), `by` (client kind). */
    const val NETWORK_CLEARED = "network.cleared"
    const val APP_CONNECTION = "app.connection"
    /** One timeline event (features 015/016): data `subscription`, `device`, `app`, `event` (TimelineEvent). */
    const val TIMELINE_EVENT = "timeline.event"
    /** Feature 057: the app's screen changed: data `subscription`, `device`, `app`, `at`, `reasons`. */
    const val LAYOUT_CHANGED = "layout.changed"
    /** Feature 057: the screen signal's state: data `subscription`, `device`, `app`, `state`, `reason`. */
    const val LAYOUT_SIGNAL = "layout.signal"
    /** Feature 055: data `subscription`, `device`, `app`, `selection` (LayoutSelection), `by`. */
    const val LAYOUT_SELECTION = "layout.selection"
    /** Feature 071: a streamed search's rows and state (FoundEvent). */
    const val NETWORK_FOUND = "network.found"
    /** Feature 073: the agent clients changed: data `subscription`, `clients` (list of ClientInfo). */
    const val CLIENTS = "daemon.clients"
    /** The agents' redaction setting changed (user decision 2026-11-27, D-121): data `subscription`, `redaction` (true: masked). To IDE clients that watch it. */
    const val AGENTS_REDACTION = "agents.redaction.changed"
    const val REPLACED = "daemon.replaced"
    const val STOPPING = "daemon.stopping"
}

@Serializable
data class AppConnectionEvent(val device: String, val app: String, val state: String, val reason: String? = null)

@Serializable
data class DaemonReplacedEvent(val by: VersionIdentity)

@Serializable
data class DaemonStoppingEvent(val reason: String)

/** Lifecycle methods (not user operations). */
object DaemonMethods {
    const val HELLO = "daemon.hello"
    const val REPLACE = "daemon.replace"
    const val NETWORK_SUBSCRIBE = "network.subscribe"
    const val NETWORK_UNSUBSCRIBE = "network.unsubscribe"
    const val APPS_SUBSCRIBE = "apps.subscribe"
    const val APPS_UNSUBSCRIBE = "apps.unsubscribe"
    const val TIMELINE_SUBSCRIBE = "timeline.subscribe"
    const val TIMELINE_UNSUBSCRIBE = "timeline.unsubscribe"
    const val LAYOUT_SUBSCRIBE = "layout.subscribe"
    const val LAYOUT_UNSUBSCRIBE = "layout.unsubscribe"
    /** Feature 055: the app's shared layout selection, now and at every change. */
    const val SELECTION_SUBSCRIBE = "selection.subscribe"
    const val SELECTION_UNSUBSCRIBE = "selection.unsubscribe"
    /** Feature 071 (contracts/find.md): a streamed search; `network.found` events until a final state. */
    const val NETWORK_FIND = "network.find"
    /** Feature 071: more rows for a stream (`stream`, `n`). */
    const val NETWORK_FIND_MORE = "network.find.more"
    /** Feature 071: stops a stream (`stream`); its last event says stopped. */
    const val NETWORK_FIND_STOP = "network.find.stop"
    /** Feature 073: the connected clients now and at every change (`daemon.clients` events). */
    const val CLIENTS_SUBSCRIBE = "daemon.clients"
    const val CLIENTS_UNSUBSCRIBE = "daemon.clients.unsubscribe"
    /** The agents' redaction setting now and at every change (`agents.redaction.changed` events); the plugin only (an IDE client). */
    const val AGENTS_REDACTION_SUBSCRIBE = "agents.redaction.watch"
    const val AGENTS_REDACTION_UNSUBSCRIBE = "agents.redaction.unwatch"
    /** Feature 073: a stdio MCP client says its agent's name ({name, version?}). */
    const val CLIENT_NAME = "client.name"
    /** Feature 073: the plugin's HTTP server reports its agent sessions ({sessions: [{name, version, since}], server}). */
    const val CLIENT_SESSIONS = "client.sessions"
    /** Feature 071: cancels a running call of this client (`id`); it answers CANCELLED. */
    const val REQUEST_CANCEL = "request.cancel"
}
