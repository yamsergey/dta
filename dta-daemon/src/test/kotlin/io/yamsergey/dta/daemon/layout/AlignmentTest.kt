package io.yamsergey.dta.daemon.layout

import io.yamsergey.dta.protocol.layout.AlignmentKind
import io.yamsergey.dta.protocol.layout.Rect
import io.yamsergey.dta.protocol.layout.ScreenWindow
import io.yamsergey.dta.protocol.layout.ScreenWindowKind
import io.yamsergey.dta.protocol.layout.ViewNode
import io.yamsergey.dta.protocol.layout.Window
import io.yamsergey.dta.protocol.layout.WindowType
import kotlin.test.Test
import kotlin.test.assertEquals

/** Feature 014 (T005, research R4): node bounds against the host's window frames. */
class AlignmentTest {
    private fun tree(vararg bounds: Rect) = bounds.mapIndexed { i, b ->
        Window(title = "W$i", type = WindowType.APPLICATION, zOrder = i, root = ViewNode(id = i.toLong(), bounds = b, className = "DecorView"))
    }

    private fun screen(vararg bounds: Rect, foreign: Rect? = null) = buildList {
        foreign?.let { add(ScreenWindow("StatusBar", "com.android.systemui", false, ScreenWindowKind.SYSTEM, "STATUS_BAR", it, zOrder = 0)) }
        bounds.forEach { add(ScreenWindow("pkg/.Main", "pkg", true, ScreenWindowKind.ACTIVITY, "BASE_APPLICATION", it, zOrder = size)) }
    }

    @Test
    fun `equal frames are aligned, and each app window is paired with its tree window`() {
        val r = ScreenAlignment.align(tree(Rect(0, 0, 1080, 2400)), screen(Rect(0, 0, 1080, 2400), foreign = Rect(0, 0, 1080, 121)))
        assertEquals(AlignmentKind.ALIGNED, r.alignment.kind)
        assertEquals(listOf(null, 0), r.windows.map { it.treeWindow })
    }

    @Test
    fun `the same size at another place is an offset`() {
        val r = ScreenAlignment.align(tree(Rect(0, 0, 2279, 1080)), screen(Rect(121, 0, 2400, 1080)))
        assertEquals(AlignmentKind.OFFSET, r.alignment.kind)
        assertEquals(121 to 0, r.alignment.dx to r.alignment.dy)
    }

    @Test
    fun `a uniform scale is stated`() {
        val r = ScreenAlignment.align(tree(Rect(0, 0, 1080, 2400)), screen(Rect(0, 0, 540, 1200)))
        assertEquals(AlignmentKind.SCALED, r.alignment.kind)
        assertEquals(0.5f, r.alignment.scale)
    }

    @Test
    fun `windows that disagree are not aligned, with the reason`() {
        val r = ScreenAlignment.align(
            tree(Rect(0, 0, 1080, 2400), Rect(0, 0, 500, 500)),
            screen(Rect(10, 10, 510, 510), Rect(0, 0, 1080, 2400)),
        )
        assertEquals(AlignmentKind.NOT_ALIGNED, r.alignment.kind)
        assertEquals(true, r.alignment.reason?.isNotBlank())
    }

    @Test
    fun `a dialog above the activity pairs by size, then by order`() {
        // Tree order is bottom first (activity, dialog); screen windows are top first.
        val r = ScreenAlignment.align(
            tree(Rect(0, 0, 1080, 2400), Rect(28, 972, 1052, 1485)),
            screen(Rect(28, 972, 1052, 1485), Rect(0, 0, 1080, 2400)),
        )
        assertEquals(AlignmentKind.ALIGNED, r.alignment.kind)
        assertEquals(listOf(1, 0), r.windows.map { it.treeWindow })
    }

    @Test
    fun `no app window on screen - not aligned`() {
        val r = ScreenAlignment.align(tree(Rect(0, 0, 1080, 2400)), screen(foreign = Rect(0, 0, 1080, 121)))
        assertEquals(AlignmentKind.NOT_ALIGNED, r.alignment.kind)
    }

    @Test
    fun `the system fallback's bounds are screen pixels already`() {
        val r = ScreenAlignment.align(tree(Rect(0, 0, 1080, 2400)), screen(Rect(0, 121, 1080, 2337)), absolute = true)
        assertEquals(AlignmentKind.ALIGNED, r.alignment.kind)
    }
}
