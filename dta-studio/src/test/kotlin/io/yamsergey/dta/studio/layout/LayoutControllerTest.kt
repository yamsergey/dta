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
        assertEquals(listOf<Pair<String?, DetailLevel>>("com.example.shop" to DetailLevel.LAYOUT), daemon.ops.layoutRequests.toList())
        assertEquals("1080×2400 px · MainActivity · sidekick", shown.tree!!.summary)
        assertEquals(shown.tree!!.initiallyExpanded, shown.expanded)
    }

    @Test
    fun `a refresh keeps the selected node where it moved, and says when it is gone`() = runBlocking<Unit> {
        daemon.ops.capture = screen(button(2, "Pay"))
        session.start()
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

    @Test
    fun `choosing a detail level recaptures at that level`() = runBlocking<Unit> {
        session.start()
        layout.setActive(true)
        await { it.tree != null }
        layout.setDetail(DetailLevel.COMPOSITION)
        await { it.detail == DetailLevel.COMPOSITION && !it.loading && it.tree != null }
        withTimeout(5_000) { while (daemon.ops.layoutRequests.none { it.second == DetailLevel.COMPOSITION }) kotlinx.coroutines.delay(20) }
    }

    @Test
    fun `search expands the ancestors of matches and steps through them`() = runBlocking<Unit> {
        daemon.ops.capture = screen(ViewNode(3, Rect(0, 0, 10, 10), "android.widget.LinearLayout", children = listOf(button(4, "Pay now"))), button(5, "Pay later"))
        session.start()
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
}
