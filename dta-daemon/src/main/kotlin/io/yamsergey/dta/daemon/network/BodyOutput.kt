package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.DecodingInfo
import io.yamsergey.dta.protocol.network.RedactionInfo
import io.yamsergey.dta.protocol.rpc.DegradationMarker

/** Where a fetched body goes (contract §7). */
sealed interface BodyOutput {
    /**
     * Decoded text. A binary body a schema selects comes back as JSON; other binary bodies
     * are refused with a pointer to [File].
     */
    data object Text : BodyOutput

    /**
     * Bytes written to [path]; [decoded] undoes Content-Encoding, false keeps wire bytes.
     * [structured] writes the decoded JSON instead, or fails with [BodyNotDecodedException].
     */
    data class File(val path: String, val decoded: Boolean = true, val structured: Boolean = false) : BodyOutput
}

@kotlinx.serialization.Serializable
data class BodyResult(
    val body: BodyInfo,
    /** [BodyOutput.Text] only. */
    val text: String? = null,
    /** [BodyOutput.File] only. */
    val file: String? = null,
    /** Bytes returned as text or written to the file. */
    val bytes: Long,
    /** [BodyOutput.File] only: of the bytes written. */
    val sha256: String? = null,
    /** e.g. an encoding that could not be undone. */
    val note: String? = null,
    val degraded: List<DegradationMarker>? = null,
    /** Present iff redaction was requested; `applied = false` when overridden. */
    val redaction: RedactionInfo? = null,
    /** How the body was (not) decoded as structured data (feature 006). */
    val decoding: DecodingInfo? = null,
)

/** Text was requested for a binary body. */
class BinaryBodyException(message: String) : Exception(message)

/** Structured output was requested for a body that was not decoded; [decoding] says why. */
class BodyNotDecodedException(val decoding: DecodingInfo) :
    Exception("body not decoded: ${decoding.reason ?: "no schema maps it (network.schema.add, then network.mapping.add)"}")
