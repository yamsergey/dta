package io.yamsergey.dta.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.plugin.DtaService
import io.yamsergey.dta.plugin.DtaService.DaemonInfo
import io.yamsergey.dta.plugin.DtaService.DtaServiceListener
import io.yamsergey.dta.plugin.DtaService.SidekickInfo
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Tab that shows the active dta-daemon's identity and lifecycle controls.
 *
 * <p>Surfaces:
 * <ul>
 *   <li>plugin and daemon versions side-by-side, with a warning banner when
 *       the major.minor.patch differs — the most common cause of "nothing
 *       works" after a partial sideload</li>
 *   <li>pid, port, base URL, uptime — enough to identify which daemon
 *       process is on the other end without grepping {@code ps}</li>
 *   <li>a Restart button that goes through {@link DtaService#restartDaemon}
 *       so port-forwards and SSE listeners are torn down cleanly</li>
 * </ul></p>
 */
class DaemonPanel : JPanel(BorderLayout()), DtaServiceListener {

    private val service = DtaService.getInstance()

    private val pluginVersion: String = runCatching {
        PluginManagerCore.getPlugin(PluginId.getId("io.yamsergey.dta"))?.version ?: "(unknown)"
    }.getOrDefault("(unknown)")

    private val warningBanner = WarningBanner()

    private val pluginVersionLabel = JBLabel(pluginVersion)
    private val daemonVersionLabel = JBLabel("—")
    private val sidekickVersionLabel = JBLabel("—")
    private val sidekickPackageLabel = JBLabel("—")
    private val pidLabel = JBLabel("—")
    private val portLabel = JBLabel("—")
    private val baseUrlLabel = JBLabel("—")
    private val uptimeLabel = JBLabel("—")

    private val restartButton = JButton("Restart Daemon", AllIcons.Actions.Restart).apply {
        toolTipText = "Stop the current daemon and start a fresh one"
        isEnabled = false
        addActionListener { service.restartDaemon() }
    }

    @Volatile private var info: DaemonInfo? = null
    @Volatile private var sidekick: SidekickInfo? = null

    private val uptimeTicker = Timer(1_000) { refreshUptime() }

    init {
        border = JBUI.Borders.empty(12)

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        warningBanner.alignmentX = Component.LEFT_ALIGNMENT
        content.add(warningBanner)
        content.add(Box.createVerticalStrut(8))

        content.add(buildInfoGrid().apply { alignmentX = Component.LEFT_ALIGNMENT })
        content.add(Box.createVerticalStrut(16))

        val buttonRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            add(restartButton)
            add(Box.createHorizontalGlue())
        }
        content.add(buttonRow)
        content.add(Box.createVerticalGlue())

        add(content, BorderLayout.NORTH)

        service.addListener(this)
        onDaemonInfoChanged(service.daemonInfo)
        onSidekickInfoChanged(service.sidekickInfo)
        uptimeTicker.start()
    }

    private fun buildInfoGrid(): JPanel {
        val grid = JPanel(GridBagLayout())
        val labelInsets = Insets(2, 0, 2, 12)
        val valueInsets = Insets(2, 0, 2, 0)

        fun row(rowIdx: Int, label: String, value: JBLabel) {
            val labelComponent = JBLabel("$label:").apply { foreground = JBColor.GRAY }
            grid.add(labelComponent, GridBagConstraints().apply {
                gridx = 0; gridy = rowIdx; anchor = GridBagConstraints.WEST; insets = labelInsets
            })
            grid.add(value, GridBagConstraints().apply {
                gridx = 1; gridy = rowIdx; anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; insets = valueInsets
            })
        }

        row(0, "Plugin version", pluginVersionLabel)
        row(1, "Daemon version", daemonVersionLabel)
        row(2, "Sidekick version", sidekickVersionLabel)
        row(3, "Sidekick app", sidekickPackageLabel)
        row(4, "PID", pidLabel)
        row(5, "Port", portLabel)
        row(6, "Base URL", baseUrlLabel)
        row(7, "Uptime", uptimeLabel)

        return grid
    }

    override fun onDaemonInfoChanged(info: DaemonInfo?) {
        this.info = info
        restartButton.isEnabled = info != null

        if (info == null) {
            daemonVersionLabel.text = "—"
            pidLabel.text = "—"
            portLabel.text = "—"
            baseUrlLabel.text = "—"
            uptimeLabel.text = "—"
        } else {
            daemonVersionLabel.text = info.version.ifEmpty { "(unknown)" }
            pidLabel.text = info.pid.toString()
            portLabel.text = info.port.toString()
            baseUrlLabel.text = info.baseUrl
            refreshUptime()
        }

        updateMismatchWarning()
    }

    override fun onSidekickInfoChanged(info: SidekickInfo?) {
        this.sidekick = info
        if (info == null) {
            sidekickVersionLabel.text = "—"
            sidekickPackageLabel.text = "(no app connected)"
        } else {
            sidekickVersionLabel.text = info.sidekickVersion.ifEmpty { "(unknown)" }
            sidekickPackageLabel.text = "${info.packageName} on ${info.deviceSerial}"
        }
        updateMismatchWarning()
    }

    private fun refreshUptime() {
        val started = info?.startedAt ?: return
        val secs = ((System.currentTimeMillis() - started) / 1000).coerceAtLeast(0)
        uptimeLabel.text = formatUptime(secs)
    }

    /**
     * Surfaces any pair-wise mismatch between plugin / daemon / sidekick on
     * the `major.minor.patch` segment (everything before {@code -SNAPSHOT[.N]}).
     * Empty / placeholder versions ({@code "(unknown)"}, {@code "standalone"})
     * are skipped — we only flag when we have two real versions to compare.
     */
    private fun updateMismatchWarning() {
        val pluginV = pluginVersion
        val daemonV = info?.version
        val sidekickV = sidekick?.sidekickVersion?.takeIf { it.isNotEmpty() }

        val mismatches = mutableListOf<String>()
        compare("Plugin", pluginV, "Daemon", daemonV)?.let(mismatches::add)
        compare("Plugin", pluginV, "Sidekick", sidekickV)?.let(mismatches::add)
        compare("Daemon", daemonV, "Sidekick", sidekickV)?.let(mismatches::add)

        if (mismatches.isEmpty()) {
            warningBanner.hideWarning()
        } else {
            warningBanner.showWarning(
                "Version mismatch detected — API contracts may differ.<br>" +
                        mismatches.joinToString("<br>") { "• $it" } +
                        "<br>Rebuild the app and/or restart the daemon to bring them in sync."
            )
        }
    }

    /** Returns a one-line description if {@code a} and {@code b} differ on base version, else null. */
    private fun compare(aLabel: String, a: String?, bLabel: String, b: String?): String? {
        val aBase = baseVersion(a ?: return null)
        val bBase = baseVersion(b ?: return null)
        if (aBase.isEmpty() || bBase.isEmpty()) return null
        if (aBase == bBase) return null
        return "$aLabel $a vs $bLabel $b"
    }

    private fun baseVersion(v: String): String =
        v.replace(Regex("-SNAPSHOT\\.\\d+$"), "")
            .replace("-SNAPSHOT", "")
            .trimEnd('.')
            .let { if (it == "standalone" || it == "(unknown)") "" else it }

    private fun formatUptime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h ${m}m ${s}s"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    fun dispose() {
        uptimeTicker.stop()
        service.removeListener(this)
    }

    /**
     * Yellow banner that's hidden until something has to be said. Kept as a
     * nested class so callers don't have to manage visibility/text/repaint
     * separately — three concerns that always travel together.
     */
    private class WarningBanner : JPanel(BorderLayout()) {
        private val label = JBLabel("")

        init {
            isVisible = false
            background = JBColor(0xFFF4D6, 0x4A3C1E)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(0xE0B850, 0xB58A2A), 1),
                JBUI.Borders.empty(8, 12)
            )
            label.icon = AllIcons.General.BalloonWarning
            label.foreground = JBColor(0x6A4A00, 0xE6C779)
            add(label, BorderLayout.CENTER)
            isOpaque = true
        }

        fun showWarning(message: String) {
            label.text = "<html>$message</html>"
            isVisible = true
            revalidate()
            repaint()
        }

        fun hideWarning() {
            if (!isVisible) return
            isVisible = false
            revalidate()
            repaint()
        }
    }
}
