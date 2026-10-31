package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 011 T003: the archive's wire shapes (contracts/archive-ops.md). */
class ArchiveModelTest {
    @Test
    fun `a scope names exactly one thing`() {
        ArchiveScope(epoch = "e1"); ArchiveScope(version = "1.4.0"); ArchiveScope(all = true); ArchiveScope(fromMs = 1, toMs = 2); ArchiveScope(toMs = 2)
        assertFailsWith<IllegalArgumentException> { ArchiveScope() }
        assertFailsWith<IllegalArgumentException> { ArchiveScope(epoch = "e1", all = true) }
        assertFailsWith<IllegalArgumentException> { ArchiveScope(all = false) }
    }

    @Test
    fun `a query without a scope is live only, and one with a scope reads back`() {
        assertNull(NetworkQuery.parse(JsonObject(emptyMap())).archive)
        val q = NetworkQuery(archive = ArchiveScope(version = "1.4.0"))
        assertEquals(q, NetworkQuery.parse(q.toJson()))
    }

    @Test
    fun `reasons and marks have their contract names`() {
        assertEquals("\"new-build\"", DtaJson.encodeToString(EpochCloseReason.serializer(), EpochCloseReason.NEW_BUILD))
        val mark = ArchivedMark("e1", EpochCloseReason.CLEARED, 5, "1.4.0")
        assertEquals(mark, DtaJson.decodeFromString(ArchivedMark.serializer(), DtaJson.encodeToString(ArchivedMark.serializer(), mark)))
        val coverage = DtaJson.encodeToJsonElement(Coverage.serializer(), Coverage(searched = 1, stored = 1, persisted = true, archivedEpochs = 2)).jsonObject
        assertEquals("2", coverage["archivedEpochs"].toString())
    }

    @Test
    fun `the CLI form of a scope`() {
        assertEquals(ArchiveScope(all = true), ArchiveScope.parse("all"))
        assertEquals(ArchiveScope(version = "1.4.0"), ArchiveScope.parse("version:1.4.0"))
        assertEquals(ArchiveScope(fromMs = 10, toMs = 20), ArchiveScope.parse("10..20"))
        assertEquals(ArchiveScope(fromMs = 10), ArchiveScope.parse("10.."))
        assertEquals(ArchiveScope(epoch = "5f0c-a1"), ArchiveScope.parse("5f0c-a1"))
        assertFailsWith<IllegalArgumentException> { ArchiveScope.parse("x..y") }
        assertFailsWith<IllegalArgumentException> { ArchiveScope.parse(" ") }
    }
}
