package io.yamsergey.dta.protocol.data

import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/** Feature 074 FR-012: `notKnown` is in every JSON answer, DtaJson does not encode defaults. */
class HeapNotKnownJsonTest {
    /** The JSON text an MCP or `--json` client reads says what is not known: who keeps objects alive, retained size (CLIENT-012). */
    private fun has(json: String) = Json.parseToJsonElement(json).jsonObject["notKnown"]?.jsonArray?.isNotEmpty() == true &&
        "retained size" in json && "alive" in json

    @Test fun snapshotAnswerCarriesNotKnown() {
        val a = HeapSnapshotAnswer(HeapSnapshotSummary("s1", null, "r", 0, 1, false, HeapTotals(0, 0), 0, emptyList()), 1)
        assertTrue(has(DtaJson.encodeToString(a)))
    }

    @Test fun listAnswerCarriesNotKnown() = assertTrue(has(DtaJson.encodeToString(HeapListAnswer())))

    @Test fun compareAnswerCarriesNotKnown() {
        val c = HeapComparison("a", "b", "r", emptyList(), HeapFilter(), HeapLeftOut(), 0, 0, 0)
        assertTrue(has(DtaJson.encodeToString(HeapCompareAnswer(c, ""))))
    }

    @Test fun instancesAnswerCarriesNotKnown() {
        val a = HeapInstancesAnswer("C", 0, 0, emptyList(), "n")
        assertTrue(has(DtaJson.encodeToString(a)))
    }
}
