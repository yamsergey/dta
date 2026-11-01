package io.yamsergey.dta.sidekick.layout

import io.yamsergey.dta.protocol.layout.Quad
import io.yamsergey.dta.protocol.layout.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Feature 014 (research R4): a node's bounds are where it is on the screen. For a rotated or
 * scaled view that is the box around its rendered outline — `getLocationOnScreen` maps only the
 * top-left corner, which put a rotated view's box hundreds of pixels off its pixels.
 */
class ScreenBoundsTest {
    @Test
    fun `an untransformed view is its location and size`() {
        assertEquals(Rect(10, 20, 110, 70), ScreenBounds.of(intArrayOf(10, 20), 100, 50, null))
    }

    @Test
    fun `a rotated view is the box around its four corners`() {
        // A 100×100 square at (200, 200) turned 45° about its centre (250, 250).
        val d = 70.71f
        val quad = Quad(250f, 250f - d, 250f + d, 250f, 250f, 250f + d, 250f - d, 250f)
        assertEquals(Rect(179, 179, 321, 321), ScreenBounds.of(intArrayOf(250, 179), 100, 100, quad))
    }

    @Test
    fun `a scaled view is its scaled box`() {
        val quad = Quad(50f, 50f, 250f, 50f, 250f, 150f, 50f, 150f)
        assertEquals(Rect(50, 50, 250, 150), ScreenBounds.of(intArrayOf(50, 50), 100, 50, quad))
    }
}
