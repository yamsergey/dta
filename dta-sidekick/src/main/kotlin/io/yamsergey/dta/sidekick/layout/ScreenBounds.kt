package io.yamsergey.dta.sidekick.layout

import io.yamsergey.dta.protocol.layout.Quad
import io.yamsergey.dta.protocol.layout.Rect
import kotlin.math.ceil
import kotlin.math.floor

/** A node's bounds in screen pixels: its rendered outline's box when transformed (feature 014, R4). */
internal object ScreenBounds {
    fun of(location: IntArray, width: Int, height: Int, outline: Quad?): Rect {
        if (outline == null) return Rect(location[0], location[1], location[0] + width, location[1] + height)
        val xs = floatArrayOf(outline.x0, outline.x1, outline.x2, outline.x3)
        val ys = floatArrayOf(outline.y0, outline.y1, outline.y2, outline.y3)
        return Rect(floor(xs.min()).toInt(), floor(ys.min()).toInt(), ceil(xs.max()).toInt(), ceil(ys.max()).toInt())
    }
}
