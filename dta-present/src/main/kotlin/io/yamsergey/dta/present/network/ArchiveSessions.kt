package io.yamsergey.dta.present.network

import io.yamsergey.dta.protocol.network.ArchiveListing
import io.yamsergey.dta.protocol.network.EpochCloseReason
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Feature 056: the Network source switch after "Live": the app's archived sessions (closed epochs)
 * newest first, named by when they ran and how they ended, then "All archived". A session of
 * another app version names it.
 */
object ArchiveSessions {
    const val MAX = 20
    const val ALL = "all"

    /** [scope] is the archive scope a choice sets (an epoch id, or [ALL]). */
    data class Item(val scope: String, val label: String, val meta: String, val enabled: Boolean = true)

    fun of(listing: ArchiveListing, serial: String?, app: String?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<Item> {
        val installations = listing.devices.filter { it.serial == serial }.flatMap { it.apps }.filter { it.packageName == app }.flatMap { it.installations }
        val newest = installations.maxWithOrNull(compareBy({ it.lastUpdateMs ?: Long.MIN_VALUE }, { it.id }))
        val closed = installations.flatMap { i -> i.epochs.filter { !it.live }.map { i to it } }.sortedByDescending { it.second.openedMs }
        if (closed.isEmpty()) return listOf(Item(ALL, "No archived history", "", enabled = false))
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val sessions = closed.take(MAX).map { (installation, e) ->
            val meta = listOfNotNull(
                entries(e.entries),
                reason(e.reason),
                installation.versionName?.takeIf { it != newest?.versionName }?.let { "v$it" },
            ).joinToString(" · ")
            Item(e.id, span(e.openedMs, e.closedMs!!, today, zone), meta)
        }
        val total = closed.sumOf { it.second.entries }
        return sessions + Item(ALL, "All archived", "${closed.size} session${if (closed.size == 1) "" else "s"} · ${entries(total)}")
    }

    private fun entries(n: Int) = "%,d".format(Locale.US, n) + if (n == 1) " entry" else " entries"

    private fun reason(r: EpochCloseReason?) = when (r) {
        EpochCloseReason.CLEARED -> "cleared"
        EpochCloseReason.DATA_WIPED -> "data wiped"
        EpochCloseReason.REINSTALLED -> "reinstalled"
        EpochCloseReason.NEW_BUILD -> "new build"
        EpochCloseReason.ROLLED_BACK -> "rolled back"
        EpochCloseReason.UNKNOWN, null -> null
    }

    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    private fun span(fromMs: Long, toMs: Long, today: LocalDate, zone: ZoneId): String {
        val from = Instant.ofEpochMilli(fromMs).atZone(zone)
        val to = Instant.ofEpochMilli(toMs).atZone(zone)
        fun day(d: LocalDate) = when (d) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> DAY.format(d)
        }
        return if (from.toLocalDate() == to.toLocalDate()) "${day(from.toLocalDate())} ${TIME.format(from)}–${TIME.format(to)}"
        else "${day(from.toLocalDate())} ${TIME.format(from)} – ${day(to.toLocalDate())} ${TIME.format(to)}"
    }
}
