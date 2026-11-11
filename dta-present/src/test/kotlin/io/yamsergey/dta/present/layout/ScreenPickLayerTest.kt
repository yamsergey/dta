package io.yamsergey.dta.present.layout

import io.yamsergey.dta.protocol.layout.CaptureSource
import io.yamsergey.dta.protocol.layout.ComposeNode
import io.yamsergey.dta.protocol.layout.LayoutCapture
import io.yamsergey.dta.protocol.layout.Node
import io.yamsergey.dta.protocol.layout.Provenance
import io.yamsergey.dta.protocol.layout.Rect
import io.yamsergey.dta.protocol.layout.Screen
import io.yamsergey.dta.protocol.layout.Window
import io.yamsergey.dta.protocol.layout.WindowType
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Feature 057 (US6): a sheet host kept composed over the screen — a full-window container with
 * nothing of its own — does not answer every point; N reaches every node there.
 */
class ScreenPickLayerTest {
    private fun c(id: Long, b: Rect, vararg children: Node, text: String? = null, tooling: Boolean = text == null) = ComposeNode(
        id = id, bounds = b, name = if (text != null) "Text" else "Box", text = text, children = children.toList(),
        provenance = if (tooling) Provenance.TOOLING else Provenance.SEMANTICS,
    )

    private val full = Rect(0, 0, 1080, 2400)
    private val screen = c(1, full,
        c(2, full, c(3, Rect(100, 200, 600, 300), text = "AAPL × 3")),
        // Drawn last, over everything: the hidden sheet's host and its sheet far below the screen.
        c(4, full, c(5, Rect(0, 4000, 1080, 4600))),
    )

    private fun tree(root: Node) = LayoutTree.of(
        DtaJson.encodeToJsonElement(
            LayoutCapture.serializer(),
            LayoutCapture(screen = Screen(1080, 2400, 2.625f), source = CaptureSource.SIDEKICK, windows = listOf(Window(type = WindowType.APPLICATION, zOrder = 0, root = root))),
        ).jsonObject,
    )

    @Test
    fun `the content under an empty full-window layer answers, and the layer is named`() {
        val hit = assertIs<ScreenHit.Nodes>(ScreenPick.at(tree(screen), 150.0, 250.0))
        assertEquals(listOf(0, 0, 0), hit.paths.first(), "the Text, not the sheet host")
        assertEquals(listOf(0, 1), hit.skipped)
        assertEquals(setOf(listOf(0, 0, 0), listOf(0, 1), listOf(0, 0), listOf(0)), hit.paths.toSet(), "N goes through every node at the point")
    }

    @Test
    fun `where the screen has nothing of its own, its container answers, not the layer over it`() {
        val hit = assertIs<ScreenHit.Nodes>(ScreenPick.at(tree(screen), 900.0, 1500.0))
        assertEquals(listOf(0, 0), hit.paths.first())
        assertEquals(listOf(0, 1), hit.skipped)
    }

    @Test
    fun `a layer inside the content area, not the whole window, is a layer too`() {
        val content = Rect(0, 90, 1080, 2200)
        val app = c(1, full, c(2, content, c(3, content, c(4, Rect(100, 200, 600, 300), text = "Cash 100")), c(5, content)))
        val hit = assertIs<ScreenHit.Nodes>(ScreenPick.at(tree(app), 150.0, 250.0))
        assertEquals(listOf(0, 0, 0, 0), hit.paths.first())
        assertEquals(listOf(0, 0, 1), hit.skipped)
    }

    @Test
    fun `a lone full-size container is not a layer`() {
        val lone = c(1, full, c(2, full, c(3, full)))
        assertNull(assertIs<ScreenHit.Nodes>(ScreenPick.at(tree(lone), 10.0, 10.0)).skipped)
        assertEquals(listOf(0, 0, 0), assertIs<ScreenHit.Nodes>(ScreenPick.at(tree(lone), 10.0, 10.0)).paths.first())
    }

    @Test
    fun `a container smaller than its window is not a layer`() {
        val small = c(1, full, c(2, full, c(3, Rect(100, 200, 600, 300), text = "AAPL")), c(4, Rect(0, 0, 1080, 1200)))
        val hit = assertIs<ScreenHit.Nodes>(ScreenPick.at(tree(small), 150.0, 250.0))
        assertEquals(listOf(0, 1), hit.paths.first(), "the sibling drawn last still wins")
    }

    @Test
    fun `hiding containers keeps capture paths`() {
        val visible = tree(screen).visible(hideContainers = true)
        assertEquals(1, visible.windows.single().root.children.size, "the Box containers are lifted away")
        assertEquals(listOf(0, 0, 0), visible.windows.single().root.children.single().path)
        assertEquals("AAPL × 3", visible.node(listOf(0, 0, 0))?.json?.get("text")?.toString()?.trim('"'))
        assertEquals(3, visible.hiddenContainers)
        assertEquals(tree(screen).windows, tree(screen).visible(hideContainers = false).windows)
    }
}

/**
 * Reported 2026-11-11 on a proprietary app (structure only): the content area holds a scrim Box,
 * the visible content, then a sheet host drawn last whose only-child chain ends in an empty
 * full-screen scrim. With no sheet open that scrim answered every point.
 */
class ScreenPickScrimChainTest {
    private fun c(id: Long, b: Rect, vararg children: Node, text: String? = null, tooling: Boolean = false) = ComposeNode(
        id = id, bounds = b, name = if (text != null) "Text" else "Box", text = text, children = children.toList(),
        provenance = if (tooling) Provenance.TOOLING else Provenance.SEMANTICS,
    )

    private val full = Rect(0, 0, 1080, 2400)
    private fun tree(root: Node) = LayoutTree.of(
        DtaJson.encodeToJsonElement(
            LayoutCapture.serializer(),
            LayoutCapture(screen = Screen(1080, 2400, 2.625f), source = CaptureSource.SIDEKICK, windows = listOf(Window(type = WindowType.APPLICATION, zOrder = 0, root = root))),
        ).jsonObject,
    )

    private fun screen(sheet: Node? = null) = c(1, full,
        c(244, full,
            c(245, full),
            c(258, full, c(259, Rect(100, 300, 600, 400), text = "Settings")),
            c(279, full, c(280, full, *listOfNotNull(c(281, full), sheet).toTypedArray()), tooling = true),
        ),
    )

    @Test
    fun `a scrim at the end of an only-child chain does not answer over the content`() {
        val hit = assertIs<ScreenHit.Nodes>(ScreenPick.at(tree(screen()), 300.0, 350.0))
        assertEquals(listOf(0, 0, 1, 0), hit.paths.first(), "the text under the sheet host")
        assertEquals(listOf(0, 0, 2, 0, 0), hit.skipped, "the scrim is named")
    }

    @Test
    fun `an open sheet's content still answers`() {
        val sheet = c(290, Rect(0, 1200, 1080, 2400), c(291, Rect(100, 1300, 600, 1400), text = "Accounts"))
        val hit = assertIs<ScreenHit.Nodes>(ScreenPick.at(tree(screen(sheet)), 300.0, 1350.0))
        assertEquals(listOf(0, 0, 2, 0, 1, 0), hit.paths.first())
    }

    @Test
    fun `with containers hidden the same holds`() {
        val hit = assertIs<ScreenHit.Nodes>(ScreenPick.at(tree(screen()).visible(hideContainers = true), 300.0, 350.0))
        assertEquals(listOf(0, 0, 1, 0), hit.paths.first())
    }
}
