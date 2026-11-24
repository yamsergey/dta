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
        val pane = EntryDetailPane(project, scope, open = { opened += it })
        pane.showLoaded("r2-1:6", Json.parseToJsonElement(javaClass.getResource("/entry-ws.json")!!.readText()).jsonObject)
        val tabs = all(pane).filterIsInstance<JBTabbedPane>().single()
        // D-023: the tab says how many messages the socket had.
        tabs.selectedIndex = (0 until tabs.tabCount).first { tabs.getTitleAt(it) == "Messages · 3" }
        render(pane, "links-connection")
        // Feature 042: a list of app content — summary, the first formatted lines, expandable; a double-click opens the message.
        val view = all(tabs.selectedComponent).filterIsInstance<MessagesView>().single()
        assertEquals(2, view.rowCount)
        (0 until view.rowCount).forEach { i -> assertTrue(view.rowTexts(i).toString(), view.rowTexts(i).drop(1).none { it.trimStart().startsWith("[") }) }
        assertTrue(all(tabs.selectedComponent).filterIsInstance<ActionLink>().any { it.text.startsWith("Format: ") && it.text.endsWith("▾") })
        val list = all(view).filterIsInstance<javax.swing.JList<*>>().single()
        list.setSize(800, 400)
        val at = list.getCellBounds(1, 1)
        list.dispatchEvent(java.awt.event.MouseEvent(list, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, at.x + 2, at.y + 2, 2, false))
        assertEquals(listOf("r2-1:8"), opened)
        // D-055 (025 FR-002): Enter opens the selected row as a double-click does.
        view.select(0)
        val enter = list.getActionForKeyStroke(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0))
        assertNotNull("Enter is bound on the message rows", enter)
        enter!!.actionPerformed(java.awt.event.ActionEvent(list, java.awt.event.ActionEvent.ACTION_PERFORMED, "enter"))
        assertEquals(listOf("r2-1:8", "r2-1:7"), opened)
    }

    /**
     * D-091 (042 FR-002: expand/collapse per row, kept while the tab lives): a new message of the shown socket reads the
     * entry again; the Messages view stays the same one, with its expanded row, its selection and the focus.
     */
    fun `test a new message keeps the Messages view, its expanded row and its selection (D-091)`() {
        fun msg(n: Int, text: String) = """{"type":"ws-message","id":"r2-1:$n","runId":"r2-1","timeMs":${1700000006000 + n * 100},"library":"okhttp3-ws","connectionId":"r2-1:6","direction":"in","opcode":"text","payload":{"part":"message","storedBytes":${text.length},"completeness":"complete","preview":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(text))}}}"""
        val long = (1..12).joinToString(",", "[", "]") { "{\"tick\":$it}" }
        fun entry(vararg messages: String) = Json.parseToJsonElement(
            """{"entry":{"type":"ws-connection","id":"r2-1:6","runId":"r2-1","timeMs":1700000006000,"library":"okhttp3-ws","url":"wss://ws.example.com/feed","state":"open","openedAtMs":1700000006100,"messageCount":${messages.size}},"messages":[${messages.joinToString(",")}]}""",
        ).jsonObject
        val pane = EntryDetailPane(project, scope)
        pane.showLoaded("r2-1:6", entry(msg(7, long), msg(8, "{\"a\":1}")))
        val tabs = all(pane).filterIsInstance<JBTabbedPane>().single()
        tabs.selectedIndex = (0 until tabs.tabCount).first { tabs.getTitleAt(it).startsWith("Messages") }
        val view = all(pane).filterIsInstance<MessagesView>().single()
        val expandedAt = (0 until view.rowCount).first { i -> view.rowTexts(i).last().startsWith("▸") }
        view.toggle(expandedAt)
        val expanded = view.rowTexts(expandedAt)
        assertTrue(expanded.toString(), expanded.last() == "▾ less")
        view.select("r2-1:8")
        pane.followLoaded(entry(msg(7, long), msg(8, "{\"a\":1}"), msg(9, "{\"b\":2}")))
        val after = all(pane).filterIsInstance<MessagesView>().single()
        assertSame("the same Messages view", view, after)
        assertEquals(3, after.rowCount)
        val row = (0 until after.rowCount).first { i -> after.rowTexts(i) == expanded }
        assertTrue("the expanded row stays expanded", after.rowTexts(row).last() == "▾ less")
        assertEquals("r2-1:8", after.selectedId)
        assertTrue("the Messages tab stays selected", tabs.getTitleAt(tabs.selectedIndex).startsWith("Messages"))
    }

    /** D-092 (042 edge cases): a binary message's content line is "binary · N B", no text lines. */
    fun `test a binary message row reads binary with its size (D-092)`() {
        val pane = EntryDetailPane(project, scope)
        pane.showLoaded("r2-1:6", Json.parseToJsonElement(javaClass.getResource("/entry-ws.json")!!.readText()).jsonObject)
        val view = all(pane).filterIsInstance<MessagesView>().single()
        val binary = (0 until view.rowCount).map { view.rowTexts(it) }.single { rows -> rows.any { it.startsWith("binary") } }
        assertEquals(binary.toString(), listOf("binary · 300 B"), binary.drop(1))
    }

    /**
     * NET-WS-064 (025 FR-002, D-076): in the IDE, keys go through its key dispatcher, which gives a key to the actions
     * registered on the focused component before Swing's key bindings (the robot IDE: Enter on a selected row did
     * nothing). Enter on the Messages list is such an action, and it opens the selected message.
     */
    fun `test Enter on a Messages row is an IDE action on the list that opens it (NET-WS-064)`() {
        val opened = mutableListOf<String>()
        val pane = EntryDetailPane(project, scope, open = { opened += it })
        pane.showLoaded("r2-1:6", Json.parseToJsonElement(javaClass.getResource("/entry-ws.json")!!.readText()).jsonObject)
        val view = all(pane).filterIsInstance<MessagesView>().single()
        val list = all(view).filterIsInstance<javax.swing.JList<*>>().single()
        val enter = javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0)
        val action = com.intellij.openapi.actionSystem.ex.ActionUtil.getActions(list).singleOrNull { a ->
            a.shortcutSet.shortcuts.any { (it as? com.intellij.openapi.actionSystem.KeyboardShortcut)?.firstKeyStroke == enter }
        }
        assertNotNull("an IDE action with the Enter shortcut on the Messages list", action)
        view.select(1)
        action!!.actionPerformed(com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext("test", null, com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT))
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
        val pane = EntryDetailPane(project, scope, open = { opened += it })
        val message = """{"entry":{"type":"ws-message","id":"r2-1:7","runId":"r2-1","timeMs":1700000006200,"library":"okhttp3-ws","connectionId":"r2-1:6","url":"wss://ws.example.com/feed","direction":"out","opcode":"text","payload":{"part":"message","storedBytes":9,"completeness":"complete","preview":"{\"sub\":1}"}}}"""
        pane.showLoaded("r2-1:7", Json.parseToJsonElement(message).jsonObject, unlisted = true)
        render(pane, "links-message")
        val link = all(pane).filterIsInstance<ActionLink>().single { it.text == "wss://ws.example.com/feed" }
        link.doClick()
        assertEquals(listOf("r2-1:6"), opened)
        assertTrue(all(pane).filterIsInstance<JLabel>().any { it.text == "Not in the list as filtered" })
    }
}
