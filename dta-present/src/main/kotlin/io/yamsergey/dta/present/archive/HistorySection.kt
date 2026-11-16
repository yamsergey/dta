package io.yamsergey.dta.present.archive

import io.yamsergey.dta.present.Units
import io.yamsergey.dta.protocol.network.ArchiveListing
import io.yamsergey.dta.protocol.network.DeviceHistory
import io.yamsergey.dta.protocol.network.HistorySummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun n(value: Number): String = String.format(java.util.Locale.ROOT, "%,d", value)

/** One installation's epoch in the history section; [epochId] is what Show and Delete… act on (closed only). */
data class HistoryRow(
    val installation: String,
    val installationId: Long,
    val period: String,
    val state: String,
    val entries: String,
    val size: String,
    val epochId: String,
    val live: Boolean,
)

/** Another app's archive on this computer, with what Delete… removes. */
data class OtherApp(val packageName: String, val what: String)

/**
 * Feature 066 (mockup "066 Network history"): the Daemon tab's Network history of the selected
 * app as text the plugin lays out — the device's part, this computer's, its epochs, other apps.
 */
data class HistorySection(
    /** A warning over the device line when the history is over its warning size. */
    val banner: String?,
    val device: String,
    val deviceSub: String?,
    /** False when the device's part is unknown: its actions are disabled. */
    val deviceKnown: Boolean,
    val host: String,
    val rows: List<HistoryRow>,
    val others: List<OtherApp>,
) {
    companion object {
        fun of(summary: HistorySummary, listing: ArchiveListing?, deviceKey: String?, packageName: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): HistorySection {
            val day = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(zone)
            fun t(ms: Long) = day.format(Instant.ofEpochMilli(ms))
            val d = summary.device
            val device = d?.let { dev ->
                listOfNotNull("${n(dev.entries)} entries", Units.bytes(dev.bytes), dev.earliestMs?.let { e -> "${t(e)} – ${dev.latestMs?.let(::t) ?: "now"}" }).joinToString(" · ")
            } ?: "Unknown: ${summary.deviceUnknown ?: "the device did not say"}."
            val deviceSub = d?.let { dev ->
                val share = dev.archivedEntries?.takeIf { dev.entries > 0 }?.let { "${it * 100 / dev.entries}% already on this computer" }
                listOfNotNull(share, "keeps ${Units.bytes(dev.keepBytes)}, warns above ${Units.bytes(dev.warnBytes)}").joinToString(" · ")
            }
            val a = summary.archive
            val host = listOfNotNull(
                "${n(a.entries)} entries", Units.bytes(a.bytes), a.earliestMs?.let { "since ${t(it)}" },
                summary.hostOnly?.takeIf { it > 0 }?.let { "${n(it)} of them no longer on the device" },
            ).joinToString(" · ")
            val rows = a.installations.flatMap { inst ->
                val version = listOfNotNull(inst.versionName, inst.versionCode?.let { "($it)" }).joinToString(" ").ifEmpty { "unknown version" }
                inst.epochs.map { e ->
                    HistoryRow(version, inst.id, "${t(e.openedMs)} → ${e.closedMs?.let(::t) ?: "now"}",
                        if (e.closedMs == null) "live" else e.reason?.name?.lowercase()?.replace('_', ' ') ?: "archived",
                        n(e.entries), Units.bytes(e.bytes), e.id, e.closedMs == null)
                }
            }
            val others = listing?.devices.orEmpty().filter { deviceKey == null || it.key == deviceKey }.flatMap { it.apps }
                .filter { it.packageName != packageName }.map { app ->
                    val epochs = app.installations.flatMap { it.epochs }
                    val n = app.installations.size
                    OtherApp(app.packageName, "$n installation${if (n == 1) "" else "s"} · ${n(epochs.sumOf { it.entries })} entries · ${Units.bytes(epochs.sumOf { it.bytes })}")
                }
            return HistorySection(d?.takeIf { it.overWarning }?.let { banner(it, nowMs, ::t) }, device, deviceSub, d != null, host, rows, others)
        }

        private fun banner(d: DeviceHistory, nowMs: Long, t: (Long) -> String): String {
            val reason = when (d.trimHeld) {
                "no-archive" -> "no DTA client has archived it yet"
                "stale-archive" -> "nothing archived since ${d.lastArchivedMs?.let(t) ?: "a while"}"
                "unarchived" -> "what is over the budget is not archived yet"
                else -> "it is being archived now; the device trims to ${Units.bytes(d.keepBytes)} as it goes"
            }
            return "The device keeps ${Units.bytes(d.bytes)} of this app's network history, over its ${Units.bytes(d.warnBytes)} warning size: $reason. " +
                "Keep a DTA client connected so it is archived and trimmed, clear it, or raise the budget."
        }
    }
}

/**
 * Feature 066 (FR-015): one notification per app and growth episode — when the device history
 * goes over its warning size, and again only after it was back under it.
 */
class HistoryEpisodes {
    private val over = HashMap<Pair<String, String>, Boolean>()

    /** True when this is the crossing to notify about. Unknown sizes change nothing. */
    fun crossed(device: String, packageName: String, deviceBytes: Long?, warnBytes: Long?): Boolean {
        if (deviceBytes == null || warnBytes == null) return false
        val now = deviceBytes > warnBytes
        val was = over.put(device to packageName, now) ?: false
        return now && !was
    }
}
