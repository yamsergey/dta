package io.yamsergey.dta.present.archive

import io.yamsergey.dta.protocol.network.ArchiveApp
import io.yamsergey.dta.protocol.network.ArchiveDevice
import io.yamsergey.dta.protocol.network.ArchiveEpoch
import io.yamsergey.dta.protocol.network.ArchiveHistory
import io.yamsergey.dta.protocol.network.ArchiveInstallation
import io.yamsergey.dta.protocol.network.ArchiveListing
import io.yamsergey.dta.protocol.network.DeviceHistory
import io.yamsergey.dta.protocol.network.EpochCloseReason
import io.yamsergey.dta.protocol.network.HistorySummary
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 066 T039/T037: the Daemon tab's history section and the notification episodes. */
class HistorySectionTest {
    private val mb = 1024L * 1024
    private val day = 86_400_000L
    private val install = ArchiveInstallation(3, "1.4.2", 42, epochs = listOf(
        ArchiveEpoch("e1", openedMs = 0, closedMs = day, reason = EpochCloseReason.CLEARED, entries = 1930, bytes = 410 * mb),
        ArchiveEpoch("e2", openedMs = day, entries = 5100, bytes = 820 * mb),
    ))
    private val listing = ArchiveListing(listOf(ArchiveDevice("dev1", apps = listOf(
        ArchiveApp("io.example", listOf(install)),
        ArchiveApp("io.weather", listOf(ArchiveInstallation(4, "2.0", 2, epochs = listOf(ArchiveEpoch("w1", 0, day, EpochCloseReason.REINSTALLED, 3410, 380 * mb))))),
    ))), 0)

    private fun section(device: DeviceHistory?, unknown: String? = null) = HistorySection.of(
        HistorySummary(device, unknown, ArchiveHistory(7030, 1230 * mb, 0, 2 * day, listOf(install)), hostOnly = 3896),
        listing, "dev1", "io.example", nowMs = 2 * day, zone = ZoneOffset.UTC,
    )

    @Test
    fun `the device line, this computer's, the epochs and other apps`() {
        val s = section(DeviceHistory(1204, 212 * mb, 0, day, archivedEntries = 1156, keepBytes = 256 * mb, warnBytes = 512 * mb))
        assertNull(s.banner)
        assertEquals("1,204 entries · 212.0 MB · 1 Jan 00:00 – 2 Jan 00:00", s.device)
        assertEquals("96% already on this computer · keeps 256.0 MB, warns above 512.0 MB", s.deviceSub)
        assertEquals("7,030 entries · 1.2 GB · since 1 Jan 00:00 · 3,896 of them no longer on the device", s.host)
        assertEquals(listOf("cleared", "live"), s.rows.map { it.state })
        assertFalse(s.rows[0].live)
        assertEquals("e1", s.rows[0].epochId)
        assertEquals(listOf(OtherApp("io.weather", "1 installation · 3,410 entries · 380.0 MB")), s.others)
    }

    @Test
    fun `over the warning size a banner says why and what to do`() {
        val s = section(DeviceHistory(70712, 1126 * mb, 0, day, keepBytes = 256 * mb, warnBytes = 512 * mb, trimHeld = "no-archive", overWarning = true))
        assertTrue(s.banner!!.startsWith("The device keeps 1.1 GB of this app's network history, over its 512.0 MB warning size: no DTA client has archived it yet."), s.banner)
    }

    @Test
    fun `an unknown device part is said, never zero, and its actions are off`() {
        val s = section(null, unknown = "the app is not running")
        assertEquals("Unknown: the app is not running.", s.device)
        assertFalse(s.deviceKnown)
    }

    @Test
    fun `one notification per growth episode, per device and app`() {
        val e = HistoryEpisodes()
        assertFalse(e.crossed("d", "a", 100, 200))
        assertTrue(e.crossed("d", "a", 300, 200))
        assertFalse(e.crossed("d", "a", 400, 200), "still over: no second notification")
        assertTrue(e.crossed("d", "b", 300, 200), "another app is its own episode")
        assertFalse(e.crossed("d", "a", 150, 200))
        assertTrue(e.crossed("d", "a", 250, 200), "grew again")
        assertFalse(e.crossed("d", "a", null, 200), "unknown sizes change nothing")
    }
}
