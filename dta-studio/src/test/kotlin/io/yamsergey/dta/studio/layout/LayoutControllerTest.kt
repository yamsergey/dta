package io.yamsergey.dta.studio.layout

import io.yamsergey.dta.protocol.layout.CaptureSource
import io.yamsergey.dta.protocol.layout.DetailLevel
import io.yamsergey.dta.protocol.layout.LayoutCapture
import io.yamsergey.dta.protocol.layout.Rect
import io.yamsergey.dta.protocol.layout.Screen
import io.yamsergey.dta.protocol.layout.ViewNode
import io.yamsergey.dta.protocol.layout.Window
import io.yamsergey.dta.protocol.layout.WindowType
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.studio.ContextController
import io.yamsergey.dta.studio.DaemonSession
import io.yamsergey.dta.testing.TestDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T035: the Layout view's source — `layout.tree` on demand, detail level, search, selection across refresh. */
class LayoutControllerTest {
    private val daemon = TestDaemon()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = DaemonSession(connect = { daemon.connect(ClientKind.IDE) }, scope = scope)
    private val context = ContextController(session.state, scope)
    private val layout = LayoutController(context, scope)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        daemon.close()
    }

    private suspend fun await(check: (LayoutViewState) -> Boolean): LayoutViewState =
        runCatching { withTimeout(5_000) { layout.state.first(check) } }.getOrElse { error("timed out; layout=${layout.state.value} context=${context.state.value}") }

    private fun screen(vararg children: ViewNode) = LayoutCapture(
        Screen(1080, 2400, 2.6f, "MainActivity"), CaptureSource.SIDEKICK,
        listOf(Window(type = WindowType.APPLICATION, zOrder = 0, root = ViewNode(1, Rect(0, 0, 1080, 2400), "android.widget.FrameLayout", children = children.toList()))),
    )

    private fun button(id: Long, text: String) = ViewNode(id, Rect(0, 0, 100, 50), "android.widget.Button", text = text)

    @Test
    fun `nothing is captured until the view is shown, then the app's screen at layout detail`() = runBlocking<Unit> {
        session.start()
        // The layout controller itself has seen the app: showing the view now is one capture of it
        // (showing it earlier is a capture of the foreground app, then another once the app is known).
        await { it.app == "com.example.shop" }
        assertTrue(daemon.ops.layoutRequests.isEmpty())
        layout.setActive(true)
        val shown = await { it.tree != null }
        // The context may still settle (a new generation once the app's connection is reported):
        // that is a fresh capture, never one of something else or at another detail.
        val requests = daemon.ops.layoutRequests.toList()
        assertTrue(requests.isNotEmpty() && requests.all { it == ("com.example.shop" to DetailLevel.COMPOSITION) }, "$requests")
        assertEquals("1080×2400 px · MainActivity · sidekick", shown.tree!!.summary)
        assertEquals(shown.tree!!.initiallyExpanded, shown.expanded)
    }

    @Test
    fun `a refresh keeps the selected node where it moved, and says when it is gone`() = runBlocking<Unit> {
        daemon.ops.capture = screen(button(2, "Pay"))
        session.start()
        // Shown before the app is known, the view captures twice and the second capture can undo what the test did.
        await { it.app == "com.example.shop" }
        layout.setActive(true)
        await { it.tree != null }
        layout.select(listOf(0, 0))

        daemon.ops.capture = screen(button(7, "Back"), button(2, "Pay"))
        layout.refresh()
        val kept = await { it.tree?.windows?.single()?.root?.children?.size == 2 }
        assertEquals(listOf(0, 1), kept.selected)
        assertNull(kept.note)

        daemon.ops.capture = screen(button(7, "Back"))
        layout.refresh()
        val gone = await { it.tree?.windows?.single()?.root?.children?.size == 1 }
        assertNull(gone.selected)
        assertEquals("The selected node Button (#2) is no longer on screen.", gone.note)
    }

    private suspend fun shown(): LayoutViewState {
        session.start()
        await { it.app == "com.example.shop" }
        layout.setActive(true)
        return await { it.tree != null && !it.loading }
    }

    private fun change() = daemon.ops.signal.tryEmit(
        io.yamsergey.dta.daemon.layout.LayoutSignalWatch.Update.Changed(io.yamsergey.dta.protocol.layout.LayoutChanged(System.currentTimeMillis(), listOf("windows"))),
    )

    @Test
    fun `auto captures after the app's screen changed, manual only says so`() = runBlocking<Unit> {
        shown()
        withTimeout(5_000) { while (daemon.ops.signalWatchers.get() == 0) kotlinx.coroutines.delay(20) }
        kotlinx.coroutines.delay(1_100)
        var before = daemon.ops.layoutRequests.size
        change()
        val hint = await { it.reason.first.startsWith("The screen changed") }
        assertTrue(hint.reason.second, "a caveat")
        kotlinx.coroutines.delay(1_500)
        assertEquals(before, daemon.ops.layoutRequests.size, "manual never captures by itself")

        layout.setRefreshMode(io.yamsergey.dta.present.layout.RefreshPolicy.Mode.AUTO)
        before = daemon.ops.layoutRequests.size
        change()
        withTimeout(5_000) { while (daemon.ops.layoutRequests.size == before) kotlinx.coroutines.delay(20) }
        val after = await { !it.loading }
        assertTrue(after.reason.first.startsWith("Follows the app"), after.reason.first)
        assertTrue(daemon.ops.layoutRequests.all { it.second == DetailLevel.COMPOSITION })
    }

    /**
     * D-149: the screen-change signal says when it happened on the DEVICE's clock; the debounce is counted on the plugin's. A device
     * clock ahead of the computer's (the emulator's was 359 ms) made Auto wait that much longer (1.57-1.94 s against a 1.5 s limit), and one
     * behind made the signal look older than the last capture and be dropped. The plugin takes the moment the signal reached it.
     */
    @Test
    fun `auto debounces from the moment the signal arrived, whatever the device's clock says`() = runBlocking<Unit> {
        shown()
        withTimeout(5_000) { while (daemon.ops.signalWatchers.get() == 0) kotlinx.coroutines.delay(20) }
        layout.setRefreshMode(io.yamsergey.dta.present.layout.RefreshPolicy.Mode.AUTO)
        kotlinx.coroutines.delay(1_100)
        for (skewMs in listOf(6 * 60_000L, -6 * 60_000L)) {
            val before = daemon.ops.layoutRequests.size
            daemon.ops.signal.tryEmit(
                io.yamsergey.dta.daemon.layout.LayoutSignalWatch.Update.Changed(io.yamsergey.dta.protocol.layout.LayoutChanged(System.currentTimeMillis() + skewMs, listOf("windows"))),
            )
            withTimeout(4_000) { while (daemon.ops.layoutRequests.size == before) kotlinx.coroutines.delay(20) }
            await { !it.loading }
            kotlinx.coroutines.delay(1_100)
        }
    }

    /**
     * D-155: after `am start -S` the signal to the old process ends (the state says reconnecting) and returns when the new process is
     * reached. Its screen was drawn before the watch was armed, so no change is ever signalled and Auto kept the dead process's picture
     * (1 of 5 runs, 41 s+). Getting the app back is a change of the screen: one capture follows.
     */
    @Test
    fun `auto captures again when the signal comes back after reconnecting, with no change signalled`() = runBlocking<Unit> {
        shown()
        withTimeout(5_000) { while (daemon.ops.signalWatchers.get() == 0) kotlinx.coroutines.delay(20) }
        layout.setRefreshMode(io.yamsergey.dta.present.layout.RefreshPolicy.Mode.AUTO)
        kotlinx.coroutines.delay(1_100)
        val before = daemon.ops.layoutRequests.size
        daemon.ops.signal.tryEmit(io.yamsergey.dta.daemon.layout.LayoutSignalWatch.Update.State(io.yamsergey.dta.protocol.layout.ScreenSignal.RECONNECTING))
        await { it.reason.first.startsWith("Auto is waiting") }
        kotlinx.coroutines.delay(300)
        assertEquals(before, daemon.ops.layoutRequests.size, "nothing is captured while the app is unreachable")
        daemon.ops.signal.tryEmit(io.yamsergey.dta.daemon.layout.LayoutSignalWatch.Update.State(io.yamsergey.dta.protocol.layout.ScreenSignal.WATCHING))
        withTimeout(4_000) { while (daemon.ops.layoutRequests.size == before) kotlinx.coroutines.delay(20) }
    }

    /** V4 (D-143): the line that Auto cannot follow a capture of an app without DTA goes when the app with DTA is captured again. */
    @Test
    fun `the line that auto cannot follow goes when the app's own capture comes back`() = runBlocking<Unit> {
        shown()
        layout.setRefreshMode(io.yamsergey.dta.present.layout.RefreshPolicy.Mode.AUTO)
        daemon.ops.capture = screen(button(2, "Pay")).copy(source = CaptureSource.UIAUTOMATOR)
        layout.refresh()
        val stuck = await { it.reason.first.startsWith("Auto cannot follow") }
        assertTrue("no DTA inside" in stuck.reason.first, stuck.reason.first)
        daemon.ops.capture = screen(button(2, "Pay"))
        layout.refresh()
        val back = await { !it.loading && !it.reason.first.startsWith("Auto cannot follow") }
        assertTrue(back.reason.first.startsWith("Follows the app"), back.reason.first)
    }

    @Test
    fun `a hidden view watches nothing`() = runBlocking<Unit> {
        shown()
        withTimeout(5_000) { while (daemon.ops.signalWatchers.get() == 0) kotlinx.coroutines.delay(20) }
        layout.setActive(false)
        withTimeout(5_000) { while (daemon.ops.signalWatchers.get() != 0) kotlinx.coroutines.delay(20) }
    }

    @Test
    fun `hiding containers changes the shown tree, not the capture`() = runBlocking<Unit> {
        daemon.ops.capture = LayoutCapture(
            Screen(1080, 2400, 2.6f, "MainActivity"), CaptureSource.SIDEKICK,
            listOf(Window(type = WindowType.APPLICATION, zOrder = 0, root = ViewNode(1, Rect(0, 0, 1080, 2400), "androidx.compose.ui.platform.ComposeView", children = listOf(
                io.yamsergey.dta.protocol.layout.ComposeNode(id = 2, bounds = Rect(0, 0, 1080, 2400), name = "Column", provenance = io.yamsergey.dta.protocol.layout.Provenance.TOOLING, children = listOf(
                    io.yamsergey.dta.protocol.layout.ComposeNode(id = 3, bounds = Rect(0, 0, 100, 50), name = "Text", text = "Pay"))),
            )))),
        )
        val hidden = shown()
        assertEquals(listOf(0, 0, 0), hidden.tree!!.windows.single().root.children.single().path, "the Column is hidden, the Text lifted")
        val before = daemon.ops.layoutRequests.size
        layout.setHideContainers(false)
        val all = await { !it.hideContainers }
        assertEquals(listOf(0, 0), all.tree!!.windows.single().root.children.single().path)
        assertEquals(before, daemon.ops.layoutRequests.size, "no new capture")
    }

    @Test
    fun `search expands the ancestors of matches and steps through them`() = runBlocking<Unit> {
        daemon.ops.capture = screen(ViewNode(3, Rect(0, 0, 10, 10), "android.widget.LinearLayout", children = listOf(button(4, "Pay now"))), button(5, "Pay later"))
        session.start()
        // Shown before the app is known, the view captures twice and the second capture can undo what the test did.
        await { it.app == "com.example.shop" }
        layout.setActive(true)
        await { it.tree != null }
        layout.search("pay")
        val searched = await { it.search?.matches?.size == 2 }
        assertTrue(listOf(0, 0) in searched.expanded)
        layout.nextMatch()
        assertEquals(listOf(0, 0, 0), await { it.selected != null }.selected)
        layout.nextMatch()
        assertEquals(listOf(0, 1), await { it.selected == listOf(0, 1) }.selected)
        layout.search("")
        assertNull(await { it.search == null }.search)
    }

    // --- Feature 014: the screen beside the tree

    private fun withScreen(capture: LayoutCapture, vararg foreign: io.yamsergey.dta.protocol.layout.ScreenWindow) = capture.copy(
        screenImage = io.yamsergey.dta.protocol.layout.ScreenImage(
            io.yamsergey.dta.protocol.layout.ScreenImageState.PRESENT, widthPx = 1080, heightPx = 2400,
            alignment = io.yamsergey.dta.protocol.layout.Alignment(io.yamsergey.dta.protocol.layout.AlignmentKind.ALIGNED), data = "iVBORw0KGgo=",
        ),
        screenWindows = foreign.toList() + io.yamsergey.dta.protocol.layout.ScreenWindow(
            "pkg/.Main", "com.example.shop", true, io.yamsergey.dta.protocol.layout.ScreenWindowKind.ACTIVITY, "BASE_APPLICATION",
            Rect(0, 0, 1080, 2400), zOrder = foreign.size, treeWindow = 0,
        ),
    )

    private fun placed(id: Long, text: String, b: Rect) = ViewNode(id, b, "android.widget.Button", text = text)

    private val toast = io.yamsergey.dta.protocol.layout.ScreenWindow(
        "Toast", "com.android.systemui", false, io.yamsergey.dta.protocol.layout.ScreenWindowKind.TOAST, "TOAST", Rect(300, 2000, 780, 2150), zOrder = 0,
    )

    @Test
    fun `the screen is taken while the screen pane is shown, and the selection is outlined on it`() = runBlocking<Unit> {
        daemon.ops.screenCapture = withScreen(screen(placed(2, "Pay", Rect(100, 200, 300, 260))))
        session.start()
        // Shown before the app is known, the view captures twice and the second capture can undo what the test did.
        await { it.app == "com.example.shop" }
        layout.setActive(true)
        await { it.tree?.screen != null }
        assertTrue(daemon.ops.screenRequests.last() != null)
        layout.select(listOf(0, 0))
        val outline = await { it.selected == listOf(0, 0) }.outline
        assertEquals(io.yamsergey.dta.present.layout.OutlineState.SHOWN, outline?.state)
        assertEquals(100.0 to 200.0, outline?.corners?.first())

        layout.setScreenShown(false)
        layout.refresh()
        await { !it.loading && it.tree?.screen == null }
        assertNull(daemon.ops.screenRequests.last())
    }

    @Test
    fun `clicking the picture selects the deepest element, and the next action climbs to its parents`() = runBlocking<Unit> {
        daemon.ops.screenCapture = withScreen(screen(placed(2, "Pay", Rect(100, 200, 300, 260))), toast)
        session.start()
        // Shown before the app is known, the view captures twice and the second capture can undo what the test did.
        await { it.app == "com.example.shop" }
        layout.setActive(true)
        await { it.tree?.screen != null }
        layout.pick(150.0, 230.0)
        assertEquals(listOf(0, 0), await { it.selected == listOf(0, 0) }.selected)
        layout.nextUnderPointer()
        assertEquals(listOf(0), await { it.selected == listOf(0) }.selected)
        layout.nextUnderPointer()
        assertEquals(listOf(0, 0), await { it.selected == listOf(0, 0) }.selected)
    }

    /** D-158: the caption under the picture said the element last hovered after a CLI select of another element. */
    @Test
    fun `a selection made elsewhere clears the hover, so the caption is not an old label`() = runBlocking<Unit> {
        daemon.ops.screenCapture = withScreen(screen(placed(2, "Pay", Rect(100, 200, 300, 260)), placed(3, "Back", Rect(400, 200, 600, 260))), toast)
        session.start()
        await { it.app == "com.example.shop" }
        layout.setActive(true)
        await { it.tree?.screen != null }
        layout.hover(150.0, 230.0)
        await { it.hover != null }
        // An agent selects the other element (the plugin selects the tree's row): the pointer is not on it, the old label goes.
        layout.select(listOf(0, 1))
        val after = await { it.selected == listOf(0, 1) }
        assertNull(after.hover, "hover after the selection moved: ${after.hover}")
    }

    @Test
    fun `hovering names what is under the pointer, a window that is not the app's included`() = runBlocking<Unit> {
        daemon.ops.screenCapture = withScreen(screen(placed(2, "Pay", Rect(100, 200, 300, 260))), toast)
        session.start()
        // Shown before the app is known, the view captures twice and the second capture can undo what the test did.
        await { it.app == "com.example.shop" }
        layout.setActive(true)
        await { it.tree?.screen != null }
        layout.hover(500.0, 2050.0)
        assertEquals("not the app: toast Toast", await { it.hover != null }.hover?.statement)
        layout.pick(500.0, 2050.0)
        val picked = await { it.note != null }
        assertNull(picked.selected)
        assertEquals("not the app: toast Toast", picked.note)
        layout.hover(null)
        assertNull(await { it.hover == null }.hover)
    }

    @Test
    fun `after a refresh the outline follows the element to where it moved`() = runBlocking<Unit> {
        daemon.ops.screenCapture = withScreen(screen(placed(2, "Pay", Rect(100, 200, 300, 260))))
        session.start()
        // Shown before the app is known, the view captures twice and the second capture can undo what the test did.
        await { it.app == "com.example.shop" }
        layout.setActive(true)
        await { it.tree?.screen != null }
        layout.select(listOf(0, 0))
        daemon.ops.screenCapture = withScreen(screen(placed(7, "Back", Rect(0, 0, 50, 50)), placed(2, "Pay", Rect(100, 600, 300, 660))))
        layout.refresh()
        val moved = await { it.selected == listOf(0, 1) }
        assertEquals(100.0 to 600.0, moved.outline?.corners?.first())
        daemon.ops.screenCapture = withScreen(screen(placed(7, "Back", Rect(0, 0, 50, 50))))
        layout.refresh()
        val gone = await { it.note != null && it.selected == null }
        assertNull(gone.outline)
    }
}
