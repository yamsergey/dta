package io.yamsergey.dta.mcp

import io.yamsergey.dta.protocol.data.*
import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D-131 through the real tool call (McpTools.invoke runtime_heap_compare), at the contract's bound: 65536 (008 contracts/mcp.md), cut over it. */
class McpHeapCompareBoundTest {
    private fun answer(n: Int): Selected {
        val rows = (1..n).map { HeapComparisonRow("com.example.pkg.Class$it", null, HeapOrigin.PLATFORM, 0, 3, 0, 300L * (n - it + 1), HeapChange.APPEARED) }
        val c = HeapComparison("empty", "b", "r1", rows, HeapFilter(HeapOriginFilter.ALL, null, 100000), HeapLeftOut(), 0, 3L * n, 0)
        return Selected(null, DtaJson.encodeToJsonElement(HeapCompareAnswer.serializer(), HeapCompareAnswer(c, c.leftOut.describe())))
    }

    private fun invoke(n: Int) = runBlocking { McpTools(call = { _, _ -> answer(n) }).invoke("runtime_heap_compare", JsonObject(emptyMap())) }

    @Test fun `an answer within the bound passes whole`() {
        val out = invoke(100)
        assertFalse(out.isError, out.text)
        assertTrue(out.text.length <= 65_536)
        assertEquals(100, DtaJson.parseToJsonElement(out.text).jsonObject["result"]!!.jsonObject["comparison"]!!.jsonObject["rows"]!!.jsonArray.size)
        assertFalse("truncated" in DtaJson.parseToJsonElement(out.text).jsonObject)
    }

    @Test fun `an answer over the bound is cut at rows, parses, and says so`() {
        val out = invoke(3000)
        assertFalse(out.isError, out.text)
        assertTrue(out.text.toByteArray().size <= 65_536, "${out.text.length}")
        val doc = DtaJson.parseToJsonElement(out.text).jsonObject
        val rows = doc["result"]!!.jsonObject["comparison"]!!.jsonObject["rows"]!!.jsonArray
        assertTrue(rows.size in 1..2999)
        assertTrue(Regex("""^[1-9]\d* of 3000""").containsMatchIn(doc["truncated"]!!.jsonObject["omitted"]!!.jsonPrimitive.content))
        assertEquals("com.example.pkg.Class1", rows.first().jsonObject["className"]!!.jsonPrimitive.content, "biggest first")
    }
}
