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
) {
    enum class Comparison { SAME, CLIENT_NEWER, CLIENT_OLDER }

    val display: String get() = "$version (build $build)"

    companion object {
        /** Protocol, then semantic version (a release outranks its snapshots; snapshots by number), then build. */
        fun compare(client: VersionIdentity, daemon: VersionIdentity): Comparison {
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
data class DaemonHello(val client: ClientKind, val identity: VersionIdentity)

@Serializable
data class DaemonHelloResult(val identity: VersionIdentity, val pid: Long)

/** Sent by a newer client to make the daemon step aside. */
@Serializable
data class ReplaceParams(val by: VersionIdentity)

@Serializable
data class ClientInfo(val kind: ClientKind, val version: String, val connectedAtMs: Long)

@Serializable
data class AdbInfo(val path: String? = null, val available: Boolean, val reason: String? = null)

@Serializable
data class DeviceInfo(val serial: String, val state: String, val model: String? = null)

@Serializable
data class AppInfo(val packageName: String, val foreground: Boolean)

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
}
