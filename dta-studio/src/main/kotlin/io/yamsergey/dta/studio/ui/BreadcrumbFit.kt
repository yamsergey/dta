package io.yamsergey.dta.studio.ui

/**
 * Which items of a breadcrumb stay when the row is short of room: the last (the selected one, the identifying part) is
 * never dropped, then the first and the parent go last; the middle items go first, from the left, and one "…" stands
 * for them (`DecorView › … › Parent › Selected`). Pure: widths are px, or characters in tests.
 */
object BreadcrumbFit {
    /** The indices of [widths] kept, in order; [gap] is a separator with its spaces, [ellipsis] the "…" item's width. */
    fun kept(widths: List<Int>, gap: Int, ellipsis: Int, room: Int): List<Int> {
        val n = widths.size
        if (n == 0) return emptyList()
        fun total(kept: List<Int>): Int {
            val dropped = kept.size < n
            val items = kept.sumOf { widths[it] } + (if (dropped) ellipsis else 0)
            val parts = kept.size + (if (dropped) 1 else 0)
            return items + gap * (parts - 1).coerceAtLeast(0)
        }
        val kept = (0 until n).toMutableList()
        // The order things are given up: middle items from the left, then the parent, then the first.
        val order = (1..n - 3).toList() + listOfNotNull(if (n >= 3) n - 2 else null) + listOfNotNull(if (n >= 2) 0 else null)
        for (i in order) {
            if (total(kept) <= room) break
            kept.remove(i)
        }
        return kept
    }
}
