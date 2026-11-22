package io.yamsergey.dta.daemon.store

import io.yamsergey.dta.protocol.network.EntrySource
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.rpc.DtaJson
import java.sql.Connection

/**
 * Feature 069 (research R1): epochs of the archive as an answer walks them, read from disk in
 * pages by `(epoch, seq)` and decoded only as the walk reaches them. 071: each page is its own read
 * (see [query]); the seq cursor keeps a walk from giving a row twice. [epochs] are in the order
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
    /** Feature 071: only this run of its epoch (the default scope), from where it starts. */
    private val run: RunBound? = null,
    /** Called once per row read (tests count what a walk reads). */
    private val onRow: () -> Unit = {},
) : EntrySource {
    /** The run's conditions for epoch [epoch], or none. */
    private fun runSql(epoch: String): Pair<String, List<Any?>>? =
        run?.takeIf { it.epochId == epoch }?.let { "coalesce(run_id, '') = ? AND seq >= ?" to listOf(it.runId, it.firstSeq) }

    private val decoded = object : LinkedHashMap<String, NetworkEntry?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NetworkEntry?>?) = size > LOOKUPS
    }

    private fun decode(json: String) = DtaJson.decodeFromString(NetworkEntry.serializer(), json)

    /**
     * Feature 071: an entry of epoch [i] that a later epoch holds too (read again after a rollback) is
     * given once, in its latest state, as [byId] gives it: no entry shows twice in a whole-history answer.
     */
    private fun shadowed(i: Int, entryId: String): Boolean {
        if (i == epochs.lastIndex) return false
        val later = epochs.subList(i + 1, epochs.size)
        return query("SELECT 1 FROM entry WHERE entry_id=? AND epoch_id IN (${later.joinToString(",") { "?" }}) LIMIT 1", listOf(entryId) + later) { true }.isNotEmpty()
    }

    /**
     * Feature 071: each read ends its transaction once its rows are taken. A walk may run for minutes (no
     * time limit, or a pattern stuck on one body): a read held open that long keeps SQLite from checkpointing
     * its WAL, which then grows, and every writer and reader slows with it (measured: opening an entry went
     * from 0.3 s to 1.9 s beside two stuck searches). The walk's cursor is by seq, so no row comes twice.
     */
    private fun <T> query(sql: String, args: List<Any?>, map: (java.sql.ResultSet) -> T): List<T> = try {
        db.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
    } finally {
        runCatching { db.rollback() }
    }

    /** The latest state of an entry: the last epoch that holds it (as one list of the epochs would). */
    override fun byId(id: String): NetworkEntry? = decoded.getOrPut(id) {
        epochs.asReversed().firstNotNullOfOrNull { e -> query("SELECT json FROM entry WHERE epoch_id=? AND entry_id=?", listOf(e, id)) { decode(it.getString(1)) }.firstOrNull() }
    }

    override fun walk(newestFirst: Boolean, links: Boolean, after: String?): Sequence<NetworkEntry>? {
        val start: Pair<Int, Long>? = after?.let { id ->
            // The latest epoch holding it: the copy the walk gives (an earlier one is shadowed, 071).
            val at = epochs.withIndex().reversed().firstNotNullOfOrNull { (i, e) ->
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
                    val r = runSql(epochs[i])
                    val conditions = buildList {
                        add("epoch_id = ?")
                        if (!links) add("kind <> 'link'")
                        cursor?.let { add(if (newestFirst) "seq < ?" else "seq > ?") }
                        pre?.let { add(it.sql) }
                        r?.let { add(it.first) }
                    }
                    val args = buildList<Any?> { add(epochs[i]); cursor?.let { add(it) }; pre?.let { addAll(it.args) }; r?.let { addAll(it.second) } }
                    val rows = query(
                        "SELECT seq, json FROM entry WHERE ${conditions.joinToString(" AND ")} ORDER BY seq ${if (newestFirst) "DESC" else "ASC"} LIMIT $page",
                        args,
                    ) { Row(it.getLong(1), it.getString(2)) }
                    for (row in rows) { onRow(); val e = decode(row.json); if (!shadowed(i, e.id)) yield(e) }
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
            val r = runSql(epoch)
            val lo = query("SELECT rowid FROM entry WHERE epoch_id=? AND seq >= ? ORDER BY seq LIMIT 1", listOf(epoch, run?.takeIf { it.epochId == epoch }?.firstSeq ?: Long.MIN_VALUE)) { it.getLong(1) }.firstOrNull() ?: continue
            val hi = query("SELECT rowid FROM entry WHERE epoch_id=? ORDER BY seq DESC LIMIT 1", listOf(epoch)) { it.getLong(1) }.single()
            var cursor: Long = if (start != null && i == start.first) {
                query("SELECT rowid FROM entry WHERE epoch_id=? AND seq=?", listOf(epoch, start.second)) { it.getLong(1) }.single()
            } else if (newestFirst) hi + 1 else lo - 1
            while (true) {
                // Three streams in rowid order, a page of each: the index's matches, and the rows whose words are
                // not (all) written. The batch covers up to the nearest edge a full page reached.
                fun stream(sql: String, args: List<Any?>): List<Long> = if (newestFirst) {
                    query("$sql AND rowid < ? AND rowid >= ? ORDER BY rowid DESC LIMIT $page", args + listOf(cursor, lo)) { it.getLong(1) }
                } else {
                    query("$sql AND rowid > ? AND rowid <= ? ORDER BY rowid LIMIT $page", args + listOf(cursor, hi)) { it.getLong(1) }
                }
                val streams = listOf(
                    stream("SELECT rowid FROM entry_words WHERE entry_words MATCH ?", listOf(match)),
                    stream("SELECT rowid FROM word_pending WHERE 1=1", emptyList()),
                    stream("SELECT rowid FROM word_partial WHERE 1=1", emptyList()),
                )
                val ends = streams.filter { it.size == page }.map { it.last() }
                val full = ends.isNotEmpty()
                val edge = when {
                    !full -> if (newestFirst) lo else hi
                    newestFirst -> ends.max()
                    else -> ends.min()
                }
                val ids = streams.flatten().filter { if (newestFirst) it >= edge else it <= edge }.distinct().let { if (newestFirst) it.sortedDescending() else it.sorted() }
                for (chunk in ids.chunked(page)) {
                    val conditions = buildList {
                        add("rowid IN (${chunk.joinToString(",")})")
                        add("epoch_id = ?")
                        if (!links) add("kind <> 'link'")
                        pre?.let { add(it.sql) }
                        r?.let { add(it.first) }
                    }
                    // NOT INDEXED: by rowid, not a scan of the epoch's index (on a million rows, 10 ms against 0.2 ms a batch).
                    val rows = query("SELECT rowid, json FROM entry NOT INDEXED WHERE ${conditions.joinToString(" AND ")}", buildList { add(epoch); pre?.let { addAll(it.args) }; r?.let { addAll(it.second) } }) { it.getLong(1) to it.getString(2) }
                        .toMap()
                    for (id in chunk) rows[id]?.let { onRow(); val e = decode(it); if (!shadowed(i, e.id)) yield(e) }
                }
                if (!full) break
                cursor = edge
            }
        }
    }

    override fun count(links: Boolean): Int = epochs.sumOf { e ->
        if (run?.epochId == e) return@sumOf query("SELECT entries, links FROM run WHERE epoch_id=? AND run_id=?", listOf(e, run.runId)) { if (links) it.getInt(1) else it.getInt(1) - it.getInt(2) }.firstOrNull() ?: 0
        query("SELECT entries, links FROM epoch WHERE id=?", listOf(e)) { if (links) it.getInt(1) else it.getInt(1) - it.getInt(2) }.firstOrNull() ?: 0
    }

    override fun first(links: Boolean): String? = epochs.firstNotNullOfOrNull { e ->
        val r = runSql(e)
        query("SELECT entry_id FROM entry WHERE epoch_id=?${if (links) "" else " AND kind <> 'link'"}${r?.let { " AND " + it.first } ?: ""} ORDER BY seq LIMIT 1", listOf(e) + r?.second.orEmpty()) { it.getString(1) }.firstOrNull()
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

/** Feature 071: one run of an epoch, from where it starts: the default scope of an answer. */
data class RunBound(val epochId: String, val runId: String, val firstSeq: Long)
