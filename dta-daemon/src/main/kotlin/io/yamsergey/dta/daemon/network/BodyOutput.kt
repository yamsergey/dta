package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.RedactionInfo
import io.yamsergey.dta.protocol.rpc.DegradationMarker

/** Where a fetched body goes (contract §7). */
sealed interface BodyOutput {
    /** Decoded text; binary bodies are refused with a pointer to [File]. */
    data object Text : BodyOutput

    /** Bytes written to [path]; [decoded] undoes Content-Encoding, false keeps wire bytes. */
    data class File(val path: String, val decoded: Boolean = true) : BodyOutput
}

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
)

/** Text was requested for a binary body. */
class BinaryBodyException(message: String) : Exception(message)
