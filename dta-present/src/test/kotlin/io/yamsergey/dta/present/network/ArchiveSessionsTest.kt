package io.yamsergey.dta.present.network

import io.yamsergey.dta.protocol.network.ArchiveApp
import io.yamsergey.dta.protocol.network.ArchiveDevice
import io.yamsergey.dta.protocol.network.ArchiveEpoch
import io.yamsergey.dta.protocol.network.ArchiveInstallation
import io.yamsergey.dta.protocol.network.ArchiveListing
import io.yamsergey.dta.protocol.network.EpochCloseReason
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** Feature 056: the Network source switch lists the app's archived sessions by time. */
class ArchiveSessionsTest {
    private val zone = ZoneId.of("UTC")
    private fun at(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 11, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(10, 20, 0)

    private fun epoch(id: String, from: Long, to: Long?, n: Int, r: EpochCloseReason?) = ArchiveEpoch(id, from, to, r, n, 1000)

    private val listing = ArchiveListing(listOf(
        ArchiveDevice("k1", "emulator-5554", null, listOf(
            ArchiveApp("io.example.shop", listOf(
                ArchiveInstallation(1, "1.2", 12, at(1, 9, 0), listOf(epoch("e1", at(8, 9, 12), at(8, 9, 30), 40, EpochCloseReason.REINSTALLED))),
                ArchiveInstallation(2, "1.3", 13, at(9, 9, 0), listOf(
                    epoch("e2", at(9, 18, 10), at(9, 18, 42), 88, EpochCloseReason.NEW_BUILD),
                    epoch("e3", at(10, 11, 40), at(10, 12, 15), 1204, EpochCloseReason.CLEARED),
                    epoch("e4", at(10, 14, 2), null, 312, null),
                )),
            )),
            ArchiveApp("io.example.other", listOf(ArchiveInstallation(3, "9", 9, null, listOf(epoch("x", at(10, 1, 0), at(10, 2, 0), 5, EpochCloseReason.CLEARED))))),
        )),
        ArchiveDevice("k2", "other", null, emptyList()),
    ), 0)

    @Test
    fun `sessions of the app newest first, by when and how they ended, then all`() {
        val items = ArchiveSessions.of(listing, "emulator-5554", "io.example.shop", now, zone)
        assertEquals(listOf("e3", "e2", "e1", "all"), items.map { it.scope })
        assertEquals("Today 11:40–12:15", items[0].label)
        assertEquals("1,204 entries · cleared", items[0].meta)
        assertEquals("Yesterday 18:10–18:42", items[1].label)
        assertEquals("88 entries · new build", items[1].meta)
        assertEquals("Nov 8 09:12–09:30", items[2].label)
        assertEquals("40 entries · reinstalled · v1.2", items[2].meta, "another version is named; the current one is not")
        assertEquals("All archived" to "3 sessions · 1,332 entries", items[3].label to items[3].meta)
    }

    @Test
    fun `nothing archived says so and cannot be chosen`() {
        val items = ArchiveSessions.of(listing, "emulator-5554", "io.example.none", now, zone)
        assertEquals(listOf("No archived history"), items.map { it.label })
        assertEquals(false, items.single().enabled)
    }

    @Test
    fun `at most the newest twenty`() {
        val many = ArchiveListing(listOf(ArchiveDevice("k", "s", null, listOf(ArchiveApp("a", listOf(ArchiveInstallation(1, "1", 1, null,
            (1..30).map { epoch("e$it", at(1, 0, it), at(1, 0, it) + 1000, 1, EpochCloseReason.CLEARED) })))))), 0)
        val items = ArchiveSessions.of(many, "s", "a", now, zone)
        assertEquals(21, items.size)
        assertEquals("e30", items.first().scope)
        assertEquals("30 sessions · 30 entries", items.last().meta)
    }
}
