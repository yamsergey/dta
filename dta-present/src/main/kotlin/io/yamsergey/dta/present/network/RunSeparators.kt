package io.yamsergey.dta.present.network

import io.yamsergey.dta.protocol.network.HistoryScope
import java.time.ZoneId

/**
 * Feature 071 (board 3): with the archive switch on, the list's rows of each earlier run and each archived
 * epoch go under a separator naming it; the current run's rows have none.
 */
object RunSeparators {
    /** A separator's place among the rows and its words. */
    data class Separator(val group: String, val text: String)

    /**
     * [rows] oldest to newest, each with its group (an archived epoch's id, else its run id). Returns the
     * rows with separators between groups, in the same order: when [newestFirst] (the list's top is the
     * newest) a group's separator goes after its newest row, so it shows above the group; else before its oldest.
     */
    fun <T> insert(rows: List<T>, groupOf: (T) -> String, scope: HistoryScope?, newestFirst: Boolean, zone: ZoneId = ZoneId.systemDefault(), separator: (Separator) -> T): List<T> {
        if (scope?.archive != true || rows.isEmpty()) return rows
        val current = scope.run?.runId
        fun label(group: String): String? {
            if (group == current) return null
            scope.epochs?.firstOrNull { it.id == group }?.let { return ScopeCaption.archived(it, zone) }
            return scope.runs?.firstOrNull { it.runId == group }?.let { ScopeCaption.earlierRun(it, zone) }
        }
        val out = ArrayList<T>(rows.size + 8)
        for (i in rows.indices) {
            val g = groupOf(rows[i])
            if (!newestFirst && (i == 0 || groupOf(rows[i - 1]) != g)) label(g)?.let { out += separator(Separator(g, it)) }
            out += rows[i]
            if (newestFirst && (i == rows.lastIndex || groupOf(rows[i + 1]) != g)) label(g)?.let { out += separator(Separator(g, it)) }
        }
        return out
    }
}
