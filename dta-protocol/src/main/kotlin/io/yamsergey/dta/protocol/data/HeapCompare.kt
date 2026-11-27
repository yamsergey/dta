package io.yamsergey.dta.protocol.data

import kotlin.math.abs

/** Feature 074: class loader kind as the sidekick can tell it. */
enum class LoaderKind { BOOT, APP, OTHER }

/**
 * The pure part of the origin rule (research R5): platform = boot loader; app = app loader and a package under one
 * of the app's namespaces; library = app loader otherwise; unknown when the namespaces (or the loader) are not
 * known — never guessed.
 */
fun classOrigin(className: String, loader: LoaderKind, namespaces: List<String>?): HeapOrigin {
    if (loader == LoaderKind.BOOT) return HeapOrigin.PLATFORM
    if (loader != LoaderKind.APP) return HeapOrigin.UNKNOWN
    if (namespaces.isNullOrEmpty()) return HeapOrigin.UNKNOWN
    val element = className.removeSuffix("[]").let { var s = it; while (s.endsWith("[]")) s = s.removeSuffix("[]"); s }
    val inApp = namespaces.any { ns -> element == ns || element.startsWith("$ns.") }
    return if (inApp) HeapOrigin.APP else HeapOrigin.LIBRARY
}

/** Compares two snapshots of one run; sorted by |Δbytes| then |Δcount|, filtered, and says what it left out. */
fun compareHeap(a: HeapSnapshot, b: HeapSnapshot, filter: HeapFilter = HeapFilter()): HeapCompareResult {
    if (a.run != b.run) {
        return HeapCompareResult.Refused(
            "snapshots ${a.id} (run ${a.run}) and ${b.id} (run ${b.run}) are from different app runs; " +
                "object counts of two processes are not comparable — take both in the same run",
        )
    }
    val ra = a.classes.associateBy { it.className to it.loader }
    val rb = b.classes.associateBy { it.className to it.loader }
    var unchanged = 0
    val changed = ArrayList<HeapComparisonRow>()
    for (key in ra.keys + rb.keys) {
        val x = ra[key]
        val y = rb[key]
        val row = HeapComparisonRow(
            className = key.first,
            loader = key.second,
            origin = (y ?: x)!!.origin,
            countA = x?.count ?: 0, countB = y?.count ?: 0,
            bytesA = x?.bytes ?: 0, bytesB = y?.bytes ?: 0,
            change = when {
                x == null -> HeapChange.APPEARED
                y == null -> HeapChange.GONE
                y.bytes > x.bytes || (y.bytes == x.bytes && y.count > x.count) -> HeapChange.GREW
                else -> HeapChange.SHRANK
            },
        )
        if (x != null && y != null && x.count == y.count && x.bytes == y.bytes) unchanged++ else changed += row
    }
    val sorted = changed.sortedWith(
        compareByDescending<HeapComparisonRow> { abs(it.deltaBytes) }
            .thenByDescending { abs(it.deltaCount) }
            .thenBy { it.className }.thenBy { it.loader ?: "" },
    )
    var byOrigin = 0
    var byPackage = 0
    var leftBytes = 0L
    val kept = ArrayList<HeapComparisonRow>()
    for (r in sorted) {
        when {
            filter.origin != HeapOriginFilter.ALL && r.origin != filter.origin.origin -> { byOrigin++; leftBytes += r.deltaBytes }
            filter.packagePrefix != null && !inPackage(r.className, filter.packagePrefix) -> { byPackage++; leftBytes += r.deltaBytes }
            else -> kept += r
        }
    }
    val top = filter.top.coerceAtLeast(0)
    val shown = kept.take(top)
    val cut = kept.drop(top)
    leftBytes += cut.sumOf { it.deltaBytes }
    return HeapCompareResult.Ok(
        HeapComparison(
            a = a.id, b = b.id, run = a.run, rows = shown, filters = filter,
            leftOut = HeapLeftOut(byOrigin, byPackage, cut.size, leftBytes),
            unchangedClasses = unchanged,
            totalsDeltaObjects = b.totals.objects - a.totals.objects,
            totalsDeltaBytes = b.totals.bytes - a.totals.bytes,
        ),
    )
}

private fun inPackage(className: String, prefix: String): Boolean {
    val p = prefix.removeSuffix(".").removeSuffix("*").removeSuffix(".")
    val n = className.trimEnd('[', ']')
    return n == p || n.startsWith("$p.")
}
