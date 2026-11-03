package io.yamsergey.dta.protocol.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PreferencesCodecTest {
    private val sample = listOf(
        PrefEntry("dark", PrefType.BOOLEAN, JsonPrimitive(true)),
        PrefEntry("ratio", PrefType.FLOAT, JsonPrimitive(1.5f)),
        PrefEntry("count", PrefType.INT, JsonPrimitive(-3)),
        PrefEntry("seen", PrefType.LONG, JsonPrimitive(1793740387051L)),
        PrefEntry("name", PrefType.STRING, JsonPrimitive("Ада ✓")),
        PrefEntry("tags", PrefType.STRING_SET, JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))),
        PrefEntry("pi", PrefType.DOUBLE, JsonPrimitive(3.141592653589793)),
        PrefEntry("raw", PrefType.BYTES, JsonPrimitive("AQID")),
    )

    @Test
    fun `every value type survives encode then decode, keys sorted`() {
        val decoded = PreferencesCodec.decode(PreferencesCodec.encode(sample))
        assertEquals(sample.sortedBy { it.key }, decoded)
    }

    @Test
    fun `bytes written by DataStore decode`() {
        // PreferenceMap { "count" -> Value { integer: 42 } } as DataStore writes it.
        val bytes = byteArrayOf(0x0a, 0x0b, 0x0a, 0x05) + "count".toByteArray() + byteArrayOf(0x12, 0x02, 0x18, 0x2a)
        assertEquals(listOf(PrefEntry("count", PrefType.INT, JsonPrimitive(42))), PreferencesCodec.decode(bytes))
    }

    @Test
    fun `an empty file is an empty map, garbage is refused with a reason`() {
        assertEquals(emptyList(), PreferencesCodec.decode(ByteArray(0)))
        val e = assertFailsWith<IllegalArgumentException> { PreferencesCodec.decode(byteArrayOf(0x0a, 0x7f)) }
        assertEquals(true, e.message!!.contains("not a Preferences DataStore file"))
    }

    @Test
    fun `typed values parse from text and refuse what does not fit`() {
        assertEquals(JsonPrimitive(42), PrefType.INT.parse("42"))
        assertEquals(JsonPrimitive(true), PrefType.BOOLEAN.parse("true"))
        assertEquals(JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive("y"))), PrefType.STRING_SET.parse("[\"x\",\"y\"]"))
        assertEquals("count takes an int (a 32-bit whole number); got \"4.2\"", assertFailsWith<IllegalArgumentException> { PrefType.INT.parseFor("count", "4.2") }.message)
        assertFailsWith<IllegalArgumentException> { PrefType.BOOLEAN.parse("yes") }
    }
}
