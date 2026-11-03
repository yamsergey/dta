package io.yamsergey.dta.studio.network

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBTabbedPane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JLabel

/**
 * Feature 025: a message links to its connection, a connection's messages link to themselves;
 * following a link asks the panel to show that entry. Pictures go to build/body-views/.
 */
class EntryLinksRenderTest : BasePlatformTestCase() {
    private val out = File("build/body-views").apply { mkdirs() }
    private val scope = CoroutineScope(Dispatchers.Default)

    private fun all(c: Component): List<Component> = listOf(c) + ((c as? Container)?.components?.flatMap(::all) ?: emptyList())

    private fun layout(c: Component) {
        c.doLayout()
        (c as? Container)?.components?.forEach(::layout)
    }

    private fun render(c: Component, name: String) {
        c.setSize(900, 420)
        layout(c)
        val image = BufferedImage(900, 420, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try { c.paint(g) } finally { g.dispose() }
        ImageIO.write(image, "png", File(out, "$name.png"))
    }

    fun `test a connection's messages are links to them`() {
        val opened = mutableListOf<String>()
        val pane = EntryDetailPane(project, scope) { opened += it }
        pane.showLoaded("r2-1:6", Json.parseToJsonElement(javaClass.getResource("/entry-ws.json")!!.readText()).jsonObject)
        val tabs = all(pane).filterIsInstance<JBTabbedPane>().single()
        tabs.selectedIndex = (0 until tabs.tabCount).first { tabs.getTitleAt(it) == "Messages" }
        render(pane, "links-connection")
        val links = all(tabs.selectedComponent).filterIsInstance<ActionLink>()
        assertEquals(2, links.size)
        links[1].doClick()
        assertEquals(listOf("r2-1:8"), opened)
    }

    fun `test the details offer a Copy menu (026)`() {
        val menu = EntryCopyMenu(project, scope, { null }) { kotlinx.serialization.json.JsonObject(emptyMap()) }
        val pane = EntryDetailPane(project, scope, menu)
        pane.showLoaded("r2-1:6", Json.parseToJsonElement(javaClass.getResource("/entry-ws.json")!!.readText()).jsonObject)
        render(pane, "copy-menu")
        assertTrue(all(pane).filterIsInstance<ActionLink>().any { it.text == "Copy ▾" })
    }

    fun `test a message links to its connection, and says when the list hides it`() {
        val opened = mutableListOf<String>()
        val pane = EntryDetailPane(project, scope) { opened += it }
        val message = """{"entry":{"type":"ws-message","id":"r2-1:7","runId":"r2-1","timeMs":1700000006200,"library":"okhttp3-ws","connectionId":"r2-1:6","url":"wss://ws.example.com/feed","direction":"out","opcode":"text","payload":{"part":"message","storedBytes":9,"completeness":"complete","preview":"{\"sub\":1}"}}}"""
        pane.showLoaded("r2-1:7", Json.parseToJsonElement(message).jsonObject, unlisted = true)
        render(pane, "links-message")
        val link = all(pane).filterIsInstance<ActionLink>().single { it.text == "wss://ws.example.com/feed" }
        link.doClick()
        assertEquals(listOf("r2-1:6"), opened)
        assertTrue(all(pane).filterIsInstance<JLabel>().any { it.text == "Not in the list as filtered" })
    }
}
