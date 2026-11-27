package io.yamsergey.dta.studio.layout

import io.yamsergey.dta.present.layout.LayoutTree
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * D-142: the picture of a capture, decoded from its PNG once per capture. "Hide containers" gives another [LayoutTree] over the same
 * capture (a copy: the tree differs, its screen is the same object), so the picture is kept by the capture's screen, not by the tree:
 * decoding a 1080 x 2400 PNG on each toggle was most of the 93-142 ms spent on the event thread.
 */
class ScreenImageCache {
    /** How many PNGs were decoded (tests count the work, not milliseconds). */
    var decodes = 0
        private set
    private var keyed = false
    private var forScreen: Any? = null
    private var image: BufferedImage? = null

    fun imageOf(tree: LayoutTree?): BufferedImage? {
        // not cached yet: decodes on every call
        decodes++
        return tree?.screen?.png?.let { runCatching { ImageIO.read(ByteArrayInputStream(it)) }.getOrNull() }
    }
}
