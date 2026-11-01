package io.yamsergey.dta.daemon.layout

import io.yamsergey.dta.protocol.layout.Rect
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Feature 014 (T004, research R3): did the app's windows change between image A and image B. */
class ScreenDiffTest {
    private fun png(w: Int = 100, h: Int = 200, paint: (BufferedImage) -> Unit = {}): ByteArray {
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply { color = Color.WHITE; fillRect(0, 0, w, h); dispose() }
        paint(image)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun BufferedImage.box(l: Int, t: Int, r: Int, b: Int, c: Color = Color.RED) =
        createGraphics().apply { color = c; fillRect(l, t, r - l, b - t); dispose() }

    private val app = listOf(Rect(0, 20, 100, 200))

    @Test
    fun `identical images have no change`() {
        assertEquals(ScreenDiff.Result.Same, ScreenDiff.compare(png(), png(), app))
    }

    @Test
    fun `a change inside an app window gives its bounding box`() {
        val b = png { it.box(10, 50, 30, 70); it.box(60, 100, 70, 120) }
        assertEquals(ScreenDiff.Result.Changed(Rect(10, 50, 70, 120)), ScreenDiff.compare(png(), b, app))
    }

    @Test
    fun `a change only outside the app's windows (the status bar clock) is ignored`() {
        val b = png { it.box(0, 0, 100, 20) }
        assertEquals(ScreenDiff.Result.Same, ScreenDiff.compare(png(), b, app))
    }

    @Test
    fun `a change is clipped to the app windows`() {
        val b = png { it.box(0, 10, 20, 30) }
        assertEquals(ScreenDiff.Result.Changed(Rect(0, 20, 20, 30)), ScreenDiff.compare(png(), b, app))
    }

    @Test
    fun `different sizes - the screen rotated or resized between the images`() {
        assertEquals(ScreenDiff.Result.Resized(100, 200, 200, 100), ScreenDiff.compare(png(), png(200, 100), app))
    }

    @Test
    fun `size of a png without decoding the pixels`() {
        assertEquals(100 to 200, ScreenDiff.size(png()))
        assertNull(ScreenDiff.size(byteArrayOf(1, 2, 3)))
    }
}
