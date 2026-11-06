package io.yamsergey.dta.protocol.network

import kotlinx.serialization.Serializable

/**
 * Which archived history a network query covers (feature 011, contracts/archive-ops.md).
 * Absent: the live history only. Exactly one of the fields is set.
 */
@Serializable
data class ArchiveScope(
    val epoch: String? = null,
    /** An app version name: every epoch of installations with it. */
    val version: String? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val all: Boolean? = null,
) {
    init {
        val set = listOfNotNull(epoch, version, all?.takeIf { it }, (fromMs ?: toMs)).size
        require(set == 1) { "an archive scope names exactly one of epoch, version, a time range (fromMs/toMs) or all" }
    }

    companion object {
        /** The CLI and tool form: `all`, `version:1.4.0`, `<fromMs>..<toMs>` (either side may be empty), or an epoch id. */
        fun parse(text: String): ArchiveScope {
            val t = text.trim()
            return when {
                t == "all" -> ArchiveScope(all = true)
                t.startsWith("version:") -> ArchiveScope(version = t.removePrefix("version:").also { require(it.isNotBlank()) { "version: needs a version name" } })
                ".." in t -> {
                    val (from, to) = t.split("..", limit = 2)
                    ArchiveScope(
                        fromMs = from.takeIf { it.isNotBlank() }?.let { it.toLongOrNull() ?: throw IllegalArgumentException("'$from' is not epoch milliseconds") },
                        toMs = to.takeIf { it.isNotBlank() }?.let { it.toLongOrNull() ?: throw IllegalArgumentException("'$to' is not epoch milliseconds") },
                    )
                }
                t.isNotEmpty() -> ArchiveScope(epoch = t)
                else -> throw IllegalArgumentException("an archive scope is all, version:<name>, <fromMs>..<toMs> or an epoch id")
            }
        }
    }
}

/** Why an epoch was closed: the device forgot its history (feature 011, research R7). */
@Serializable
enum class EpochCloseReason {
    @kotlinx.serialization.SerialName("cleared") CLEARED,
    @kotlinx.serialization.SerialName("data-wiped") DATA_WIPED,
    @kotlinx.serialization.SerialName("reinstalled") REINSTALLED,
    @kotlinx.serialization.SerialName("new-build") NEW_BUILD,
    /** The journal went back to an earlier state with the same id: an emulator snapshot, restored app data (feature 046). */
    @kotlinx.serialization.SerialName("rolled-back") ROLLED_BACK,
    @kotlinx.serialization.SerialName("unknown") UNKNOWN,
}

/** On an entry from the archive: which epoch, why it closed, and the app version then. */
@Serializable
data class ArchivedMark(val epoch: String, val reason: EpochCloseReason, val closedMs: Long, val version: String? = null)

/** One node of `archive.list`: device › app › installation › epoch. */
@Serializable
data class ArchiveEpoch(
    val id: String,
    val openedMs: Long,
    val closedMs: Long? = null,
    val reason: EpochCloseReason? = null,
    val entries: Int,
    val bytes: Long,
    /** Entries the device had that never reached this host. */
    val neverReceived: Int = 0,
) {
    val live: Boolean get() = closedMs == null
}

@Serializable
data class ArchiveInstallation(
    /** For `archive delete --installation`. */
    val id: Long,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val lastUpdateMs: Long? = null,
    val epochs: List<ArchiveEpoch>,
)

@Serializable
data class ArchiveApp(val packageName: String, val installations: List<ArchiveInstallation>)

@Serializable
data class ArchiveDevice(val key: String, val serial: String? = null, val model: String? = null, val apps: List<ArchiveApp>)

@Serializable
data class ArchiveListing(val devices: List<ArchiveDevice>, val bytes: Long)

/** What `archive.delete` removed. */
@Serializable
data class ArchiveDeleted(val epochs: Int, val entries: Int, val bodiesFreed: Int, val bytesFreed: Long)

/** The answer of `archive.delete`: without `confirm` only what it would remove. */
@Serializable
data class ArchiveDeletion(val confirmed: Boolean, val removed: ArchiveDeleted)
