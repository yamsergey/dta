package io.yamsergey.dta.protocol.layout

import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutTreeParamsTest {

    private fun parse(json: String?): LayoutTreeParams =
        LayoutTreeParams.parse(json?.let { DtaJson.parseToJsonElement(it).jsonObject })

    @Test
    fun `absent params mean semantic level, no filter, no framework frames`() {
        for (p in listOf(parse(null), parse("{}"))) {
            assertEquals(DetailLevel.SEMANTIC, p.detail)
            assertNull(p.filter)
            assertEquals(false, p.frameworkFrames)
        }
    }

    @Test
    fun `detail level and framework frames are parsed`() {
        val p = parse("""{"detail":"composition","frameworkFrames":true}""")
        assertEquals(DetailLevel.COMPOSITION, p.detail)
        assertEquals(true, p.frameworkFrames)
        assertEquals(DetailLevel.LAYOUT, parse("""{"detail":"layout"}""").detail)
    }

    @Test
    fun `unknown detail level is invalid params listing the valid values`() {
        val e = assertFailsWith<InvalidParamsException> { parse("""{"detail":"everything"}""") }
        assertTrue("everything" in e.message!!, e.message)
        assertTrue("semantic, layout, composition" in e.message!!, e.message)
    }

    @Test
    fun `filters are the nested object from the layout_tree contract`() {
        val p = parse("""{"filters":{"text":"Submit","type":"Button","resourceId":"id/x","rootId":7}}""")
        assertEquals(LayoutFilter(text = "Submit", type = "Button", resourceId = "id/x", rootId = 7), p.filter)
    }

    @Test
    fun `an empty filter object means no filter`() {
        assertNull(parse("""{"filters":{}}""").filter)
    }

    @Test
    fun `malformed params are invalid params, not a crash`() {
        assertFailsWith<InvalidParamsException> { parse("""{"filters":"text"}""") }
        assertFailsWith<InvalidParamsException> { parse("""{"frameworkFrames":"yes"}""") }
    }

    @Test
    fun `encoded params round-trip and omit defaults`() {
        val p = LayoutTreeParams(filters = LayoutFilter(text = "A"), detail = DetailLevel.LAYOUT)
        val json: JsonObject = p.toJson()
        assertEquals(p, LayoutTreeParams.parse(json))
        assertTrue("frameworkFrames" !in json, json.toString())
        assertTrue("detail" !in LayoutTreeParams().toJson())
    }
}
