package io.yamsergey.dta.protocol.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 074 (B2): field values for agents go through the one redactor, and the answer says so. */
class HeapRedactionTest {
    private fun field(name: String, value: kotlinx.serialization.json.JsonElement?, type: String = "java.lang.String", length: Int? = null, static: Boolean = false) =
        HeapField(name, "com.x.Session", type, static, value, length)

    private fun fields(vararg f: HeapField) = HeapFields(7, "com.x.Session", f.toList())

    @Test fun sensitiveNamesAreMaskedWholeAndNamesAndTypesStay() {
        val r = HeapRedaction.apply(fields(field("authToken", JsonPrimitive("abc123")), field("title", JsonPrimitive("Home"))))
        assertEquals("authToken", r.fields[0].name)
        assertEquals("java.lang.String", r.fields[0].type)
        assertNotEquals(JsonPrimitive("abc123"), r.fields[0].value)
        assertTrue(r.fields[0].value.toString().contains("redacted"))
        assertEquals(JsonPrimitive("Home"), r.fields[1].value)
    }

    @Test fun credentialsInFreeTextAreMasked() {
        val r = HeapRedaction.apply(fields(field("header", JsonPrimitive("Authorization: Bearer abc.def.ghi"))))
        assertTrue(!r.fields[0].value.toString().contains("abc.def.ghi"))
    }

    @Test fun nestedObjectKeysAreMasked() {
        val v = JsonObject(mapOf("user" to JsonPrimitive("ann"), "password" to JsonPrimitive("hunter2")))
        val r = HeapRedaction.apply(fields(field("state", v, type = "com.x.State")))
        assertTrue(!r.fields[0].value.toString().contains("hunter2"))
        assertTrue(r.fields[0].value.toString().contains("ann"))
    }

    @Test fun lengthsAndMissingValuesAreKept() {
        val r = HeapRedaction.apply(fields(field("password", null, length = 8), field("count", null, type = "int")))
        assertEquals(8, r.fields[0].length)
        assertNull(r.fields[0].value)
        assertNull(r.fields[1].value)
    }

    @Test fun answerStatesWhatWasMasked() {
        val r = HeapRedaction.apply(fields(field("secret", JsonPrimitive("x")), field("title", JsonPrimitive("Home"))))
        assertTrue(r.redaction!!.contains("1"))
        assertTrue(r.redaction!!.contains("masked"))
        val none = HeapRedaction.apply(fields(field("title", JsonPrimitive("Home"))))
        assertTrue(none.redaction!!.contains("no value needed masking"))
    }

    @Test fun staticFieldsAreRedactedToo() {
        val r = HeapRedaction.apply(fields(field("API_KEY", JsonPrimitive("k-1"), static = true)))
        assertTrue(r.fields[0].static)
        assertTrue(!r.fields[0].value.toString().contains("k-1"))
    }
}
