package io.yamsergey.dta.studio.layout

import io.yamsergey.dta.present.layout.LayoutTree
import io.yamsergey.dta.present.layout.ScreenInfo
import io.yamsergey.dta.protocol.layout.ScreenImage
import io.yamsergey.dta.protocol.layout.ScreenImageState
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** D-142: Hide containers shows another tree over the same capture; the picture is not decoded again for it. */
class ScreenImageCacheTest {
    private fun png(): String = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8, 16, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray().let { Base64.getEncoder().encodeToString(it) }

    private fun tree() = LayoutTree("", emptyList(), emptyList(), ScreenInfo(ScreenImage(ScreenImageState.PRESENT, 8, 16, data = png()), emptyList()))

    @Test
    fun `a tree over the same capture does not decode the picture again, a new capture does`() {
        val cache = ScreenImageCache()
        val capture = tree()
        val first = cache.imageOf(capture)
        assertNotNull(first)
        assertEquals(1, cache.decodes)
        assertEquals(first, cache.imageOf(capture.visible(true)), "containers hidden: the same picture")
        assertEquals(first, cache.imageOf(capture.visible(false)), "and shown again")
        assertEquals(first, cache.imageOf(capture))
        assertEquals(1, cache.decodes, "decoded once for the capture")
        cache.imageOf(tree())
        assertEquals(2, cache.decodes, "a new capture is a new picture")
    }

    @Test
    fun `no tree or no picture is no image`() {
        val cache = ScreenImageCache()
        assertNull(cache.imageOf(null))
        assertNull(cache.imageOf(LayoutTree("", emptyList(), emptyList(), null)))
    }
}
