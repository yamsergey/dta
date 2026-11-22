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

        /**
         * The line under the list: without a filter, the history's size; with one, how many rows are
         * shown and the history's size, and the saved filters that apply. Feature 069: matches are
         * not counted (it would read the whole history).
         */
        fun caption(shown: Int, history: Int, saved: SavedFiltersApplied?, filtered: Boolean, more: Boolean = false): String {
            val savedOn = saved?.takeIf { !it.ignored && it.words.isNotEmpty() }?.words?.size
            if (!filtered && savedOn == null) return "${grouped(history)} entries"
            // Feature 070: rows loaded so far, not every match; D-022: the Load older row says when more can load.
            val base = "${grouped(shown)} shown · ${grouped(history)} in the history"
            return if (savedOn == null) base else "$base · $savedOn saved filter${if (savedOn == 1) "" else "s"} on"
        }

        /**
         * D-022: the Load older row's grey words — how many entries the history holds beyond the rows
         * shown when nothing filters (with filters no match is counted, 069).
         */
        fun older(shown: Int, history: Int, filtered: Boolean): String =
            if (!filtered && history > shown) "${grouped(history - shown)} older in the history"
            else "older matches in the history"

        /** 125690 as "125 690", as the tab writes counts. */
        fun grouped(n: Int): String = n.toString().reversed().chunked(3).joinToString(" ").reversed()
    }
}
