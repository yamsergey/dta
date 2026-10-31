package io.yamsergey.dta.protocol.network

import kotlinx.serialization.Serializable

/**
 * `network_identity` (feature 011, contracts/sidekick-journal.md): which journal the app writes.
 * [reason] says why it is new in this process (`cleared`, `storage-new`); [entries] how many
 * entries it holds; [persisted] false is the memory-only fallback, which answers queries itself.
 */
@Serializable
data class JournalIdentityResult(
    val journalId: String,
    val reason: String? = null,
    val entries: Int,
    val persisted: Boolean,
    /** What the host needs to answer queries as the app would (run, coverage, capture, script). */
    val context: HistoryContext,
)

/** `network_journal`: records in write order after a cursor; [next] resumes; [end] when caught up. */
@Serializable
data class JournalPageResult(val journalId: String, val records: List<NetworkEntry>, val next: String, val end: Boolean)

/** `network_body_range`: the range is the binary attachment; [totalBytes] is the whole stored body. */
@Serializable
data class BodyRangeResult(val totalBytes: Long, val offset: Long, val bytes: Long)
