package io.yamsergey.dta.protocol.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Result of `network_script_set` and `network_script_status` (feature 005). */
@Serializable
data class ScriptStatus(
    val active: Boolean,
    /** First 12 hex chars of the source's SHA-256. */
    val id: String? = null,
    val installedAtMs: Long? = null,
    /** False when the script will not survive a restart. */
    val persisted: Boolean? = null,
    /** Status only. */
    val source: String? = null,
    val handlers: List<ScriptHandler> = emptyList(),
    /** Counters of this process run. */
    val invocations: Long = 0,
    val changes: Long = 0,
    val errors: Long = 0,
    /** Why the script cannot apply. */
    val unavailable: String? = null,
)

@Serializable
enum class ScriptLogLevel {
    @SerialName("log") LOG,
    @SerialName("error") ERROR,
}

@Serializable
data class ScriptLogLine(
    /** Monotonic within a process run. */
    val seq: Long,
    val timeMs: Long,
    val level: ScriptLogLevel,
    val handler: ScriptHandler? = null,
    val entryId: String? = null,
    val message: String,
)

/** Result of `network_script_logs`. */
@Serializable
data class ScriptLogPage(
    val lines: List<ScriptLogLine>,
    /** Pass as `since` to get the following lines. */
    val next: Long,
    /** Lines lost to the ring buffer since `since`. */
    val skipped: Long = 0,
)

/** The `script` field of the `network` capability. */
@Serializable
data class ScriptSummary(
    val id: String,
    val installedAtMs: Long,
    val unavailable: String? = null,
)

/** Result of `network_script_clear`. */
@Serializable
data class ScriptClearResult(val cleared: Boolean)
