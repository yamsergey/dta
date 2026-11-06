package io.yamsergey.dta.present.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RowSortTest {
    @Test
    fun `durations compare as durations`() {
        assertEquals(142, RowSort.millis("142 ms · 990 B"))
        assertEquals(1300, RowSort.millis("1.3 s · 18.4 KB"))
        assertNull(RowSort.millis("22 B"))
    }
}
