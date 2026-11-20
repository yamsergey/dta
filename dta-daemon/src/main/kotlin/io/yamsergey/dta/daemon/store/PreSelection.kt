package io.yamsergey.dta.daemon.store

import io.yamsergey.dta.protocol.network.EntryKind
import io.yamsergey.dta.protocol.network.NetworkFilter

/**
 * Feature 069 (research R2): the rows of `entry` a filter may keep, as SQL over the columns written
 * with each entry. It only leaves out rows the filter certainly drops; every row it lets through is
 * still decided by the filter itself (one meaning, constitution VI). What it cannot say from columns
 * (exclusions, bodies, terms) it lets through.
 */
class PreSelection private constructor(val sql: String, val args: List<Any?>) {
    companion object {
        fun of(filter: NetworkFilter?): PreSelection? {
            filter ?: return null
            val parts = ArrayList<String>()
            val args = ArrayList<Any?>()
            filter.kinds?.let { kinds ->
                parts += "kind IN (${kinds.joinToString(",") { "?" }})"
                args.addAll(kinds.map(::kindName))
            }
            filter.sinceMs?.let { parts += "time_ms >= ?"; args.add(it) }
            filter.untilMs?.let { parts += "time_ms <= ?"; args.add(it) }
            filter.method?.let { parts += "kind = 'http' AND method = ? COLLATE NOCASE"; args.add(it) }
            filter.status?.let { status -> status(status)?.let { (sql, a) -> parts += sql; args.addAll(a) } }
            filter.host?.let { wanted ->
                // A socket message's host is its connection's, not a column of its own: it is let through.
                val w = wanted.lowercase()
                if (w.startsWith("*.")) {
                    val suffix = w.drop(1)
                    parts += "(kind = 'ws-message' OR substr(host, -?) = ?)"
                    args.add(suffix.length); args.add(suffix)
                } else {
                    parts += "(kind = 'ws-message' OR host = ?)"
                    args.add(w)
                }
            }
            return if (parts.isEmpty()) null else PreSelection(parts.joinToString(" AND ") { "($it)" }, args)
        }

        /** As NetworkFilterEval reads a status: HTTP codes and classes, HTTP and socket states, socket close codes. */
        private fun status(status: String): Pair<String, List<Any?>>? = when {
            status == "failed" || status == "pending" -> "kind IN ('http','ws-connection')" to emptyList()
            status == "open" || status == "closed" || status == "connecting" -> "kind = 'ws-connection'" to emptyList()
            status.length == 3 && status.endsWith("xx") && status[0].isDigit() ->
                "kind = 'http' AND status BETWEEN ? AND ?" to listOf(status[0].digitToInt() * 100, status[0].digitToInt() * 100 + 99)
            status.length == 3 && status.toIntOrNull() != null -> "kind = 'http' AND status = ?" to listOf(status.toInt())
            status.length == 4 && status.toIntOrNull() != null -> "kind = 'ws-connection'" to emptyList()
            else -> "0" to emptyList()
        }

        fun kindName(kind: EntryKind) = when (kind) {
            EntryKind.HTTP -> "http"
            EntryKind.WS_CONNECTION -> "ws-connection"
            EntryKind.WS_MESSAGE -> "ws-message"
            EntryKind.LINK -> "link"
        }
    }
}
