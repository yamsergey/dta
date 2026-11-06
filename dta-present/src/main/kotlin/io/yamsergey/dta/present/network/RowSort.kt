package io.yamsergey.dta.present.network

/**
 * Feature 043: sorting the Network list by a column header. Time is the list's own order (the
 * live edge); any other column sorts the loaded rows. [column] counts as the table does:
 * Time, Type, Method, URL, Status, Duration · Size, Notes.
 */
data class RowSort(val column: Int, val ascending: Boolean = true) {
    fun comparator(): Comparator<EntryRow> {
        val by: Comparator<EntryRow> = when (column) {
            1 -> compareBy { it.kind.label }
            2 -> compareBy { it.verb }
            3 -> compareBy { (it.host.orEmpty() + it.path).lowercase() }
            4 -> compareBy { it.outcome.text.lowercase() }
            5 -> compareBy { millis(it.timing) ?: 0L }
            6 -> compareBy { it.marks.shown.joinToString { m -> m.word } }
            else -> compareBy { it.time }
        }
        val directed = if (ascending) by else by.reversed()
        // Rows with no duration (a message's "22 B") stay at the end whichever way it runs.
        return if (column == 5) compareBy<EntryRow> { millis(it.timing) == null }.then(directed) else directed
    }

    companion object {
        private val DURATION = Regex("""^([\d.]+)\s*(ms|s|min)\b""")

        /** "142 ms · 990 B" → 142, "1.3 s · 18 KB" → 1300; null when it does not start with a duration. */
        fun millis(timing: String): Long? {
            val m = DURATION.find(timing.trim()) ?: return null
            val n = m.groupValues[1].toDoubleOrNull() ?: return null
            return (n * when (m.groupValues[2]) { "ms" -> 1.0; "s" -> 1000.0; else -> 60_000.0 }).toLong()
        }
    }
}
