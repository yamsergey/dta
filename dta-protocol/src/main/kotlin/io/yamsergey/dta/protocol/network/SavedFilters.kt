package io.yamsergey.dta.protocol.network

import kotlinx.serialization.Serializable

/** A filter the app had saved before (feature 043), offered to bring it back. */
@Serializable
data class RecentFilter(val word: String, val lastUsedMs: Long)

/**
 * Feature 043: an app's saved filters, kept by the daemon per package. [saved] are words of the
 * query language (040) applied to every list and search unless a call ignores them; [problem]
 * says the store could not be read and the app started with none.
 */
@Serializable
data class SavedFilters(
    val saved: List<String> = emptyList(),
    val recent: List<RecentFilter> = emptyList(),
    val problem: String? = null,
) {
    /** The line a call runs with, and the saved words that really apply to it. */
    data class Merged(val applied: List<String>, val q: String?)

    companion object {
        const val MAX_RECENT = 20

        /** A saved filter of a key the line also gives yields to the line: the person just typed it. */
        fun merge(saved: List<String>, q: String?): Merged {
            val line = q?.takeIf { it.isNotBlank() }
            val typed = line?.let { l -> QueryLine.parse(l).tokens.filter { it.key != null && !it.negated }.mapNotNull { it.key }.toSet() }.orEmpty()
            val applied = saved.filter { positiveKey(it) !in typed || positiveKey(it) == null }
            if (applied.isEmpty()) return Merged(emptyList(), q)
            return Merged(applied, (applied + listOfNotNull(line)).joinToString(" "))
        }

        /** The key [word] sets when it is not an exclusion: only one of each can be in force. */
        fun positiveKey(word: String): String? = QueryLine.parse(word).tokens.firstOrNull()?.takeIf { !it.negated }?.key
    }
}

/**
 * Feature 043, on list and search results: the saved filters behind this answer. [hidden] is how
 * many entries they left out (null on later pages); [ignored] means the call asked not to apply them.
 */
@Serializable
data class SavedFiltersApplied(
    val words: List<String>,
    val hidden: Int? = null,
    val ignored: Boolean = false,
)
