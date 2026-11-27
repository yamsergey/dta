package io.yamsergey.dta.protocol.data

import io.yamsergey.dta.protocol.network.FingerprintKey
import io.yamsergey.dta.protocol.network.Redactor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 074 (B3): field values for agents follow 072 — every string and primitive value masked by value, `[redacted len=N #fp12]`. */
class HeapRedactionTest {
    private val mask = Regex("""\[redacted len=(\d+) #([0-9a-f]{12})]""")

    @BeforeTest fun key() { Redactor.use(FingerprintKey(ByteArray(32) { it.toByte() })) }
    @AfterTest fun unkey() { Redactor.use(null) }

    private fun field(name: String, value: kotlinx.serialization.json.JsonElement?, type: String = "java.lang.String", length: Int? = null, static: Boolean = false) =
        HeapField(name, "com.x.Session", type, static, value, length)

    private fun fields(vararg f: HeapField) = HeapFields(7, "com.x.Session", f.toList())

    @Test fun everyStringAndPrimitiveIsMaskedByValueNotByName() {
        val (r, info) = HeapRedaction.apply(fields(
            field("title", JsonPrimitive("Home")),
            field("MODE_PRIVATE", JsonPrimitive(0), type = "int", static = true),
            field("enabled", JsonPrimitive(true), type = "boolean"),
        ))
        r.fields.forEach { f ->
            val v = (f.value as JsonPrimitive).content
            assertTrue(mask.matches(v), "${f.name} -> $v")
        }
        assertEquals("[redacted len=4 #", (r.fields[0].value as JsonPrimitive).content.substringBefore("#") + "#")
        assertEquals(3, info.masked.size)
        assertTrue(info.applied)
    }

    @Test fun namesTypesStaticAndLengthsStayReadable() {
        val (r, _) = HeapRedaction.apply(fields(field("title", JsonPrimitive("Home"), length = 4, static = true)))
        assertEquals("title", r.fields[0].name)
        assertEquals("java.lang.String", r.fields[0].type)
        assertEquals(4, r.fields[0].length)
        assertTrue(r.fields[0].static)
    }

    @Test fun nullsAndMissingValuesStayAndAreNotListed() {
        val (r, info) = HeapRedaction.apply(fields(field("a", JsonNull), field("b", null, length = 8)))
        assertEquals(JsonNull, r.fields[0].value)
        assertNull(r.fields[1].value)
        assertEquals(8, r.fields[1].length)
        assertTrue(info.masked.isEmpty())
    }

    @Test fun nestedValuesAreMaskedLeafByLeafAndKeysStay() {
        val v = JsonObject(mapOf("user" to JsonPrimitive("ann"), "tags" to JsonArray(listOf(JsonPrimitive("x"), JsonNull)), "n" to JsonPrimitive(3)))
        val (r, info) = HeapRedaction.apply(fields(field("state", v, type = "com.x.State")))
        val o = r.fields[0].value as JsonObject
        assertEquals(setOf("user", "tags", "n"), o.keys)
        assertTrue(mask.matches((o["user"] as JsonPrimitive).content))
        assertEquals(JsonNull, (o["tags"] as JsonArray)[1])
        assertEquals(3, info.masked.size)
        assertTrue(info.masked.any { it.place.contains("state") && it.place.contains("user") })
    }

    @Test fun maskIsKeyedAndStableAcrossFields() {
        val (r, info) = HeapRedaction.apply(fields(field("a", JsonPrimitive("same")), field("b", JsonPrimitive("same")), field("c", JsonPrimitive("other"))))
        val v = r.fields.map { (it.value as JsonPrimitive).content }
        assertEquals(v[0], v[1])
        assertTrue(v[0] != v[2])
        assertEquals(Redactor.mask("same"), v[0])
        assertEquals(4, info.masked[0].length)
        assertEquals(mask.find(v[0])!!.groupValues[2], info.masked[0].fingerprint)
    }

    @Test fun veryManyMaskedPlacesAreCappedAndSaid() {
        val big = JsonArray((1..500).map { JsonPrimitive("v$it") })
        val (r, info) = HeapRedaction.apply(fields(field("items", big, type = "java.util.List")))
        assertEquals(HeapRedaction.MAX_LISTED, info.masked.size)
        assertTrue(r.notes.any { it.contains("${500 - HeapRedaction.MAX_LISTED} more") }, r.notes.toString())
    }
}
