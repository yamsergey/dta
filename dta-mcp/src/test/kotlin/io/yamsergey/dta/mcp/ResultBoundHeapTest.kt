package io.yamsergey.dta.mcp

import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** D-131: heap answers over the bound are cut at row boundaries and say so, never as a broken string. */
class ResultBoundHeapTest {
    private fun rows(n: Int) = JsonArray((1..n).map { buildJsonObject { put("className", "com.example.C$it"); put("countA", 1); put("countB", 2) } })

    private fun check(op: String, result: JsonObject, listPath: List<String>) {
        val text = ResultBound(32_768).apply(op, Selected(null, result))
        assertTrue(text.toByteArray().size <= 32_768, "within the bound")
        val doc = DtaJson.parseToJsonElement(text).jsonObject
        val cut = doc["result"]!!.jsonObject
        var node = cut
        for (k in listPath.dropLast(1)) node = node[k]!!.jsonObject
        val kept = node[listPath.last()]!!.jsonArray
        assertTrue(kept.isNotEmpty() && kept.size < 2000, "some rows kept, some left out")
        val t = assertNotNull(doc["truncated"], "the cut is stated").jsonObject
        assertTrue(t["omitted"]!!.toString().contains(listPath.last()))
        assertTrue(t["how"]!!.toString().isNotBlank())
    }

    @Test fun compareRowsAreCutAtRowBoundaries() = check(
        "runtime.heap.compare",
        buildJsonObject {
            put("comparison", buildJsonObject { put("a", "s1"); put("rows", rows(2000)) })
            put("leftOut", "none")
        },
        listOf("comparison", "rows"),
    )

    @Test fun instancesAreCutAtRowBoundaries() = check(
        "runtime.heap.instances",
        buildJsonObject { put("className", "C"); put("total", 2000); put("instances", rows(2000)) },
        listOf("instances"),
    )

    @Test fun fieldsAreCutAtRowBoundaries() = check(
        "runtime.heap.fields",
        buildJsonObject { put("fields", buildJsonObject { put("instance", 1); put("fields", rows(2000)) }) },
        listOf("fields", "fields"),
    )
}
