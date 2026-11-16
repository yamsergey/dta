package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

/** 066 T002: the device history's wire shapes (data-model.md). */
class HistoryModelTest {
    private inline fun <reified T> roundTrip(value: T, serializer: kotlinx.serialization.KSerializer<T>) =
        assertEquals(value, DtaJson.decodeFromString(serializer, DtaJson.encodeToString(serializer, value)))

    @Test
    fun `the acknowledgement, settings and summary read back`() {
        roundTrip(ArchiveAck("j1", "h1", "12:340", listOf("r1-2:88"), atMs = 5), ArchiveAck.serializer())
        roundTrip(ArchiveAckResult(accepted = false, reason = "reading", trimPending = false), ArchiveAckResult.serializer())
        roundTrip(HistorySettings(keepBytes = 1L shl 28, warnBytes = 1L shl 29, deviceBytes = 7, trimHeld = "no-archive", lastArchivedMs = 9), HistorySettings.serializer())
        roundTrip(
            HistorySummary(
                device = DeviceHistory(10, 2048, 1, 2, archivedEntries = 8, keepBytes = 100, warnBytes = 200, trimHeld = null, lastArchivedMs = 3, overWarning = false),
                deviceUnknown = null,
                archive = ArchiveHistory(entries = 20, bytes = 4096, earliestMs = 1, latestMs = 9, installations = emptyList()),
            ),
            HistorySummary.serializer(),
        )
    }

    @Test
    fun `an older peer's coverage and clear result decode with the new fields absent`() {
        val coverage = DtaJson.decodeFromString(Coverage.serializer(), """{"searched":1,"stored":1,"persisted":true}""")
        listOf(coverage.deviceBytes, coverage.keepBytes, coverage.warnBytes, coverage.trimHeld, coverage.lastArchivedMs,
            coverage.archivedOnly, coverage.archivedByOther, coverage.hostOnly, coverage.archiveHeld).forEach { assertNull(it) }
        assertNull(DtaJson.decodeFromString(ClearResult.serializer(), """{"clearedEntries":3,"clearedAtMs":4}""").notKept)
        roundTrip(ClearResult(3, 4, NotKept(2, 1, "the copy did not finish in 20 s")), ClearResult.serializer())
    }

    @Test
    fun `defaults and bounds of the settings`() {
        assertEquals(256L * 1024 * 1024, HistoryDefaults.KEEP_BYTES)
        assertEquals(512L * 1024 * 1024, HistoryDefaults.WARN_BYTES)
        assertEquals(16L * 1024 * 1024, HistoryDefaults.MIN_BYTES)
        assertNull(HistoryDefaults.problem(HistoryDefaults.KEEP_BYTES, HistoryDefaults.WARN_BYTES))
        assertEquals("the budget is at least 16 MB", HistoryDefaults.problem(1024, HistoryDefaults.WARN_BYTES))
        assertEquals("the warning size cannot be under the budget (256 MB)", HistoryDefaults.problem(HistoryDefaults.KEEP_BYTES, 20L * 1024 * 1024))
    }

    @Test
    fun `sizes as people write them`() {
        assertEquals(128L * 1024 * 1024, HistoryDefaults.parseSize("128MB"))
        assertEquals(128L * 1024 * 1024, HistoryDefaults.parseSize("128 mb"))
        assertEquals((1.5 * 1024 * 1024 * 1024).toLong(), HistoryDefaults.parseSize("1.5GB"))
        assertEquals(1000L, HistoryDefaults.parseSize("1000"))
        assertEquals(2048L, HistoryDefaults.parseSize("2KB"))
        assertFailsWith<IllegalArgumentException> { HistoryDefaults.parseSize("lots") }
    }
}
