package io.yamsergey.dta.protocol.rpc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Wire protocol version, negotiated in `hello` (contract §3). v1 policy: exact match. */
/** 2: the journal methods of feature 011; the daemon answers network queries from its own store. */
const val PROTOCOL_VERSION = 2

object ErrorCodes {
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL = -32603
    const val VERSION_INCOMPATIBLE = -32001
    const val NOT_READY = -32002
}

val DtaJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    classDiscriminator = "type"
}

@Serializable
sealed interface Message

@Serializable
@SerialName("request")
data class Request(
    val id: Long,
    val method: String,
    val params: JsonObject? = null,
) : Message

@Serializable
@SerialName("response")
data class Response(
    val id: Long,
    val result: JsonElement? = null,
    val error: ErrorInfo? = null,
    val degraded: List<DegradationMarker>? = null,
    val binary: BinaryAnnouncement? = null,
) : Message {
    init {
        require((result != null) xor (error != null)) {
            "Response must carry exactly one of result or error"
        }
    }
}

@Serializable
@SerialName("event")
data class Event(
    val name: String,
    val seq: Long,
    val data: JsonElement? = null,
) : Message

@Serializable
data class ErrorInfo(
    val code: Int,
    val message: String,
    val details: JsonObject? = null,
)

@Serializable
data class DegradationMarker(
    val capability: String,
    val reason: String,
)

@Serializable
data class BinaryAnnouncement(
    val frames: Int,
    val totalBytes: Long,
)

@Serializable
data class CapabilityStatus(
    val name: String,
    val status: Status,
    val reason: String? = null,
    /** layout capability only: detail levels currently servable (feature 003). */
    val levels: List<io.yamsergey.dta.protocol.layout.DetailLevel>? = null,
    /** network capability only: per-library capture status (feature 004). */
    val libraries: List<io.yamsergey.dta.protocol.network.LibraryStatus>? = null,
    /** network capability only: storage bounds (feature 004). */
    val bounds: io.yamsergey.dta.protocol.network.NetworkBounds? = null,
    /** network capability only: the active network script (feature 005). */
    val script: io.yamsergey.dta.protocol.network.ScriptSummary? = null,
    /** layout capability only: where Compose's tooling data came from, when DTA's injection knows (feature 010). */
    val tooling: ToolingSource? = null,
) {
    @Serializable
    enum class Status {
        @SerialName("available") AVAILABLE,
        @SerialName("degraded") DEGRADED,
        @SerialName("unavailable") UNAVAILABLE,
    }

    init {
        require(status == Status.AVAILABLE || reason != null) {
            "CapabilityStatus '$name' with status $status requires a reason (no silent degradation)"
        }
    }
}

@Serializable
data class HelloParams(
    val protocolVersion: Int,
    val client: String,
    val clientVersion: String,
)

@Serializable
data class HelloResult(
    val protocolVersion: Int,
    val server: String,
    val serverVersion: String,
    val packageName: String,
)

/**
 * Compose's tooling data in the app (feature 010 US6): [source] `dta` when DTA's injection added
 * it ([version]), `project` when the project declares it; [present] whether the sidekick can use it.
 */
@Serializable
data class ToolingSource(val present: Boolean, val source: String, val version: String? = null)

/** The installed build of the app, read inside it (feature 011, research R5). */
@Serializable
data class AppInstallation(
    val versionName: String? = null,
    val versionCode: Long? = null,
    val firstInstallMs: Long? = null,
    val lastUpdateMs: Long? = null,
    val signingSha256: String? = null,
)

@Serializable
data class IdentityReport(
    val protocolVersion: Int,
    val sidekickVersion: String,
    val packageName: String,
    val capabilities: List<CapabilityStatus>,
    /** Absent from sidekicks before feature 011. */
    val app: AppInstallation? = null,
)
