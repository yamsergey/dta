package io.yamsergey.dta.protocol.network

/**
 * Feature 069: a history as an answer walks it, in first-record order. The app's history and the
 * tests give it as a list; this computer's archive reads it from disk as the walk goes, so no answer
 * needs a copy of the whole history.
 */
interface EntrySource : AutoCloseable {
    fun byId(id: String): NetworkEntry?

    /**
     * The entries in first-record order, reversed when [newestFirst]; links only when [links]; those
     * after the entry [after] (null: from the first). Null when [after] is not among them.
     */
    fun walk(newestFirst: Boolean, links: Boolean, after: String?): Sequence<NetworkEntry>?

    /** How many entries [walk] would give from the first, without walking them. */
    fun count(links: Boolean): Int

    /** The first entry in first-record order, without walking. */
    fun first(links: Boolean): String?

    /** A socket's messages in first-record order. */
    fun messagesOf(connectionId: String): List<WsMessageEntry>

    override fun close() {}
}

/**
 * Opens the history for one answer (one consistent view of it). [filter] is the answer's resolved
 * filter: a source may leave out entries it certainly drops, never one it may keep; null: none.
 */
fun interface EntrySourceProvider {
    fun open(filter: NetworkFilter?): EntrySource
}

/** A history held as a list (the app's, the tests'). */
class ListEntrySource(
    private val entries: List<NetworkEntry>,
    private val lookup: ((String) -> NetworkEntry?)? = null,
) : EntrySource {
    private val withoutLinks by lazy { entries.filter { it !is LinkEntry } }
    private val byIds by lazy { entries.associateBy { it.id } }

    override fun byId(id: String): NetworkEntry? = if (lookup != null) lookup.invoke(id) else byIds[id]

    override fun walk(newestFirst: Boolean, links: Boolean, after: String?): Sequence<NetworkEntry>? {
        val chronological = if (links) entries else withoutLinks
        val all = if (newestFirst) chronological.asReversed() else chronological
        if (after == null) return all.asSequence()
        val at = all.indexOfFirst { it.id == after }
        return if (at < 0) null else all.subList(at + 1, all.size).asSequence()
    }

    override fun count(links: Boolean): Int = if (links) entries.size else withoutLinks.size

    override fun first(links: Boolean): String? = (if (links) entries else withoutLinks).firstOrNull()?.id

    override fun messagesOf(connectionId: String): List<WsMessageEntry> =
        entries.filter { it is WsMessageEntry && it.connectionId == connectionId }.map { it as WsMessageEntry }
}
