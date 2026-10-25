package io.yamsergey.dta.protocol.rpc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Wire protocol version, negotiated in `hello` (contract §3). v1 policy: exact match. */
const val PROTOCOL_VERSION = 1

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

@Serializable
data class IdentityReport(
    val protocolVersion: Int,
    val sidekickVersion: String,
    val packageName: String,
    val capabilities: List<CapabilityStatus>,
)
