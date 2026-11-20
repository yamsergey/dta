package io.yamsergey.dta.protocol.network

import kotlinx.serialization.Serializable

/**
 * Feature 066: what this computer has archived of an app's journal, told to the device so it may
 * remove it (research R1). Records before [through] are kept here, except the bodies of [keep].
 */
@Serializable
data class ArchiveAck(
    val journalId: String,
    /** The stable id of this computer's DTA home. */
    val hostId: String,
    /** The host's journal cursor (`segment:line`) after a complete catch-up. */
    val through: String,
    /** Entries before [through] whose bodies this computer has not copied yet. */
    val keep: List<String> = emptyList(),
    /** When the device accepted it (device clock); set by the device. */
    val atMs: Long? = null,
)

/** Result of `network_archived`. */
@Serializable
data class ArchiveAckResult(
    val accepted: Boolean,
    /** `journal-changed`, `reading` or `invalid-cursor` when not accepted. */
    val reason: String? = null,
    /** The history is over its budget and a trim pass will run. */
    val trimPending: Boolean = false,
)

/** The device history's settings in force, with its size (`network_history_settings`, `network.history.set`). */
@Serializable
data class HistorySettings(
    val keepBytes: Long,
    val warnBytes: Long,
    val deviceBytes: Long? = null,
    val trimHeld: String? = null,
    val lastArchivedMs: Long? = null,
)

/** The device's part of an app's history (`network.history`). */
@Serializable
data class DeviceHistory(
    val entries: Int,
    val bytes: Long,
    val earliestMs: Long? = null,
    val latestMs: Long? = null,
    /** Device entries this computer has archived (before the last acknowledgement, not kept for their bodies). */
    val archivedEntries: Int? = null,
    val keepBytes: Long,
    val warnBytes: Long,
    val trimHeld: String? = null,
    val lastArchivedMs: Long? = null,
    val overWarning: Boolean = false,
    /** Feature 068: records of the device's journal this computer still has to read (catching up); null when none. */
    val catchingUp: Long? = null,
    /** Feature 068: the app is still reading its earlier runs after it started. */
    val reading: Boolean = false,
    /**
     * Feature 068: how far behind the app this computer's archive was in the last 10 s, the most of
     * any batch: its wait on the device plus its wait here until archived (each on its own clock;
     * the channel's own delay is not in it). Null when nothing came by the feed in that time.
     */
    val feedLagMs: Long? = null,
)

/** This computer's part of an app's history (`network.history`). */
@Serializable
data class ArchiveHistory(
    val entries: Long,
    val bytes: Long,
    val earliestMs: Long? = null,
    val latestMs: Long? = null,
    val installations: List<ArchiveInstallation> = emptyList(),
)

/** Result of `network.history`: the device part is null, with [deviceUnknown] saying why, when the app is not reachable. */
@Serializable
data class HistorySummary(
    val device: DeviceHistory? = null,
    val deviceUnknown: String? = null,
    val archive: ArchiveHistory,
    /** Entries kept on this computer only (the device removed them after archiving). */
    val hostOnly: Long? = null,
)

/** What a clear could not keep: not yet on this computer when the device forgot it (feature 066 R6). */
@Serializable
data class NotKept(val entries: Int, val bodies: Int, val reason: String)

object HistoryDefaults {
    const val KEEP_BYTES = 256L * 1024 * 1024
    const val WARN_BYTES = 512L * 1024 * 1024
    const val MIN_BYTES = 16L * 1024 * 1024

    /** Why these settings are refused, or null. */
    fun problem(keepBytes: Long, warnBytes: Long): String? = when {
        keepBytes < MIN_BYTES -> "the budget is at least 16 MB"
        warnBytes < MIN_BYTES -> "the warning size is at least 16 MB"
        warnBytes < keepBytes -> "the warning size cannot be under the budget (${keepBytes / (1024 * 1024)} MB)"
        else -> null
    }

    private val SIZE = Regex("""(?i)\s*(\d+(?:\.\d+)?)\s*(b|kb|k|mb|m|gb|g)?\s*""")

    /** `128MB`, `1.5 GB`, `2kb`, plain bytes. */
    fun parseSize(text: String): Long {
        val m = SIZE.matchEntire(text) ?: throw IllegalArgumentException("'$text' is not a size: write it as 128MB, 1.5GB or a number of bytes")
        val n = m.groupValues[1].toDouble()
        val unit = when (m.groupValues[2].lowercase()) {
            "", "b" -> 1L
            "k", "kb" -> 1024L
            "m", "mb" -> 1024L * 1024
            else -> 1024L * 1024 * 1024
        }
        return (n * unit).toLong()
    }
}
