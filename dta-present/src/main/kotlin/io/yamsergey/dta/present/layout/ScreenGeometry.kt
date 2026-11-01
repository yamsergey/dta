package io.yamsergey.dta.present.layout

import io.yamsergey.dta.protocol.layout.Alignment
import io.yamsergey.dta.protocol.layout.AlignmentKind
import io.yamsergey.dta.protocol.layout.Quad
import io.yamsergey.dta.protocol.layout.Rect

/**
 * Feature 014 (research R7): the screen image fitted into a pane of the given size, keeping its
 * aspect ratio and centred, with the mapping both ways. Any surface draws with it.
 */
data class ScreenGeometry(val imageWidth: Int, val imageHeight: Int, val paneWidth: Int, val paneHeight: Int) {
    val scale: Double = minOf(paneWidth.toDouble() / imageWidth, paneHeight.toDouble() / imageHeight)
    val originX: Double = (paneWidth - imageWidth * scale) / 2
    val originY: Double = (paneHeight - imageHeight * scale) / 2

    fun toPane(x: Double, y: Double): Pair<Double, Double> = originX + x * scale to originY + y * scale

    /** The image pixel under a pane point, or null when the point is beside the picture. */
    fun toImage(x: Double, y: Double): Pair<Double, Double>? {
        val ix = (x - originX) / scale
        val iy = (y - originY) / scale
        return if (ix < 0 || iy < 0 || ix > imageWidth || iy > imageHeight) null else ix to iy
    }
}

enum class OutlineState(val statement: String?) {
    SHOWN(null),
    CLIPPED("partly outside the screen"),
    ZERO_SIZE("zero size: nothing to outline"),
    OFF_SCREEN("outside the screen"),
    NOT_ALIGNED("the tree's bounds do not line up with the picture"),
}

/** A node's shape on the image: four corners in image pixels, or none with the reason. */
data class Outline(val state: OutlineState, val corners: List<Pair<Double, Double>>) {
    companion object {
        fun of(bounds: Rect, renderOutline: Quad?, alignment: Alignment, imageWidth: Int, imageHeight: Int): Outline {
            if (alignment.kind == AlignmentKind.NOT_ALIGNED) return Outline(OutlineState.NOT_ALIGNED, emptyList())
            if (bounds.width <= 0 || bounds.height <= 0) return Outline(OutlineState.ZERO_SIZE, emptyList())
            val raw = renderOutline?.let { q -> listOf(q.x0 to q.y0, q.x1 to q.y1, q.x2 to q.y2, q.x3 to q.y3).map { (x, y) -> x.toDouble() to y.toDouble() } }
                ?: listOf(bounds.left to bounds.top, bounds.right to bounds.top, bounds.right to bounds.bottom, bounds.left to bounds.bottom)
                    .map { (x, y) -> x.toDouble() to y.toDouble() }
            val corners = raw.map { (x, y) -> (x + alignment.dx) * alignment.scale to (y + alignment.dy) * alignment.scale }
            val xs = corners.map { it.first }
            val ys = corners.map { it.second }
            val state = when {
                xs.max() <= 0 || ys.max() <= 0 || xs.min() >= imageWidth || ys.min() >= imageHeight -> OutlineState.OFF_SCREEN
                xs.min() < 0 || ys.min() < 0 || xs.max() > imageWidth || ys.max() > imageHeight -> OutlineState.CLIPPED
                else -> OutlineState.SHOWN
            }
            return Outline(state, if (state == OutlineState.OFF_SCREEN) emptyList() else corners)
        }
    }
}

/** The outline of the node at [path] on this capture's picture, or null without a picture. */
fun LayoutTree.outline(path: List<Int>): Outline? {
    val screen = screen ?: return null
    val width = screen.image.widthPx ?: return null
    val height = screen.image.heightPx ?: return null
    val json = node(path)?.json ?: return null
    val bounds = json["bounds"]?.let { io.yamsergey.dta.protocol.rpc.DtaJson.decodeFromJsonElement(Rect.serializer(), it) } ?: return null
    val quad = json["renderOutline"]?.let { io.yamsergey.dta.protocol.rpc.DtaJson.decodeFromJsonElement(Quad.serializer(), it) }
    return Outline.of(bounds, quad, screen.alignment, width, height)
}
