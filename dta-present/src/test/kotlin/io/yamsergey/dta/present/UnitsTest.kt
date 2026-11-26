package io.yamsergey.dta.present

import kotlin.test.Test
import kotlin.test.assertEquals

class UnitsTest {
    @Test
    fun `a count says one in the singular and the others in the plural`() {
        assertEquals("1 store", Units.count(1, "store"))
        assertEquals("0 stores", Units.count(0, "store"))
        assertEquals("8 stores", Units.count(8, "store"))
        assertEquals("1 entry", Units.count(1, "entry", "entries"))
        assertEquals("2 entries", Units.count(2, "entry", "entries"))
        assertEquals("0 navigation hosts", Units.count(0, "navigation host"))
        assertEquals("1 ViewModel", Units.count(1, "ViewModel"))
        assertEquals("1,234 entries", Units.count(1234, "entry", "entries"))
    }
}
