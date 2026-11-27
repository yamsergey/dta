package io.yamsergey.dta.protocol.data

/**
 * Feature 074 (2026-11-27 decision): DTA's own classes (the sidekick, the shared protocol, the relocated Rhino, the shim and the
 * state API: the packages below) are not the user's: left out of the rows and totals, and said in one line so nothing is hidden
 * silently. A class of the app's own namespaces is never left out, whatever its package (the test apps live under io.yamsergey.dta.*).
 * What DTA holds of the app is read from the app's classes (instances, fields, referrers), not from these rows.
 */
object HeapDta {
    val PACKAGES = listOf(
        "io.yamsergey.dta.sidekick.", "io.yamsergey.dta.protocol.", "io.yamsergey.dta.vendor.", "io.yamsergey.dta.shim.", "io.yamsergey.dta.state.",
    )

    class Split(val kept: List<HeapClassRow>, val leftOut: HeapTotals)

    fun isDta(row: HeapClassRow): Boolean {
        if (row.origin == HeapOrigin.APP) return false
        var element = row.className
        while (element.endsWith("[]")) element = element.removeSuffix("[]")
        return PACKAGES.any { element.startsWith(it) }
    }

    fun split(rows: List<HeapClassRow>): Split {
        // not left out yet
        return Split(rows, HeapTotals(0, 0))
    }

    /** The one line of the answer; null when nothing of DTA was in the walk. */
    fun line(leftOut: HeapTotals): String? =
        if (leftOut.objects == 0L) null else "${leftOut.objects} objects of DTA itself left out (${leftOut.bytes} bytes)"
}
