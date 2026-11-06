package io.yamsergey.dta.present.network

import io.yamsergey.dta.protocol.network.SavedFiltersApplied

/** Feature 043: a saved filter as a chip: its key muted, its value plain. */
data class SavedChip(val word: String, val key: String, val value: String) {
    companion object {
        fun of(word: String): SavedChip {
            val colon = word.indexOf(':')
            return if (colon > 0) SavedChip(word, word.substring(0, colon + 1), word.substring(colon + 1)) else SavedChip(word, "", word)
        }

        /** When a filter was last used, as the list of recent ones says it. */
        fun age(thenMs: Long, nowMs: Long): String {
            val minutes = (nowMs - thenMs).coerceAtLeast(0) / 60_000
            return when {
                minutes < 1 -> "just now"
                minutes < 60 -> "$minutes min ago"
                minutes < 24 * 60 -> "${minutes / 60} h ago"
                minutes / (24 * 60) == 1L -> "yesterday"
                else -> "${minutes / (24 * 60)} days ago"
            }
        }

        /** The line under the list: what is shown and what the saved filters hid. */
        fun caption(shown: Int, total: Int, saved: SavedFiltersApplied?): String {
            val hidden = saved?.hidden?.takeIf { it > 0 && !saved.ignored } ?: return "$shown of $total shown"
            val count = saved.words.size
            val base = if (shown == total) "$shown shown" else "$shown of $total shown"
            return "$base · $hidden hidden by $count saved filter" + if (count == 1) "" else "s"
        }
    }
}
