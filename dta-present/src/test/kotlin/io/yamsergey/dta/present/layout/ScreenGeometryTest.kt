package io.yamsergey.dta.present.layout

import io.yamsergey.dta.protocol.layout.Alignment
import io.yamsergey.dta.protocol.layout.AlignmentKind
import io.yamsergey.dta.protocol.layout.Quad
import io.yamsergey.dta.protocol.layout.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Feature 014 (T011, research R7): the picture fitted into the pane, and a node's outline on it. */
class ScreenGeometryTest {
    @Test
    fun `a tall image in a wide pane fits by height and is centred`() {
        val g = ScreenGeometry(imageWidth = 1080, imageHeight = 2400, paneWidth = 600, paneHeight = 600)
        assertEquals(0.25, g.scale)
        assertEquals(165.0 to 0.0, g.originX to g.originY)
        assertEquals(165.0 to 0.0, g.toPane(0.0, 0.0))
        assertEquals(435.0 to 600.0, g.toPane(1080.0, 2400.0))
    }

    @Test
    fun `a wide image in a tall pane fits by width`() {
        val g = ScreenGeometry(imageWidth = 2400, imageHeight = 1080, paneWidth = 480, paneHeight = 900)
        assertEquals(0.2, g.scale)
        assertEquals(0.0 to 342.0, g.originX to g.originY)
    }

    @Test
    fun `pane points map back to image pixels, and outside the picture is nothing`() {
        val g = ScreenGeometry(1080, 2400, 600, 600)
        assertEquals(400.0 to 1200.0, g.toImage(265.0, 300.0))
        assertNull(g.toImage(100.0, 300.0))
    }

    @Test
    fun `a node inside the image is shown with its four corners`() {
        val o = Outline.of(Rect(100, 200, 300, 260), null, Alignment(AlignmentKind.ALIGNED), 1080, 2400)
        assertEquals(OutlineState.SHOWN, o.state)
        assertEquals(listOf(100.0 to 200.0, 300.0 to 200.0, 300.0 to 260.0, 100.0 to 260.0), o.corners)
    }

    @Test
    fun `a transformed node follows its rendered outline`() {
        val quad = Quad(200f, 100f, 300f, 200f, 200f, 300f, 100f, 200f)
        val o = Outline.of(Rect(100, 100, 300, 300), quad, Alignment(AlignmentKind.ALIGNED), 1080, 2400)
        assertEquals(listOf(200.0 to 100.0, 300.0 to 200.0, 200.0 to 300.0, 100.0 to 200.0), o.corners)
    }

    @Test
    fun `offset and scale are applied`() {
        assertEquals(221.0 to 0.0, Outline.of(Rect(100, 0, 200, 50), null, Alignment(AlignmentKind.OFFSET, dx = 121), 2400, 1080).corners.first())
        assertEquals(50.0 to 25.0, Outline.of(Rect(100, 50, 200, 100), null, Alignment(AlignmentKind.SCALED, scale = 0.5f), 540, 1200).corners.first())
    }

    @Test
    fun `zero size, outside the screen and clipped are stated`() {
        val a = Alignment(AlignmentKind.ALIGNED)
        assertEquals(OutlineState.ZERO_SIZE, Outline.of(Rect(10, 10, 10, 50), null, a, 1080, 2400).state)
        assertEquals(OutlineState.OFF_SCREEN, Outline.of(Rect(0, 2500, 100, 2600), null, a, 1080, 2400).state)
        assertEquals(OutlineState.CLIPPED, Outline.of(Rect(0, 2350, 100, 2450), null, a, 1080, 2400).state)
    }

    @Test
    fun `bounds that do not line up with the picture are not outlined`() {
        val o = Outline.of(Rect(10, 10, 50, 50), null, Alignment(AlignmentKind.NOT_ALIGNED, reason = "no window"), 1080, 2400)
        assertEquals(OutlineState.NOT_ALIGNED, o.state)
        assertEquals(emptyList(), o.corners)
    }

    @Test
    fun `each state says what it means`() {
        assertNull(OutlineState.SHOWN.statement)
        assertEquals("zero size: nothing to outline", OutlineState.ZERO_SIZE.statement)
        assertEquals("outside the screen", OutlineState.OFF_SCREEN.statement)
        assertEquals("partly outside the screen", OutlineState.CLIPPED.statement)
    }
}
