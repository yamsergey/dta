package io.yamsergey.dta.protocol.network

import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Feature 068 (contracts/feed.md): the app's journal as one feed. After this computer has read the
 * journal to its end, it opens a subscription from that position; the app sends what was written
 * after it, then each record right after writing it, as [FeedBatch] events. Positions are journal
 * cursors (`segment:line`); a batch starts where the previous one ended.
 */
@Serializable
data class FeedBatch(
    val subscription: String,
    val journalId: String,
    /** The cursor this batch starts after: the previous batch's [to], or the position it was opened from. */
    val from: String,
    /** The cursor after its last record. */
    val to: String,
    val records: List<NetworkEntry>,
    /** Bodies of up to [FeedBody.MAX_BYTES] of these records (none for one its preview holds whole). */
    val bodies: List<FeedBody>? = null,
) {
    companion object {
        const val EVENT = "network.feed"
    }
}

/** A subscription ended: [reason] is [OVERFLOW] or [CLOSED]; nothing after [at] was sent. */
@Serializable
data class FeedEnded(val subscription: String, val reason: String, val at: String) {
    companion object {
        const val EVENT = "network.feed.ended"
        const val OVERFLOW = "overflow"
        const val CLOSED = "closed"
    }
}

/** `network_feed_open` params: the journal and the position this computer holds. */
@Serializable
data class FeedOpenParams(val journalId: String, val after: String)

/**
 * `network_feed_open` answer: [subscription] when opened. Otherwise [refused] says why:
 * [JOURNAL_CHANGED] (another journal: read it from its start), [BEHIND] (more than a page was
 * written after [FeedOpenParams.after]: read pages first), [READING] (the app is still reading its
 * earlier runs), [NOT_PERSISTED] (no journal).
 */
@Serializable
data class FeedOpenResult(val subscription: String? = null, val refused: String? = null) {
    companion object {
        const val JOURNAL_CHANGED = "journal-changed"
        const val BEHIND = "behind"
        const val READING = "reading"
        const val NOT_PERSISTED = "not-persisted"
    }
}

/** A small body carried with its record: [text] when it is UTF-8 text, else [base64]. */
@Serializable
data class FeedBody(
    val entryId: String,
    val part: BodyPart,
    val text: String? = null,
    val base64: String? = null,
) {
    @OptIn(ExperimentalEncodingApi::class)
    fun bytes(): ByteArray = text?.toByteArray(Charsets.UTF_8) ?: Base64.decode(base64 ?: "")

    companion object {
        /** Bodies up to this size travel with their records (research R3). */
        const val MAX_BYTES = 64L * 1024

        @OptIn(ExperimentalEncodingApi::class)
        fun of(entryId: String, part: BodyPart, bytes: ByteArray, binary: Boolean): FeedBody {
            val text = if (binary) null else utf8(bytes)
            return if (text != null) FeedBody(entryId, part, text = text) else FeedBody(entryId, part, base64 = Base64.encode(bytes))
        }

        private fun utf8(bytes: ByteArray): String? = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()?.takeIf { s -> s.none { it == '\u0000' } }
    }
}
