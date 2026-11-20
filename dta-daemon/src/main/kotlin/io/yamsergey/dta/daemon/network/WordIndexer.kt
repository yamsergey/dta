package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.daemon.store.WordWrite
import io.yamsergey.dta.protocol.network.IndexText
import io.yamsergey.dta.protocol.network.NetworkEntry

/**
 * Feature 070 (research R5, R8): writes the words of one app's pending entries, newest first, a
 * small batch at a time, off the feed's path (the feed only leaves entries pending). An entry
 * still pending is read by every answer, so the index may lag behind a burst and miss nothing.
 *
 * [text] builds an entry's [IndexText] with the app's current ports (bodies here, schemas);
 * [fingerprint] names those schemas: when it changes, the entries whose words they decide are
 * pending again.
 */
class WordIndexer(
    private val store: HostStore,
    private val deviceKey: String,
    private val packageName: String,
    private val epochs: () -> List<String>,
    private val fingerprint: () -> String,
    private val text: (epochId: String, entry: NetworkEntry) -> IndexText.Result,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** Writes up to [limit] entries' words, building them for at most [budgetMs]; returns how many were written. */
    fun step(limit: Int = BATCH, budgetMs: Long = BUDGET_MS): Int {
        val ids = epochs()
        if (ids.isEmpty()) return 0
        val now = fingerprint()
        if (store.wordFingerprint(deviceKey, packageName) != now) {
            // Pending first, then the new name: until both are written, those entries are read as without an index.
            store.markDerivedPending(ids)
            store.setWordFingerprint(deviceKey, packageName, now)
        }
        val pending = store.pendingWords(ids, limit)
        if (pending.isEmpty()) return 0
        val start = nanoTime()
        val writes = ArrayList<WordWrite>(pending.size)
        for (p in pending) {
            // A body that cannot be read now is read by the answers, as without an index.
            val t = runCatching { text(p.epochId, p.entry) }.getOrElse { IndexText.Result("", cut = true) }
            writes += WordWrite(p.rowid, p.mark, t.text, t.cut)
            if ((nanoTime() - start) / 1_000_000 >= budgetMs) break
        }
        store.putWords(writes)
        return writes.size
    }

    /** Entries of the app still to index (for measurements and logs). */
    fun pending(): Int = store.pendingWordCount(epochs())

    companion object {
        const val BATCH = 200
        /** Time to build one batch's words; the write that follows takes about a millisecond. */
        const val BUDGET_MS = 20L
    }
}
