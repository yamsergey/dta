package io.yamsergey.dta.daemon.store

import io.yamsergey.dta.protocol.network.EntrySource
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.rpc.DtaJson
import java.sql.Connection

/**
 * Feature 069 (research R1): epochs of the archive as an answer walks them, read from disk in
 * pages by `(epoch, seq)` and decoded only as the walk reaches them. One read transaction: one
 * consistent view for the whole answer, whatever is written meanwhile. [epochs] are in the order
 * their entries come (first-record order: the earlier epoch first).
 */
class StoreEntrySource internal constructor(
    private val db: Connection,
    private val epochs: List<String>,
    private val pre: PreSelection?,
    private val release: (Connection) -> Unit,
    /** Feature 070: the word index's MATCH for the answer's need; null: every row is read. */
    private val match: String? = null,
    /** Rows read at a time (tests make it small to walk many batches). */
    private val page: Int = PAGE,
) : EntrySource {
    private val decoded = object : LinkedHashMap<String, NetworkEntry?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NetworkEntry?>?) = size > LOOKUPS
    }

    private fun decode(json: String) = DtaJson.decodeFromString(NetworkEntry.serializer(), json)

    private fun <T> query(sql: String, args: List<Any?>, map: (java.sql.ResultSet) -> T): List<T> = db.prepareStatement(sql).use { st ->
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

    /** The latest state of an entry: the last epoch that holds it (as one list of the epochs would). */
    override fun byId(id: String): NetworkEntry? = decoded.getOrPut(id) {
        epochs.asReversed().firstNotNullOfOrNull { e -> query("SELECT json FROM entry WHERE epoch_id=? AND entry_id=?", listOf(e, id)) { decode(it.getString(1)) }.firstOrNull() }
    }

    override fun walk(newestFirst: Boolean, links: Boolean, after: String?): Sequence<NetworkEntry>? {
        val start: Pair<Int, Long>? = after?.let { id ->
            val at = epochs.withIndex().firstNotNullOfOrNull { (i, e) ->
                query("SELECT seq, kind FROM entry WHERE epoch_id=? AND entry_id=?", listOf(e, id)) { it.getLong(1) to it.getString(2) }.firstOrNull()?.let { i to it }
            } ?: return null
            if (!links && at.second.second == "link") return null
            at.first to at.second.first
        }
        if (match != null) return candidates(newestFirst, links, start, match)
        val order = if (newestFirst) epochs.indices.reversed() else epochs.indices
        return sequence {
            for (i in order) {
                if (start != null && (if (newestFirst) i > start.first else i < start.first)) continue
                var cursor: Long? = if (start != null && i == start.first) start.second else null
                while (true) {
                    val conditions = buildList {
                        add("epoch_id = ?")
                        if (!links) add("kind <> 'link'")
                        cursor?.let { add(if (newestFirst) "seq < ?" else "seq > ?") }
                        pre?.let { add(it.sql) }
                    }
                    val args = buildList<Any?> { add(epochs[i]); cursor?.let { add(it) }; pre?.let { addAll(it.args) } }
                    val rows = query(
                        "SELECT seq, json FROM entry WHERE ${conditions.joinToString(" AND ")} ORDER BY seq ${if (newestFirst) "DESC" else "ASC"} LIMIT $page",
                        args,
                    ) { Row(it.getLong(1), it.getString(2)) }
                    for (row in rows) yield(decode(row.json))
                    if (rows.size < page) break
                    cursor = rows.last().seq
                }
            }
        }
    }

    private class Row(val seq: Long, val json: String)

    /**
     * Feature 070 (research R7): only the rows the word index may hold the need in, and those whose
     * words are not (all) written: pending or partial. Within an epoch rowid order is seq order, so
     * the entries come in the walk's order. [start] is (epoch index, seq) of the cursor.
     */
    private fun candidates(newestFirst: Boolean, links: Boolean, start: Pair<Int, Long>?, match: String): Sequence<NetworkEntry> = sequence {
        val order = if (newestFirst) epochs.indices.reversed() else epochs.indices
        for (i in order) {
            if (start != null && (if (newestFirst) i > start.first else i < start.first)) continue
            val epoch = epochs[i]
            val lo = query("SELECT rowid FROM entry WHERE epoch_id=? ORDER BY seq LIMIT 1", listOf(epoch)) { it.getLong(1) }.firstOrNull() ?: continue
            val hi = query("SELECT rowid FROM entry WHERE epoch_id=? ORDER BY seq DESC LIMIT 1", listOf(epoch)) { it.getLong(1) }.single()
            var cursor: Long = if (start != null && i == start.first) {
                query("SELECT rowid FROM entry WHERE epoch_id=? AND seq=?", listOf(epoch, start.second)) { it.getLong(1) }.single()
            } else if (newestFirst) hi + 1 else lo - 1
            while (true) {
                val found = if (newestFirst) {
                    query("SELECT rowid FROM entry_words WHERE entry_words MATCH ? AND rowid < ? AND rowid >= ? ORDER BY rowid DESC LIMIT $page", listOf(match, cursor, lo)) { it.getLong(1) }
                } else {
                    query("SELECT rowid FROM entry_words WHERE entry_words MATCH ? AND rowid > ? AND rowid <= ? ORDER BY rowid LIMIT $page", listOf(match, cursor, hi)) { it.getLong(1) }
                }
                val full = found.size == page
                // The range this batch covers: up to the last row the index gave, or the epoch's end.
                val edge = if (full) found.last() else if (newestFirst) lo else hi
                val (from, to) = if (newestFirst) edge to cursor - 1 else cursor + 1 to edge
                val unwritten = query(
                    "SELECT rowid FROM word_pending WHERE rowid BETWEEN ? AND ? UNION SELECT rowid FROM word_partial WHERE rowid BETWEEN ? AND ?",
                    listOf(from, to, from, to),
                ) { it.getLong(1) }
                val ids = (found + unwritten).distinct().let { if (newestFirst) it.sortedDescending() else it.sorted() }
                for (chunk in ids.chunked(page)) {
                    val conditions = buildList {
                        add("rowid IN (${chunk.joinToString(",")})")
                        add("epoch_id = ?")
                        if (!links) add("kind <> 'link'")
                        pre?.let { add(it.sql) }
                    }
                    val rows = query("SELECT rowid, json FROM entry WHERE ${conditions.joinToString(" AND ")}", buildList { add(epoch); pre?.let { addAll(it.args) } }) { it.getLong(1) to it.getString(2) }
                        .toMap()
                    for (id in chunk) rows[id]?.let { yield(decode(it)) }
                }
                if (!full) break
                cursor = edge
            }
        }
    }

    override fun count(links: Boolean): Int = epochs.sumOf { e ->
        query("SELECT entries, links FROM epoch WHERE id=?", listOf(e)) { if (links) it.getInt(1) else it.getInt(1) - it.getInt(2) }.firstOrNull() ?: 0
    }

    override fun first(links: Boolean): String? = epochs.firstNotNullOfOrNull { e ->
        query("SELECT entry_id FROM entry WHERE epoch_id=?${if (links) "" else " AND kind <> 'link'"} ORDER BY seq LIMIT 1", listOf(e)) { it.getString(1) }.firstOrNull()
    }

    override fun messagesOf(connectionId: String): List<WsMessageEntry> = epochs.flatMap { e ->
        query("SELECT json FROM entry WHERE epoch_id=? AND connection_id=? AND kind='ws-message' ORDER BY seq", listOf(e, connectionId)) { decode(it.getString(1)) as WsMessageEntry }
    }

    override fun close() = release(db)

    internal companion object {
        const val PAGE = 500
        const val LOOKUPS = 4_096
    }
}
