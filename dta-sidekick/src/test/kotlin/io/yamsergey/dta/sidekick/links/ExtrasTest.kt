package io.yamsergey.dta.sidekick.links

import io.yamsergey.dta.protocol.network.IntentExtra
import kotlin.test.Test
import kotlin.test.assertEquals

/** 012 T004: extras as text, each read on its own; one that cannot be read is said so. */
class ExtrasTest {
    @Test
    fun `values of every kind, and one that cannot be read`() {
        val values = mapOf<String, () -> Any?>(
            "s" to { "abc" },
            "n" to { 42 },
            "b" to { true },
            "arr" to { intArrayOf(1, 2) },
            "list" to { listOf("x", "y") },
            "nested" to { NestedFake(mapOf("k" to "v")) },
            "parcel" to { SomeParcelable() },
            "broken" to { throw RuntimeException("ClassNotFoundException when unmarshalling: com.app.Secret") },
            "nothing" to { null },
        )
        val extras = Extras.render(values.keys.sorted(), { values.getValue(it)() }) { (it as? NestedFake)?.map }
        val byKey = extras.associateBy { it.key }
        assertEquals(IntentExtra("s", "String", "abc"), byKey["s"])
        assertEquals(IntentExtra("n", "Integer", "42"), byKey["n"])
        assertEquals("[1, 2]", byKey["arr"]!!.value)
        assertEquals("[x, y]", byKey["list"]!!.value)
        assertEquals("{k=v}", byKey["nested"]!!.value)
        assertEquals(IntentExtra("parcel", "ExtrasTest.SomeParcelable", null, null), byKey["parcel"]!!.copy(value = null))
        assertEquals("ClassNotFoundException when unmarshalling: com.app.Secret", byKey["broken"]!!.notRead)
        assertEquals(IntentExtra("nothing", null, "null"), byKey["nothing"])
    }

    class NestedFake(val map: Map<String, Any?>)
    class SomeParcelable { override fun toString() = "SomeParcelable@1" }
}
