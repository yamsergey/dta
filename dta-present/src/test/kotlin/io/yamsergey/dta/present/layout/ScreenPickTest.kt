package io.yamsergey.dta.present.layout

import io.yamsergey.dta.protocol.layout.Alignment
import io.yamsergey.dta.protocol.layout.AlignmentKind
import io.yamsergey.dta.protocol.layout.CaptureSource
import io.yamsergey.dta.protocol.layout.LayoutCapture
import io.yamsergey.dta.protocol.layout.Node
import io.yamsergey.dta.protocol.layout.Rect
import io.yamsergey.dta.protocol.layout.Screen
import io.yamsergey.dta.protocol.layout.ScreenImage
import io.yamsergey.dta.protocol.layout.ScreenImageState
import io.yamsergey.dta.protocol.layout.ScreenWindow
import io.yamsergey.dta.protocol.layout.ScreenWindowKind
import io.yamsergey.dta.protocol.layout.ViewNode
import io.yamsergey.dta.protocol.layout.Visibility
import io.yamsergey.dta.protocol.layout.Window
import io.yamsergey.dta.protocol.layout.WindowType
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Feature 014 (T014, research R7): what is under a point of the picture. */
class ScreenPickTest {
    private fun v(id: Long, b: Rect, vararg children: Node, invisible: Boolean = false) = ViewNode(
        id = id, bounds = b, className = "V$id", children = children.toList(),
        visibility = if (invisible) Visibility.INVISIBLE else Visibility.VISIBLE,
    )

    private val activity = v(
        1, Rect(0, 0, 1080, 2400),
        v(2, Rect(0, 100, 1080, 600),
            v(3, Rect(100, 200, 400, 300)),
            // Overlapping siblings: 5 is drawn after 4, so it is on top where they meet.
            v(4, Rect(500, 200, 800, 300)),
            v(5, Rect(700, 250, 900, 350)),
        ),
        v(6, Rect(100, 700, 400, 800), invisible = true),
        v(7, Rect(100, 900, 100, 1000)),
    )
    private val dialog = v(10, Rect(100, 1000, 980, 1500), v(11, Rect(150, 1300, 500, 1400)))

    private fun tree(windows: List<ScreenWindow>, alignment: Alignment = Alignment(AlignmentKind.ALIGNED)): LayoutTree {
        val capture = LayoutCapture(
            screen = Screen(1080, 2400, 2.625f),
            source = CaptureSource.SIDEKICK,
            windows = listOf(
                Window(type = WindowType.APPLICATION, zOrder = 0, root = activity),
                Window(type = WindowType.DIALOG, zOrder = 1, root = dialog),
            ),
            screenImage = ScreenImage(ScreenImageState.PRESENT, widthPx = 1080, heightPx = 2400, alignment = alignment),
            screenWindows = windows,
        )
        return LayoutTree.of(DtaJson.encodeToJsonElement(LayoutCapture.serializer(), capture).jsonObject)
    }

    private fun w(name: String, kind: ScreenWindowKind, app: Boolean, b: Rect, z: Int, tree: Int? = null) =
        ScreenWindow(name, if (app) "pkg" else "com.android.systemui", app, kind, kind.name, b, zOrder = z, treeWindow = tree)

    private val windows = listOf(
        w("StatusBar", ScreenWindowKind.SYSTEM, false, Rect(0, 0, 1080, 90), 0),
        w("Toast", ScreenWindowKind.TOAST, false, Rect(300, 2000, 780, 2150), 1),
        w("pkg/.Main", ScreenWindowKind.DIALOG, true, Rect(100, 1000, 980, 1500), 2, tree = 1),
        w("pkg/.Main", ScreenWindowKind.ACTIVITY, true, Rect(0, 0, 1080, 2400), 3, tree = 0),
    )

    private fun paths(hit: ScreenHit) = assertIs<ScreenHit.Nodes>(hit).paths

    @Test
    fun `the deepest node under the point first, then its ancestors to the window root`() {
        assertEquals(listOf(listOf(0, 0, 0), listOf(0, 0), listOf(0)), paths(ScreenPick.at(tree(windows), 150.0, 250.0)))
    }

    @Test
    fun `where siblings overlap the one drawn last wins`() {
        assertEquals(listOf(0, 0, 2), paths(ScreenPick.at(tree(windows), 750.0, 280.0)).first())
    }

    @Test
    fun `a dialog above the activity is picked, not the activity below`() {
        assertEquals(listOf(listOf(1, 0), listOf(1)), paths(ScreenPick.at(tree(windows), 200.0, 1350.0)))
    }

    @Test
    fun `invisible and zero-size nodes are not picked`() {
        assertEquals(listOf(listOf(0)), paths(ScreenPick.at(tree(windows), 150.0, 750.0)))
        assertEquals(listOf(listOf(0)), paths(ScreenPick.at(tree(windows), 100.0, 950.0)))
    }

    @Test
    fun `a window that is not the app's answers for its area`() {
        val hit = assertIs<ScreenHit.Foreign>(ScreenPick.at(tree(windows), 500.0, 2050.0))
        assertEquals("Toast", hit.window.name)
        assertEquals("not the app: toast Toast", hit.statement)
        assertEquals("not the app: system StatusBar", assertIs<ScreenHit.Foreign>(ScreenPick.at(tree(windows), 10.0, 10.0)).statement)
    }

    @Test
    fun `a point no window covers is outside the app's windows`() {
        val split = listOf(w("pkg/.Main", ScreenWindowKind.ACTIVITY, true, Rect(0, 0, 1080, 1200), 0, tree = 0))
        val hit = ScreenPick.at(tree(split), 500.0, 2000.0)
        assertEquals(ScreenHit.Outside, hit)
        assertEquals("outside the app's windows", hit.statement)
    }

    @Test
    fun `the alignment offset is applied to the tree's bounds`() {
        val shifted = windows.map { if (it.app && it.kind == ScreenWindowKind.ACTIVITY) it.copy(bounds = Rect(121, 0, 1201, 2400)) else it }
        val hit = ScreenPick.at(tree(shifted, Alignment(AlignmentKind.OFFSET, dx = 121)), 150.0 + 121, 250.0)
        assertEquals(listOf(0, 0, 0), paths(hit).first())
    }

    @Test
    fun `without the window list the tree's windows are searched top first`() {
        val hit = ScreenPick.at(tree(emptyList()), 200.0, 1350.0)
        assertEquals(listOf(1, 0), paths(hit).first())
    }

    @Test
    fun `the screen the tree carries`() {
        val screen = assertNotNull(tree(windows).screen)
        assertEquals(ScreenImageState.PRESENT, screen.image.state)
        assertEquals(4, screen.windows.size)
    }
}
