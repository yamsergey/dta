package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.ops.Catalog
import io.yamsergey.dta.protocol.ops.ParamType
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 071 (FR-002/003/004, research R3): the archive switch, the explicit scope and the stated scope. */
class HistoryScopeTest {
    @Test
    fun `the switch is off by default and a boolean, the explicit scope its own parameter`() {
        assertFalse(NetworkQuery.parse(null).archive)
        val on = NetworkQuery.parse(JsonObject(mapOf("archive" to JsonPrimitive(true))))
        assertTrue(on.archive)
        assertNull(on.archiveScope)
        val explicit = NetworkQuery.parse(JsonObject(mapOf("archiveScope" to DtaJson.encodeToJsonElement(ArchiveScope.serializer(), ArchiveScope(all = true)))))
        assertEquals(ArchiveScope(all = true), explicit.archiveScope)
        assertFalse(explicit.archive)
    }

    @Test
    fun `run all with the switch off widens nothing and is said`() {
        assertTrue(NetworkQuery(q = "run:all token").runAllIgnored())
        assertFalse(NetworkQuery(q = "run:all token", archive = true).runAllIgnored())
        assertFalse(NetworkQuery(q = "run:current token").runAllIgnored())
        assertFalse(NetworkQuery(q = "token").runAllIgnored())
        assertEquals("run:all reads only this run while Archive is off.", NetworkQuery.RUN_ALL_HINT)
    }

    @Test
    fun `list, search, export and links take the switch and the explicit scope`() {
        for (op in listOf("network.list", "network.search", "network.export", "links.list")) {
            val params = Catalog[op].params.associateBy { it.name }
            assertEquals(ParamType.BOOLEAN, params.getValue("archive").type, op)
            assertEquals(ParamType.STRING, params.getValue("archiveScope").type, op)
        }
    }

    @Test
    fun `a page states its scope`() {
        val scope = HistoryScope(archive = false, entries = 48_210, run = ScopeRun("r1", 48_210, startedMs = 5), outside = 1_203_550)
        val json = DtaJson.encodeToString(HistoryScope.serializer(), scope)
        assertEquals(scope, DtaJson.decodeFromString(HistoryScope.serializer(), json))
        assertTrue("\"outside\":1203550" in json, json)
    }
}
