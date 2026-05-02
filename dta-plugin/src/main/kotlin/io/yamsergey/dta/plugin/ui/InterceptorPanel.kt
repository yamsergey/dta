package io.yamsergey.dta.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import io.yamsergey.dta.plugin.DtaService
import io.yamsergey.dta.plugin.DtaService.DtaServiceListener
import io.yamsergey.dta.plugin.DtaService.SidekickInfo
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities

/**
 * "Interceptor" tab — authoring + lifecycle + log surface for the network
 * interceptor JS scripts. Mirrors the layout of every other panel: reads
 * the active package from {@link DtaService#getSelectedApp()}, and reacts
 * to package changes by swapping the editor's document to that package's
 * persisted script.
 *
 * <h3>Layout</h3>
 *
 * <pre>
 *  ┌──────────────────────────────────────────────────────────────┐
 *  │ Script editor (60%)                                          │
 *  │  - EditorEx, JS highlighting via TextMate when available     │
 *  │  - PlainTextFileType fallback otherwise (still real editor)  │
 *  ├──────────────────────────────────────────────────────────────┤
 *  │ Toolbar  [Install] [Clear] [Templates▾]   sidekick=X.Y.Z     │
 *  │ status: "installed (479 chars)" / "compile error: ..."       │
 *  ├──────────────────────────────────────────────────────────────┤
 *  │ Log stream (40%)                                             │
 *  │  - JBList tail of interceptor_logs                           │
 *  │  - polled every 1s while the panel is showing                │
 *  └──────────────────────────────────────────────────────────────┘
 * </pre>
 */
class InterceptorPanel : JPanel(BorderLayout()), DtaServiceListener {

    private val log = Logger.getInstance(InterceptorPanel::class.java)
    private val service = DtaService.getInstance()
    private val mapper = ObjectMapper()

    // Editor (created on AS init thread; safe at panel-construction time
    // because DtaToolWindowFactory runs on EDT).
    private val editor: EditorEx = createEditor()

