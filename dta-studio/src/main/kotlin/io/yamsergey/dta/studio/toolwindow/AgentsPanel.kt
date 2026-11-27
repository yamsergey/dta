package io.yamsergey.dta.studio.toolwindow

import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
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
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Settings › Tools › DTA › Agents (FR-018/FR-019, feature 073 boards 2 and 7): Agent server, Connect an agent, Secrets.
 * The credential is never on screen; Copy command and Copy prompt put it in the clipboard. DTA never writes into an agent's files.
 */
class AgentsPanel(
    private val controller: AgentServerController,
    private val prefs: AgentServerPrefs,
    scope: CoroutineScope,
    private val redaction: io.yamsergey.dta.studio.agents.AgentRedactionController? = null,
) : JPanel(BorderLayout()), javax.swing.Scrollable {
    // D-107: inside Settings' own scroll pane the page takes the dialog's width (it wraps there) instead of keeping the width it once had.
    override fun getPreferredScrollableViewportSize() = preferredSize
    override fun getScrollableUnitIncrement(r: java.awt.Rectangle?, o: Int, d: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(r: java.awt.Rectangle?, o: Int, d: Int) = JBUI.scale(64)
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
    // Never wider than a narrow page: the host's scroll pane fills the width it has; the texts wrap to it.
    override fun getPreferredSize(): java.awt.Dimension = super.getPreferredSize().also { it.width = minOf(it.width, JBUI.scale(280)) }

    /** 072 (FR-015): the Secrets group — one setting for every agent path and exports; the daemon holds it. */
    private val mask = JBCheckBox("Mask secrets in what agents read and in exports", true).apply { isEnabled = false }
    private val maskError = JBLabel().apply { foreground = JBColor.RED; isVisible = false }
    /** D-107: every text that wraps takes the page's width (see [wrapAll]) instead of one of its own. */
    private val wrapped = mutableListOf<WrappedLabel>()
    private fun help(text: String) = WrappedLabel(text, indent = JBUI.scale(24)).apply { foreground = JBColor.GRAY; font = JBUI.Fonts.smallFont() }.also { wrapped += it }

    private val enabled = JBCheckBox("Serve DTA's tools to agents over HTTP", prefs.enabled)
    private val port = JBTextField(prefs.port.toString(), 6)
    private val stateLine = JBLabel()
    private val fix = ActionLink("") { port.requestFocusInWindow(); port.selectAll() }.apply { isVisible = false }
    private val connect = ConnectAgentBlock(controller, prefs).also { wrapped += it.note }

    init {
        enabled.addActionListener { controller.setEnabled(enabled.isSelected) }
        val applyPort = JButton("Apply").apply {
            addActionListener { port.text.trim().toIntOrNull()?.let(controller::setPort) ?: controller.setPort(-1) }
        }
        val copy = JButton("Copy command").apply {
            toolTipText = "Copy the command for the chosen agent, with the credential"
            addActionListener { connect.copyCommand() }
        }
        // An instruction for the agent to configure the server itself.
        val copyPrompt = JButton("Copy prompt").apply {
            toolTipText = "Copy an instruction to paste into the agent, so it sets up the DTA server itself"
            addActionListener { connect.copyPrompt() }
        }
        val regenerate = ActionLink("Regenerate credential") {
            val answer = Messages.showYesNoDialog(
                this, "Agents configured with the current credential will stop working until you copy the new configuration.",
                "Regenerate DTA Agent Credential", null,
            )
            if (answer == Messages.YES) {
                controller.regenerate { fresh -> connect.regenerated(fresh) }
            }
        }
        val portRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { add(port); add(applyPort) }
        val stateRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { add(stateLine); add(fix) }
        val agentRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(connect.agent) }
        val copyRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { add(copy); add(copyPrompt); add(regenerate) }
        val form = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator("Agent server"))
            .addComponent(enabled)
            .addLabeledComponent("Port", portRow)
            .addLabeledComponent("State", stateRow)
            .addComponent(TitledSeparator("Connect an agent"))
            .addLabeledComponent("Agent", agentRow)
            .addComponent(connect.where)
            .addComponent(connect.commandView)
            .addComponent(copyRow)
            .addComponent(help("The credential is kept in the IDE's password safe. DTA never writes into an agent's files."))
            .addComponent(connect.note)
            .addComponent(TitledSeparator("Secrets"))
            .addComponent(mask)
            .addComponent(help("Applies to this server, dta mcp and the CLI. A masked value keeps its length and a fingerprint, <code>#3f9a2c81d04e</code>: the same value has the same fingerprint everywhere, so an agent can tell an old token from a new one and search where it came from. The DTA tabs always show values as sent."))
            .addComponent(help("Keeps secrets out of an agent's context, logs and shared files; it does not stop a program that reads DTA's files on this computer."))
            .addComponent(maskError)
            .panel.apply { border = JBUI.Borders.empty(12) }
        // The form takes the width of its viewport (long notes wrap instead of running off the page).
        val tracking = object : JPanel(BorderLayout()), javax.swing.Scrollable {
            override fun getPreferredScrollableViewportSize() = preferredSize
            override fun getScrollableUnitIncrement(r: java.awt.Rectangle?, o: Int, d: Int) = JBUI.scale(16)
            override fun getScrollableBlockIncrement(r: java.awt.Rectangle?, o: Int, d: Int) = JBUI.scale(64)
            override fun getScrollableTracksViewportWidth() = true
            override fun getScrollableTracksViewportHeight() = false
        }.apply { add(form, BorderLayout.NORTH) }
        // The wrapping texts follow the width the page has (the dialog's, less the form's margins).
        tracking.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) = wrapAll(tracking.width - form.insets.left - form.insets.right)
        })
        add(JBScrollPane(tracking).apply { border = JBUI.Borders.empty(); horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER }, BorderLayout.CENTER)
        redaction?.let { r ->
            mask.addActionListener { mask.isEnabled = false; r.set(mask.isSelected) }
            scope.launch {
                r.state.collect { s ->
                    withContext(Dispatchers.Main) {
                        s.on?.let { mask.isSelected = it }
                        mask.isEnabled = s.on != null
                        maskError.text = s.error.orEmpty()
                        maskError.isVisible = s.error != null
                    }
                }
            }
            r.refresh()
        }
        scope.launch {
            controller.state.collect { state -> withContext(Dispatchers.Main) { show(state) } }
        }
    }

    private fun show(state: AgentServerState) {
        stateLine.text = (state as? AgentServerState.On)?.let { "Serving at ${it.url}" } ?: state.line
        stateLine.foreground = when (state) {
            is AgentServerState.On -> DtaColors.of("live")
            is AgentServerState.Failed -> DtaColors.of("status-server")
            else -> JBColor.foreground()
        }
        fix.text = state.action.orEmpty()
        fix.isVisible = state.action != null
        enabled.isSelected = prefs.enabled
        port.text = prefs.port.toString()
        connect.render()
    }

    private fun wrapAll(available: Int) { if (available > 0) wrapped.forEach { it.wrapTo(available) } }
}
