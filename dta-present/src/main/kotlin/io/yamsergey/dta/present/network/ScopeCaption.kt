package io.yamsergey.dta.present.network

import io.yamsergey.dta.protocol.network.EpochCloseReason
import io.yamsergey.dta.protocol.network.FindState
import io.yamsergey.dta.protocol.network.HistoryScope
import io.yamsergey.dta.protocol.network.RunEnd
import io.yamsergey.dta.protocol.network.SavedFiltersApplied
import io.yamsergey.dta.protocol.network.ScopeEpoch
import io.yamsergey.dta.protocol.network.ScopeRun
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Feature 071: the words the Network tab and the CLI say about an answer's scope, as the approved mockup
 * words them (Decisions 2026-11-22): "run", not "session"; the current run by default, the whole history
 * with the archive switch. One wording for every client (constitution VI).
 */
object ScopeCaption {
    /** What a caption offers beside its words (D-106: the archive is no link any more; the source list's "All" is the way). */
    enum class Action { STOP }

    /** [earlier]: the words say earlier runs are in the archive (the tab points to the source "All", the CLI to `--archive`). */
    data class Caption(val text: String, val action: Action? = null, val searching: Boolean = false, val earlier: Boolean = false)

    /**
     * The line under the list. [shown]: rows shown; [filtered]: a filter or search is on; [state]: a
     * streamed search's state (null: none ran, a plain list).
     */
    fun of(scope: HistoryScope?, shown: Int, filtered: Boolean, state: FindState? = null, saved: SavedFiltersApplied? = null, error: String? = null): Caption {
        val on = scope?.archive == true
        val total = scope?.entries ?: 0
        val where = if (on) "in the history" else "in this run"
        val savedOn = saved?.takeIf { !it.ignored && it.words.isNotEmpty() }?.words?.size
        val savedText = savedOn?.let { " · $it saved filter${if (it == 1) "" else "s"} on" }.orEmpty()
        val more = !on && (scope?.outside ?: 0) > 0 && scope?.explicit == null
        val earlier = if (more) " · earlier runs are in the archive$ALL_POINTER" else ""
        if (scope?.explicit != null) {
            return Caption("${grouped(shown)} shown · ${grouped(total)} in the archived part$savedText", searching = state == FindState.SEARCHING || state == FindState.WAITING)
        }
        return when (state) {
            FindState.SEARCHING -> Caption("Searching ${if (on) "all history" else "this run"} · ${grouped(shown)} shown · ${grouped(total)} $where$savedText", Action.STOP, searching = true)
            FindState.WAITING -> Caption("Waiting for another search to end · ${grouped(shown)} shown · ${grouped(total)} $where$savedText", Action.STOP, searching = true)
            FindState.STOPPED -> Caption("Search stopped · ${grouped(shown)} shown · ${grouped(total)} $where$savedText", earlier = false)
            FindState.FAILED -> Caption("Search failed${error?.let { ": $it" }.orEmpty()} · ${grouped(shown)} shown$savedText")
            else -> when {
                !filtered && savedOn == null -> Caption("${grouped(total)} ${if ((total).toLong() == 1L) "entry" else "entries"} $where$earlier", earlier = more)
                else -> Caption("${grouped(shown)} shown · ${grouped(total)} $where$earlier$savedText", earlier = more)
            }
        }
    }

    /** The link's words for [action]. */
    fun label(action: Action): String = when (action) {
        Action.STOP -> "Stop"
    }

    /** Board 5: the line's hint when `run:all` is in it while the switch is off, and its link. */
    const val RUN_ALL_HINT = io.yamsergey.dta.protocol.network.NetworkQuery.RUN_ALL_HINT
    /** What the caption says after "earlier runs are in the archive": where the tab shows them (the source list). */
    const val ALL_POINTER = " (source: All)"
    /** Board 5 in the tab (D-106): no link; the source list's "All" widens it. */
    const val RUN_ALL_HINT_TAB = "run:all reads only this run. Earlier runs: source All."

    /** Board 4: the app started again; [previous] is the run that ended. */
    fun restarted(current: ScopeRun, previous: ScopeRun?, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append("The app restarted")
        current.startedMs?.let { append(" at ${time(it, zone)}") }
        append(".")
        if (previous != null) {
            append(" Previous run: ${grouped(previous.entries)} entr${if (previous.entries == 1L) "y" else "ies"}")
            previous.endedMs?.let { append(", ended ${time(it, zone)}") }
            previous.ended?.let { append(" (${end(it)})") }
            append(".")
        }
    }

    /** Board 3: the separator above an earlier run's rows. */
    fun earlierRun(run: ScopeRun, zone: ZoneId = ZoneId.systemDefault()): String =
        "Earlier run" + (run.startedMs?.let { " · started ${day(it, zone)}" } ?: "") + " · ${grouped(run.entries)} ${if ((run.entries).toLong() == 1L) "entry" else "entries"}"

    /** Board 3: the separator above an archived epoch's rows. */
    fun archived(epoch: ScopeEpoch, zone: ZoneId = ZoneId.systemDefault()): String =
        "Archived history" + (epoch.closedMs?.let { " · ${reason(epoch.reason)} ${day(it, zone)}" } ?: "") + " · ${grouped(epoch.entries)} ${if ((epoch.entries).toLong() == 1L) "entry" else "entries"}"

    /** The CLI's caption: the tab's words, with the switch named where the tab offers a link. */
    fun cli(caption: Caption): String =
        if (caption.earlier) caption.text.replace(ALL_POINTER, "") + " (--archive includes them)" else caption.text

    private fun end(e: RunEnd) = when (e) { RunEnd.PROCESS_ENDED -> "process-ended" }

    private fun reason(r: EpochCloseReason?): String = when (r) {
        EpochCloseReason.CLEARED -> "cleared"
        null -> "closed"
        else -> r.name.lowercase().replace('_', ' ')
    }

    private fun time(ms: Long, zone: ZoneId) = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH).format(Instant.ofEpochMilli(ms).atZone(zone))
    private fun day(ms: Long, zone: ZoneId) = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH).format(Instant.ofEpochMilli(ms).atZone(zone))

    /** 125690 as "125 690", as the tab writes counts. */
    fun grouped(n: Long): String = n.toString().reversed().chunked(3).joinToString(" ").reversed()
    fun grouped(n: Int): String = grouped(n.toLong())
}
