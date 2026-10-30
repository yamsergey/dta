package io.yamsergey.dta.studio.toolwindow

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.present.agents.Agent
import io.yamsergey.dta.present.agents.AgentConfigs
import io.yamsergey.dta.present.agents.AgentServerState
import io.yamsergey.dta.studio.agents.AgentServerController
import io.yamsergey.dta.studio.agents.AgentServerPrefs
import io.yamsergey.dta.studio.ui.DtaColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JPanel

/**
 * The Agents view (FR-018/FR-019): the agent server's switch, port and state, and the
 * configuration each agent needs, ready to copy. DTA never writes into an agent's files.
 */
class AgentsPanel(
    private val controller: AgentServerController,
    private val prefs: AgentServerPrefs,
    scope: CoroutineScope,
) : JPanel(BorderLayout()) {
    private val enabled = JBCheckBox("Serve DTA's tools to agents over HTTP", prefs.enabled)
    private val port = JBTextField(prefs.port.toString(), 6)
    private val stateLine = JBLabel()
    private val fix = ActionLink("") { port.requestFocusInWindow(); port.selectAll() }.apply { isVisible = false }
    private val agent = ComboBox(Agent.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.title }
    }
    private val config = JBTextArea(6, 60).apply { isEditable = false; lineWrap = true; font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size) }
    private val where = JBLabel().apply { foreground = JBColor.GRAY }
    private val note = JBLabel().apply { foreground = JBColor.GRAY }

    init {
        enabled.addActionListener { controller.setEnabled(enabled.isSelected) }
        val applyPort = JButton("Apply").apply {
            addActionListener { port.text.trim().toIntOrNull()?.let(controller::setPort) ?: controller.setPort(-1) }
        }
        agent.addActionListener { renderConfig() }
        val copy = JButton("Copy").apply {
            addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(config.text)) }
        }
        // An instruction for the agent to configure the server itself.
        val copyPrompt = JButton("Copy prompt").apply {
            toolTipText = "Copy an instruction to paste into the agent, so it sets up the DTA server itself"
            addActionListener {
                val selected = agent.selectedItem as? Agent ?: return@addActionListener
                CopyPasteManager.getInstance().setContents(StringSelection(AgentConfigs.prompt(selected, endpoint(), controller.credential())))
            }
        }
        val regenerate = ActionLink("Regenerate credential") {
            val answer = Messages.showYesNoDialog(
                this, "Agents configured with the current credential will stop working until you copy the new configuration.",
                "Regenerate DTA Agent Credential", null,
            )
            if (answer == Messages.YES) {
                controller.regenerate()
                renderConfig()
            }
        }
        val portRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { add(port); add(applyPort) }
        val stateRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { add(stateLine); add(fix) }
        val copyRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { add(copy); add(copyPrompt); add(regenerate) }
        val form = FormBuilder.createFormBuilder()
            .addComponent(enabled)
            .addLabeledComponent("Port", portRow)
            .addLabeledComponent("State", stateRow)
            .addSeparator()
            .addLabeledComponent("Agent", agent)
            .addComponent(where)
            .addComponent(JBScrollPane(config))
            .addComponent(copyRow)
            .addComponent(note)
            .panel.apply { border = JBUI.Borders.empty(12) }
        add(JBScrollPane(form), BorderLayout.CENTER)
        renderConfig()
        scope.launch {
            controller.state.collect { state -> withContext(Dispatchers.Main) { show(state) } }
        }
    }

    private fun show(state: AgentServerState) {
        stateLine.text = state.line
        stateLine.foreground = when (state) {
            is AgentServerState.On -> DtaColors.of("live")
            is AgentServerState.Failed -> DtaColors.of("status-server")
            else -> JBColor.foreground()
        }
        fix.text = state.action.orEmpty()
        fix.isVisible = state.action != null
        enabled.isSelected = prefs.enabled
        port.text = prefs.port.toString()
        renderConfig()
    }

    private fun renderConfig() {
        val selected = agent.selectedItem as? Agent ?: return
        val rendered = AgentConfigs.render(selected, endpoint(), controller.credential())
        config.text = rendered.text
        where.text = rendered.where?.let { "Paste into $it" } ?: "Run in a terminal"
        note.text = rendered.note?.let { "<html>${it.replace("&", "&amp;").replace("<", "&lt;")}</html>" }.orEmpty()
    }

    private fun endpoint() = "http://127.0.0.1:${prefs.port}/mcp"
}
