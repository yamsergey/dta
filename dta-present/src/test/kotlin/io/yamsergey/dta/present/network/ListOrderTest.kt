package io.yamsergey.dta.present.network

import kotlin.test.Test
import kotlin.test.assertEquals

class ListOrderTest {

    @Test
    fun `newest on top shows the list reversed, oldest on top as it is`() {
        assertEquals(listOf(2, 1, 0), (0..2).map { ListOrder.NEWEST_FIRST.shown(it, 3) })
        assertEquals(listOf(0, 1, 2), (0..2).map { ListOrder.OLDEST_FIRST.shown(it, 3) })
    }

    @Test
    fun `rows appended to the list appear at the edge the list follows`() {
        assertEquals(0..1, ListOrder.NEWEST_FIRST.appended(before = 3, after = 5))
        assertEquals(3..4, ListOrder.OLDEST_FIRST.appended(before = 3, after = 5))
        assertEquals(0, ListOrder.NEWEST_FIRST.followEdge(5))
        assertEquals(4, ListOrder.OLDEST_FIRST.followEdge(5))
    }

    @Test
    fun `newest on top by default, and the switch names what it does`() {
        assertEquals(ListOrder.NEWEST_FIRST, ListOrder.DEFAULT)
        assertEquals(ListOrder.OLDEST_FIRST, ListOrder.NEWEST_FIRST.toggled())
        assertEquals(ListOrder.NEWEST_FIRST, ListOrder.of("nonsense"))
        assertEquals(ListOrder.OLDEST_FIRST, ListOrder.of(ListOrder.OLDEST_FIRST.name))
        assertEquals("Newest on top", ListOrder.NEWEST_FIRST.label)
    }
}
