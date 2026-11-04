package io.yamsergey.dta.present.data

import io.yamsergey.dta.protocol.data.PropertyInfo
import io.yamsergey.dta.protocol.data.ViewModelInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuntimeBrowserTest {
    private val vm = ViewModelInfo(
        "v1", "app.CartViewModel", owner = "activity MainActivity",
        properties = listOf(
            PropertyInfo("state", "stateflow", "CartState", Json.parseToJsonElement("""{"items":2}"""), true),
            PropertyInfo("label", "field", "String", JsonPrimitive("x"), false),
        ),
        savedState = listOf(PropertyInfo("step", "savedstate", "Integer", JsonPrimitive(1), true)),
    )

    @Test
    fun `a ViewModel's properties and saved state are rows, values as JSON text`() {
        val rows = PropertyRows(vm)
        assertEquals(listOf("state", "label", "saved: step"), rows.rows.map { it.label })
        assertEquals("""{"items":2}""", rows.rows[0].value)
        assertEquals(listOf(true, false, true), rows.rows.map { it.editable })
    }

    @Test
    fun `an edit is the operation that makes it, refused when not JSON or read-only`() {
        val rows = PropertyRows(vm)
        assertEquals("runtime.vm.set" to mapOf("viewModel" to "v1", "path" to "state", "value" to """{"items":0}"""), rows.edit(0, """{"items":0}"""))
        assertEquals("runtime.saved.set" to mapOf("viewModel" to "v1", "key" to "step", "value" to "3"), rows.edit(2, "3"))
        assertEquals("a property value is JSON: a string in quotes (\"text\"), a number, true/false, null or an object", assertFailsWith<IllegalArgumentException> { rows.edit(0, "text") }.message)
        assertFailsWith<IllegalArgumentException> { rows.edit(1, "\"y\"") }
    }

    @Test
    fun `the table shows a preview, objects and arrays with their size, cut at 80`() {
        assertEquals("""{1} {"items":2}""", PropertyRows.preview(Json.parseToJsonElement("""{"items":2}""")))
        assertEquals("[3] [1,2,3]", PropertyRows.preview(Json.parseToJsonElement("[1,2,3]")))
        assertEquals("\"x\"", PropertyRows.preview(JsonPrimitive("x")))
        val long = PropertyRows.preview(Json.parseToJsonElement("[" + (1..100).joinToString(",") + "]"))
        assertEquals(80, long.length)
        assertEquals('…', long.last())
        assertEquals("""{1} {"items":2}""", PropertyRows(vm).rows[0].preview)
    }

    @Test
    fun `the viewer shows the value pretty, a header, and the whole ViewModel when nothing is chosen`() {
        val rows = PropertyRows(vm)
        assertEquals("{\n  \"items\": 2\n}", rows.pretty(0))
        assertEquals("\"x\"", rows.pretty(1))
        assertEquals("state · stateflow · CartState · editable", rows.header(0))
        assertEquals("label · field · String · read-only", rows.header(1))
        assertEquals("saved: step · savedstate · Integer · editable", rows.header(2))
        assertEquals("{\n  \"state\": {\n    \"items\": 2\n  },\n  \"label\": \"x\",\n  \"savedState\": {\n    \"step\": 1\n  }\n}", rows.whole())
        assertEquals(true, rows.structured(0))
        assertEquals(false, rows.structured(1))
    }
}