    // Toolbar
    private val installButton = JButton("Install")
    private val clearButton = JButton("Clear")
    private val templatesCombo = JComboBox(TEMPLATE_NAMES.toTypedArray()).apply {
        toolTipText = "Insert a starter template into the editor"
    }
    private val sidekickChip = JBLabel("sidekick=—").apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        foreground = JBColor.GRAY
    }
    private val statusLabel = JBLabel(" ").apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
    }

    // Log stream
    private val logModel = DefaultListModel<LogEntry>()
    private val logList = JBList(logModel).apply {
        cellRenderer = LogCellRenderer()
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        // Keyboard navigation alone shouldn't break auto-scroll; only a
        // user-driven scroll up does.
        setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION)
    }
    private val autoScrollToggle = JCheckBox("Auto-scroll", true)
    private val clearLogsButton = JButton("Clear view").apply {
        toolTipText = "Clears local log view only — sidekick's ring buffer is unaffected"
    }
    private val filterCombo = JComboBox(arrayOf("ALL", "LOG", "ERROR")).apply {
        toolTipText = "Filter log level"
    }

    /** Largest seq we've fetched — passed back as `since` to page forward. */
    @Volatile private var lastSeenSeq: Long = 0

    /** Polling future; non-null while the panel is actively tailing. */
    private var pollFuture: ScheduledFuture<*>? = null
    private val poller = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dta-interceptor-poll").apply { isDaemon = true }
    }

    /**
     * The package the editor is currently displaying a script for.
     * Tracked so we know when to persist the editor contents (the package
     * we're leaving) before swapping in the destination package's script.
     */
    @Volatile private var displayedPackage: String? = null

    init {
        // Editor wraps in a panel so the splitter can size it
        val editorPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Script (JavaScript)")
            add(editor.component, BorderLayout.CENTER)
        }

        // Toolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(installButton)
            add(clearButton)
            add(JBLabel("Templates:"))
            add(templatesCombo)
            add(javax.swing.Box.createHorizontalStrut(16))
            add(sidekickChip)
            add(javax.swing.Box.createHorizontalStrut(16))
            add(statusLabel)
        }
        installButton.addActionListener { onInstall() }
        clearButton.addActionListener { onClear() }
        templatesCombo.addActionListener {
            val idx = templatesCombo.selectedIndex
            if (idx > 0) {
                pasteTemplate(idx)
                // Snap back to the placeholder so re-selecting fires again
                templatesCombo.selectedIndex = 0
            }
        }

        // Log stream
        val logToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JBLabel("Filter:"))
            add(filterCombo)
            add(autoScrollToggle)
            add(clearLogsButton)
        }
        clearLogsButton.addActionListener {
            // Local clear only — keep lastSeenSeq so we don't re-pull
            // historical entries. We just stop showing them.
            logModel.clear()
        }
        filterCombo.addActionListener { /* no-op — filter is applied at render time via repaint */
            logList.repaint()
        }
        val logPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Logs")
            add(logToolbar, BorderLayout.NORTH)
            add(JBScrollPane(logList), BorderLayout.CENTER)
        }

        val centerSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, editorPanel, logPanel).apply {
            resizeWeight = 0.6
            border = null
        }

        add(toolbar, BorderLayout.NORTH)
        add(centerSplit, BorderLayout.CENTER)

        service.addListener(this)
        // Pick up whatever package is already selected on construction.
        SwingUtilities.invokeLater { syncToSelectedPackage() }
    }

    // ------------------------------------------------------------------
    // Showing/hiding lifecycle — only poll while visible. AS reuses panels
    // on tab switches so the "isShowing" property is the cheapest signal.
    // ------------------------------------------------------------------

    override fun addNotify() {
        super.addNotify()
        startPolling()
    }

    override fun removeNotify() {
        stopPolling()
        super.removeNotify()
    }

    // ------------------------------------------------------------------
    // DtaServiceListener — pick up sidekick version + handle app changes
    // ------------------------------------------------------------------

    override fun onSidekickInfoChanged(info: SidekickInfo?) {
        SwingUtilities.invokeLater {
            sidekickChip.text = "sidekick=" + (info?.sidekickVersion ?: "—")
            // Active package may have just changed too. When the user
            // selects a different app, DtaService refreshes sidekick
            // info — same trigger lets us re-sync the editor.
            syncToSelectedPackage()
        }
    }

    override fun onConnectionChanged(status: String, connected: Boolean) {
        SwingUtilities.invokeLater { syncToSelectedPackage() }
    }

    // ------------------------------------------------------------------
    // Sync: sidekick is the source of truth. On app switch we ask
    // /api/interceptor what's currently running and load that into the
    // editor. No IDE-side persistence — the running script lives in the
    // sidekick AAR's filesDir and survives app process restart there.
    // ------------------------------------------------------------------

    private fun syncToSelectedPackage() {
        val pkg = service.selectedApp?.packageName()
        if (pkg == displayedPackage) return  // no change

        if (pkg == null) {
            setEditorText("// Select an app from the toolbar to author its interceptor script.\n")
            displayedPackage = null
            statusLabel.text = " "
            installButton.isEnabled = false
            clearButton.isEnabled = false
            return
        }

        displayedPackage = pkg
        installButton.isEnabled = true
        clearButton.isEnabled = true
        statusLabel.text = "loading…"

        val device = service.selectedDevice?.serial()
        ApplicationManager.getApplication().executeOnPooledThread {
            val running: String? = try {
                val resp = service.getInterceptor(pkg, device)
                val node = mapper.readTree(resp)
                if (node.path("installed").asBoolean(false))
                    node.path("script").asText("").takeIf { it.isNotEmpty() }
                else null
            } catch (e: Exception) {
                log.debug("getInterceptor failed for $pkg: ${e.message}")
                null
            }
            // Race guard: user may have switched packages while the
            // network call was in flight. Only apply if still on the
            // same package.
            onEdt {
                if (displayedPackage != pkg) return@onEdt
                if (running != null) {
                    setEditorText(running)
                    statusLabel.text = "loaded running script (${running.length} chars)"
                } else {
                    setEditorText(defaultStarterScript(pkg))
                    statusLabel.text = " "
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Toolbar actions
    // ------------------------------------------------------------------

    private fun onInstall() {
        val pkg = service.selectedApp?.packageName() ?: return
        val device = service.selectedDevice?.serial()
        val source = editor.document.text
        if (source.isBlank()) {
            statusLabel.text = "(empty script — nothing to install)"
            return
        }
        statusLabel.text = "installing…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val resp: String = try {
                service.setInterceptor(pkg, device, source)
            } catch (e: Exception) {
                onEdt { statusLabel.text = "install failed: ${e.message ?: e}" }
                return@executeOnPooledThread
            }
            // Parse the response: { installed, size, sidekickVersion } or { installed:false, error }
            try {
                val node: JsonNode = mapper.readTree(resp)
                val installed = node.path("installed").asBoolean(false)
                val sidekickVersion = node.path("sidekickVersion").asText("")
                onEdt {
                    if (installed) {
                        val size = node.path("size").asInt(source.length)
                        val v = if (sidekickVersion.isNotEmpty()) " [sidekick=$sidekickVersion]" else ""
                        statusLabel.text = "installed ($size chars)$v"
                        if (sidekickVersion.isNotEmpty()) sidekickChip.text = "sidekick=$sidekickVersion"
                    } else {
                        val err = node.path("error").asText("install failed")
                        statusLabel.text = "compile error: $err"
                    }
                }
            } catch (e: Exception) {
                onEdt { statusLabel.text = "install OK (response parse failed): ${e.message}" }
            }
        }
    }

    private fun onClear() {
        val pkg = service.selectedApp?.packageName() ?: return
        val device = service.selectedDevice?.serial()
        statusLabel.text = "clearing…"
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                service.clearInterceptor(pkg, device)
                onEdt { statusLabel.text = "cleared" }
            } catch (e: Exception) {
                onEdt { statusLabel.text = "clear failed: ${e.message ?: e}" }
            }
        }
    }

    private fun pasteTemplate(idx: Int) {
        val tpl = TEMPLATES.getOrNull(idx) ?: return
        setEditorText(tpl)
    }

    // ------------------------------------------------------------------
    // Editor: TextMate JS highlighting → PlainTextFileType fallback.
    // ------------------------------------------------------------------

    private fun createEditor(): EditorEx {
        val factory = EditorFactory.getInstance()
        val document = factory.createDocument("")
        val ed = factory.createEditor(document) as EditorEx

        val ftm = FileTypeManager.getInstance()
        // Resolve JS file type. AS bundles a TextMate plugin which registers
        // .js — that gives us syntax colours. If neither lookup yields a
        // real file type, fall back to plain text. Same widget either way.
        val jsType = ftm.findFileTypeByName("JavaScript")
            ?: ftm.getFileTypeByExtension("js").takeUnless { it is UnknownFileType }
            ?: PlainTextFileType.INSTANCE
        try {
            ed.highlighter = EditorHighlighterFactory.getInstance()
                .createEditorHighlighter(null, jsType)
        } catch (e: Exception) {
            log.debug("JS highlighter failed (${e.message}); editor stays in plain mode")
        }
        ed.settings.apply {
            isLineNumbersShown = true
            isFoldingOutlineShown = true
            isUseSoftWraps = false
            isAutoCodeFoldingEnabled = false
            isVirtualSpace = false
            isLineMarkerAreaShown = true
            isAdditionalPageAtBottom = false
            isCaretRowShown = true
        }
        return ed
    }

    private fun setEditorText(text: String) {
        ApplicationManager.getApplication().runWriteAction {
            editor.document.setText(text)
        }
    }

    // ------------------------------------------------------------------
    // Log stream — poll every 1s while showing.
    // ------------------------------------------------------------------

    private fun startPolling() {
        if (pollFuture != null) return
        pollFuture = poller.scheduleWithFixedDelay({
            try { tickLogs() } catch (e: Exception) { log.debug("log poll failed: ${e.message}") }
        }, 0, 1, TimeUnit.SECONDS)
    }

    private fun stopPolling() {
        pollFuture?.cancel(false)
        pollFuture = null
    }

    private fun tickLogs() {
        if (!isShowing) return
        val pkg = service.selectedApp?.packageName() ?: return
        val device = service.selectedDevice?.serial()
        val resp = service.getInterceptorLogs(pkg, device, lastSeenSeq)
        val node = try { mapper.readTree(resp) } catch (e: Exception) { return }
        val entries = node.path("entries")
        if (!entries.isArray || entries.isEmpty) return
        val newOnes = mutableListOf<LogEntry>()
        for (e in entries) {
            val seq = e.path("seq").asLong()
            val ts = e.path("ts").asLong()
            val level = e.path("level").asText("LOG")
            val text = e.path("text").asText("")
            newOnes.add(LogEntry(seq, ts, level, text))
            if (seq > lastSeenSeq) lastSeenSeq = seq
        }
        if (newOnes.isEmpty()) return
        SwingUtilities.invokeLater {
            for (le in newOnes) {
                if (matchesFilter(le)) logModel.addElement(le)
            }
            // Trim the model so it doesn't grow forever — same horizon as
            // the sidekick ring buffer.
            while (logModel.size > 1024) logModel.remove(0)
            if (autoScrollToggle.isSelected && logModel.size > 0) {
                logList.ensureIndexIsVisible(logModel.size - 1)
            }
        }
    }

    private fun matchesFilter(e: LogEntry): Boolean {
        return when (filterCombo.selectedItem as? String) {
            "LOG" -> e.level == "LOG"
            "ERROR" -> e.level == "ERROR"
            else -> true
        }
    }

    // ------------------------------------------------------------------
    // Helpers + types
    // ------------------------------------------------------------------

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeLater(block)

    private data class LogEntry(val seq: Long, val ts: Long, val level: String, val text: String)

    private class LogCellRenderer : JBLabel(), ListCellRenderer<LogEntry> {
        private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS")
        init {
            isOpaque = true
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            border = BorderFactory.createEmptyBorder(0, 4, 0, 4)
        }
        override fun getListCellRendererComponent(
            list: JList<out LogEntry>?, value: LogEntry?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            value ?: run { text = ""; return this }
            text = "${tsFmt.format(Date(value.ts))}  [${value.level}]  ${value.text}"
            background = if (isSelected) list?.selectionBackground ?: JBColor.background()
                         else JBColor.background()
            foreground = when {
                isSelected -> list?.selectionForeground ?: JBColor.foreground()
                value.level == "ERROR" -> JBColor(Color(0xC7, 0x4A, 0x32), Color(0xFF, 0x6E, 0x40))
                else -> JBColor.foreground()
            }
            return this
        }
    }

    companion object {
        // ----- Templates -----
        // Indexed: 0 is the placeholder ("— pick template —"), 1..N are real
        // entries. Selecting 0 is a no-op; selecting any other index pastes
        // the matching string into the editor.

        private val TEMPLATE_NAMES = listOf(
            "— pick template —",
            "Empty",
            "Log only",
            "Rewrite auth header",
            "Drop on URL match",
            "Latency injection",
        )

        private val TEMPLATES = listOf(
            "",  // placeholder slot — never used
            "// Empty interceptor — handlers pass-through.\n",
            """
                |// Log every HTTP request/response and every WebSocket frame.
                |function onRequest(req)   { log("→", req.method, req.url); return req; }
                |function onResponse(resp) { log("←", resp.status, resp.statusMessage); return resp; }
                |function onWsSend(f)      { if (f.text) log("ws→", f.text.slice(0, 80)); return f; }
                |function onWsReceive(f)   { if (f.text) log("ws←", f.text.slice(0, 80)); return f; }
                |""".trimMargin(),
            """
                |// Rewrite the Authorization header on every outbound request.
                |function onRequest(req) {
                |  req.headers["Authorization"] = "Bearer DEV_TOKEN";
                |  return req;
                |}
                |""".trimMargin(),
            """
                |// Drop requests whose URL contains a substring (returns synthetic 499 to the app).
                |function onRequest(req) {
                |  if (req.url.indexOf("/analytics") >= 0) {
                |    log("dropping", req.url);
                |    return null;
                |  }
                |  return req;
                |}
                |""".trimMargin(),
            """
                |// Sleep before delivering responses to /api/* so you can observe slow-network UX.
                |function onResponse(resp) {
                |  // Adjust the delay or the URL match to taste.
                |  sleep(500);
                |  return resp;
                |}
                |""".trimMargin(),
        )

        private fun defaultStarterScript(pkg: String) =
            """
                |// Interceptor for ${pkg}
                |//
                |// Edit, then click Install. Live logs from log() show below.
                |// See the `interceptor_set` MCP tool description for the
                |// req/resp/frame wrapper shapes and helpers.
                |
                |function onRequest(req)   { return req; }
                |function onResponse(resp) { return resp; }
                |function onWsSend(f)      { return f; }
                |function onWsReceive(f)   { return f; }
                |""".trimMargin()
    }
}
