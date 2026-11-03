package io.yamsergey.dta.present.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScrollingTest {

    @Test
    fun `scrolling back to the newest edge follows again`() {
        assertEquals(LiveEvent.JumpToEnd, Scrolling.event(following = false, moved = true, atEdge = true))
    }

    @Test
    fun `scrolling away from the newest edge stops following`() {
        assertEquals(LiveEvent.ScrolledAway, Scrolling.event(following = true, moved = true, atEdge = false))
    }

    @Test
    fun `nothing changes when the view did not move or already matches`() {
        // Rows arriving grow the list without moving the view: a selected row in a short list stays selected.
        assertNull(Scrolling.event(following = false, moved = false, atEdge = true))
        assertNull(Scrolling.event(following = true, moved = false, atEdge = false))
        assertNull(Scrolling.event(following = true, moved = true, atEdge = true))
        assertNull(Scrolling.event(following = false, moved = true, atEdge = false))
    }
}
