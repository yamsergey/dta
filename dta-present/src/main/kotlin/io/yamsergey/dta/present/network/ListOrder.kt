package io.yamsergey.dta.present.network

/**
 * Which edge of the live list new rows appear at. [LiveList] keeps rows oldest to newest; the
 * order only maps a row's place in it to where it is shown, and back (the mapping is its own
 * inverse). Following the list means staying at [followEdge].
 */
enum class ListOrder(val label: String) {
    NEWEST_FIRST("Newest on top"),
    OLDEST_FIRST("Newest at bottom");

    /** Where row [index] of [size] is shown, or which row is shown at [index]. */
    fun shown(index: Int, size: Int): Int = if (this == NEWEST_FIRST) size - 1 - index else index

    /** The shown places of the rows a list of [before] rows gained to have [after]. */
    fun appended(before: Int, after: Int): IntRange = if (this == NEWEST_FIRST) 0 until after - before else before until after

    /** The shown place where new rows appear. */
    fun followEdge(size: Int): Int = if (this == NEWEST_FIRST) 0 else size - 1

    fun toggled(): ListOrder = if (this == NEWEST_FIRST) OLDEST_FIRST else NEWEST_FIRST

    companion object {
        val DEFAULT = NEWEST_FIRST

        /** A remembered order by name; anything else is the default. */
        fun of(name: String?): ListOrder = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
