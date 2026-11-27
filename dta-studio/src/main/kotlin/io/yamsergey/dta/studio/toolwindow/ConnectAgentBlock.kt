package io.yamsergey.dta.studio.toolwindow

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.agents.Agent
import io.yamsergey.dta.present.agents.AgentConfigs
import io.yamsergey.dta.present.agents.AgentsTabModel
import io.yamsergey.dta.studio.agents.AgentServerController
import io.yamsergey.dta.studio.agents.AgentServerPrefs
import io.yamsergey.dta.studio.ui.textRenderer
import java.awt.datatransfer.StringSelection
import javax.swing.SwingUtilities

/**
 * Feature 073 (FR-005): "connect an agent" as the Agents tab and the settings page both show it: the agent's choice and
 * its command with the credential masked. The credential is never on screen; Copy command and Copy prompt put it in
 * the clipboard. It is read off the UI thread (the keychain may prompt).
 */
class ConnectAgentBlock(
    private val controller: AgentServerController,
    private val prefs: AgentServerPrefs,
) {
    val agent = ComboBox(Agent.entries.toTypedArray()).apply { renderer = textRenderer { it.title } }
    val command = JBTextArea(0, 40).apply {
        isEditable = false
        // Truncation rules, rule 3: the command breaks only at its own " \\" continuations; the masked credential never wraps.
        lineWrap = false
        font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, JBUI.Fonts.smallFont().size)
        border = JBUI.Borders.empty(6, 8)
    }
    /** The command in a box that scrolls sideways when a line is wider than the room, never wrapping inside a line. */
    val commandView = com.intellij.ui.components.JBScrollPane(command).apply {
        horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        border = JBUI.Borders.customLine(JBColor.border())
        name = "agents.command"
    }
    val where = JBLabel().apply { foreground = JBColor.GRAY; font = JBUI.Fonts.smallFont() }
    /** An agent's own note (Claude Code's plugin), when it has one. */
    val note = WrappedLabel().apply { foreground = JBColor.GRAY; font = JBUI.Fonts.smallFont() }

    /** Loaded off the UI thread; null until then. */
    @Volatile private var credential: String? = null

    init {
        agent.addActionListener { render() }
        render()
        controller.credentialAsync { loaded -> credential = loaded; SwingUtilities.invokeLater { render() } }
    }

    fun endpoint() = "http://127.0.0.1:${prefs.port}/mcp"

    /** The command as the screen shows it: masked, always. */
    fun render() {
        val selected = agent.selectedItem as? Agent ?: return
        command.text = AgentsTabModel.shown(selected, endpoint())
        command.caretPosition = 0
        val config = AgentConfigs.render(selected, endpoint(), AgentsTabModel.MASK)
        where.text = config.where?.let { "Paste into $it" } ?: "Run in a terminal"
        note.html = config.note?.let { it.replace("&", "&amp;").replace("<", "&lt;") }.orEmpty()
    }

    /** True when it was copied (false while the credential is still being read). */
    fun copyCommand(): Boolean {
        val selected = agent.selectedItem as? Agent ?: return false
        val known = credential ?: return false
        CopyPasteManager.getInstance().setContents(StringSelection(AgentsTabModel.toCopy(selected, endpoint(), known)))
        return true
    }

    fun copyPrompt(): Boolean {
        val selected = agent.selectedItem as? Agent ?: return false
        val known = credential ?: return false
        CopyPasteManager.getInstance().setContents(StringSelection(AgentConfigs.prompt(selected, endpoint(), known)))
        return true
    }

    fun regenerated(fresh: String) { credential = fresh; SwingUtilities.invokeLater { render() } }
}
