package io.yamsergey.dta.present.archive

import io.yamsergey.dta.present.Units
import io.yamsergey.dta.present.network.arr
import io.yamsergey.dta.present.network.long
import io.yamsergey.dta.present.network.o
import io.yamsergey.dta.present.network.str
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One row of the archive browser; [epoch], [installation] and [packageName] say what it deletes or shows. */
data class ArchiveRow(
    val depth: Int,
    val text: String,
    val note: String? = null,
    val epoch: String? = null,
    val installation: Long? = null,
    val packageName: String? = null,
    val live: Boolean = false,
)

/**
 * The host's network archive as rows (feature 011, contracts/archive-ops.md): device › app ›
 * install › epochs, each epoch with its dates, why it closed, its size, and what never reached
 * this computer.
 */
object ArchiveTree {
    const val EMPTY = "Nothing archived yet: when a device clears its history, this computer keeps it here."

    fun rows(listing: JsonObject, zone: ZoneId = ZoneId.systemDefault()): List<ArchiveRow> = buildList {
        val day = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        listing.arr("devices").forEach { d ->
            val device = d.o
            add(ArchiveRow(0, listOfNotNull(device.str("model"), device.str("serial")).joinToString(" · ").ifEmpty { device.str("key") ?: "device" }))
            device.arr("apps").forEach { a ->
                val pkg = a.o.str("packageName")
                add(ArchiveRow(1, pkg ?: "app", packageName = pkg))
                a.o.arr("installations").forEach { i ->
                    val inst = i.o
                    val version = listOfNotNull(inst.str("versionName"), inst.long("versionCode")?.let { "($it)" }).joinToString(" ").ifEmpty { "unknown version" }
                    add(ArchiveRow(2, version, installation = inst.long("id"), packageName = pkg))
                    inst.arr("epochs").forEach { e ->
                        val ep = e.o
                        val opened = Instant.ofEpochMilli(ep.long("openedMs") ?: 0)
                        val closed = ep.long("closedMs")?.let(Instant::ofEpochMilli)
                        val state = if (closed == null) "live" else ep.str("reason")?.replace('-', ' ') ?: "archived"
                        val span = day.format(opened) + " → " + (closed?.let { if (day.format(it).take(10) == day.format(opened).take(10)) time.format(it) else day.format(it) } ?: "now")
                        val text = "$span · $state · ${ep.long("entries") ?: 0} entries · ${Units.bytes(ep.long("bytes") ?: 0)}"
                        val note = ep.long("neverReceived")?.takeIf { it > 0 }?.let { "$it entries never reached this computer" }
                        add(ArchiveRow(3, text, note, epoch = ep.str("id"), packageName = pkg, live = closed == null))
                    }
                }
            }
        }
    }
}
