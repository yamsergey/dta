package io.yamsergey.dta.sidekick.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.flow.asStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ValuesTest {
    enum class Mode { LIGHT, DARK }
    data class Address(val city: String, val zip: Int)
    data class State(val items: Int, val coupon: String?, val loading: Boolean, val mode: Mode, val tags: List<String>, val address: Address)
    class Node(val name: String) { var next: Node? = null }

    private val state = State(2, null, false, Mode.LIGHT, listOf("a", "b"), Address("Oslo", 150))

    @Test
    fun `data classes, enums, collections and nulls render as JSON`() {
        assertEquals(
            Json.parseToJsonElement("""{"items":2,"coupon":null,"loading":false,"mode":"LIGHT","tags":["a","b"],"address":{"city":"Oslo","zip":150}}"""),
            Values.render(state),
        )
        assertEquals(JsonPrimitive(3.5), Values.render(3.5))
        assertEquals(Json.parseToJsonElement("""{"k":1}"""), Values.render(mapOf("k" to 1)))
    }

    @Test
    fun `cycles and depth are cut, saying so`() {
        val a = Node("a").apply { next = Node("b").also { it.next = this } }
        val r = Values.render(a).jsonObject
        assertEquals(JsonPrimitive("a"), r["name"])
        assertTrue(r["next"]!!.jsonObject["next"].toString().contains("cycle"))
        val deep = Values.render(listOf(listOf(listOf(listOf(listOf(1))))), maxDepth = 3)
        assertTrue(deep.toString().contains("…"))
    }

    @Test
    fun `JSON converts to the current value's type and refuses what does not fit`() {
        assertEquals(5, Values.convert(Json.parseToJsonElement("5"), 1, Int::class.java))
        assertEquals(5L, Values.convert(Json.parseToJsonElement("5"), 1L, Long::class.java))
        assertEquals("x", Values.convert(Json.parseToJsonElement("\"x\""), null, String::class.java))
        assertEquals(Mode.DARK, Values.convert(Json.parseToJsonElement("\"DARK\""), Mode.LIGHT, Mode::class.java))
        assertEquals(null, Values.convert(Json.parseToJsonElement("null"), "y", String::class.java))
        assertEquals(listOf("x"), Values.convert(Json.parseToJsonElement("[\"x\"]"), listOf("a"), List::class.java))
        assertEquals(Address("Rome", 1), Values.convert(Json.parseToJsonElement("""{"city":"Rome","zip":1}"""), Address("Oslo", 150), Address::class.java))
        val e = assertFailsWith<IllegalArgumentException> { Values.convert(Json.parseToJsonElement("\"many\""), 1, Int::class.java) }
        assertEquals("expected an Int; got \"many\"", e.message)
    }

    @Test
    fun `constructor order comes from the data class's toString, not the runtime's field order`() {
        assertEquals(listOf("items", "coupon", "loading", "mode", "tags", "address"), Values.constructorFields(state).map { it.name })
    }

    @Test
    fun `a field deep in a data class is set by copying each level`() {
        val changed = Values.with(state, listOf("address", "city"), Json.parseToJsonElement("\"Rome\"")) as State
        assertEquals(state.copy(address = Address("Rome", 150)), changed)
        val coupon = Values.with(state, listOf("coupon"), Json.parseToJsonElement("\"SAVE10\"")) as State
        assertEquals("SAVE10", coupon.coupon)
        assertFailsWith<IllegalArgumentException> { Values.with(state, listOf("nope"), JsonPrimitive(1)) }
    }

    data class Point(val x: Int, val y: Int)
    class Repository(val api: Any = Any())

    @Test
    fun `a read-only StateFlow is seen as the MutableStateFlow it wraps (feature 033)`() {
        val mutable = kotlinx.coroutines.flow.MutableStateFlow(1)
        val readOnly = mutable.asStateFlow()
        assertTrue(readOnly !== mutable)
        assertTrue(Values.unwrapReadonly(readOnly) === mutable)
        assertTrue(Values.unwrapReadonly(mutable) === mutable)
        assertEquals("x", Values.unwrapReadonly("x"))
    }

    @Test
    fun `services are references, state is a value (feature 033)`() {
        assertTrue(Values.isReference(Repository()))
        assertTrue(Values.isReference(okhttp3.OkHttpClient()))
        listOf(null, 1, "s", Mode.DARK, Point(1, 2), listOf(1), mapOf("a" to 1), arrayOf(1), java.util.Date()).forEach {
            assertTrue(!Values.isReference(it), "$it")
        }
    }
}
