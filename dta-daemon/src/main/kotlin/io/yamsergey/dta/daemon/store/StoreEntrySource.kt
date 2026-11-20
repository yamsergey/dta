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
                        "SELECT seq, json FROM entry WHERE ${conditions.joinToString(" AND ")} ORDER BY seq ${if (newestFirst) "DESC" else "ASC"} LIMIT $PAGE",
                        args,
                    ) { it.getLong(1) to it.getString(2) }
                    for ((_, json) in rows) yield(decode(json))
                    if (rows.size < PAGE) break
                    cursor = rows.last().first
                }
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

    private companion object {
        const val PAGE = 500
        const val LOOKUPS = 4_096
    }
}
