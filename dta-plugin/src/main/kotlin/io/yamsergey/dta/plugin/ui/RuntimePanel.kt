package io.yamsergey.dta.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import io.yamsergey.dta.plugin.DtaService
import io.yamsergey.dta.plugin.DtaService.DtaServiceListener
import tools.jackson.databind.ObjectMapper
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.*
import javax.swing.table.AbstractTableModel

/**
 * Top-level "Runtime" tab with sub-tabs: Data, Navigation, Lifecycle, Memory, ViewModels, AppFunctions.
 */
class RuntimePanel : JPanel(BorderLayout()), DtaServiceListener {

    private val service = DtaService.getInstance()
    private val dataPanel = DataPanel()
    private val navigationPanel = NavigationSubPanel()
    private val lifecyclePanel = LifecycleSubPanel()
    private val memoryPanel = MemorySubPanel()
    private val viewModelsPanel = ViewModelsSubPanel()
    private val appFunctionsPanel = AppFunctionsSubPanel()

    init {
        service.addListener(this)
        val subTabs = JBTabbedPane()
        subTabs.addTab("Data", dataPanel)
        subTabs.addTab("Navigation", navigationPanel)
        subTabs.addTab("Lifecycle", lifecyclePanel)
        subTabs.addTab("Memory", memoryPanel)
        subTabs.addTab("ViewModels", viewModelsPanel)
        subTabs.addTab("AppFunctions", appFunctionsPanel)
        add(subTabs, BorderLayout.CENTER)
    }

    override fun onDataStoreChanged(databasesJson: String?, prefsJson: String?) {
        dataPanel.onDataStoreChanged(databasesJson, prefsJson)
    }

    override fun onRuntimeChanged(lifecycleJson: String?, memoryJson: String?, threadsJson: String?, navBackstackJson: String?, navGraphJson: String?) {
        navigationPanel.update(navBackstackJson, navGraphJson)
        lifecyclePanel.update(lifecycleJson)
        memoryPanel.update(memoryJson, threadsJson)
    }

    override fun onViewModelsChanged(json: String?) {
        viewModelsPanel.update(json)
    }

    override fun onAppFunctionsChanged(json: String?) {
        appFunctionsPanel.update(json)
    }
}

// ========================================================================
// Navigation sub-panel
// ========================================================================

private class NavigationSubPanel : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(NavigationSubPanel::class.java)
    private val mapper = ObjectMapper()
    private val service = DtaService.getInstance()
    private val backstackArea = JBTextArea().apply {
        isEditable = false
        font = Font("Monospaced", Font.PLAIN, 12)
    }
    private val graphArea = JBTextArea().apply {
        isEditable = false
        font = Font("Monospaced", Font.PLAIN, 12)
    }

    // Action controls (populated from latest navigation_graph payload).
    private val destinationCombo = JComboBox<String>().apply { isEditable = true }
    private val deepLinkCombo = JComboBox<String>().apply { isEditable = true }
    private val paramsField = JTextField()
    private val navigateBtn = JButton("Navigate")
    private val openDeepLinkBtn = JButton("Open")
    private val statusLabel = JBLabel("Drive navigation from here.").apply {
        foreground = java.awt.Color.GRAY
    }

    init {
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
            resizeWeight = 0.4
            border = null
        }
        val backstackPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Backstack")
        }
        backstackPanel.add(JBScrollPane(backstackArea), BorderLayout.CENTER)

        val graphPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Navigation Graph")
        }
        graphPanel.add(JBScrollPane(graphArea), BorderLayout.CENTER)

        split.topComponent = backstackPanel
        split.bottomComponent = graphPanel
        add(split, BorderLayout.CENTER)
        add(buildActionsPanel(), BorderLayout.SOUTH)

        navigateBtn.addActionListener { onNavigateClicked() }
        openDeepLinkBtn.addActionListener { onOpenDeepLinkClicked() }
    }

    /**
     * Builds the bottom "Actions" pane: a destination row (combo + params
     * text + Navigate button) and a deep-link row (combo + Open button),
     * with a single shared status label below. Both combos are
     * {@code isEditable = true} so users can type a route the graph
     * scraper didn't pick up.
     */
    private fun buildActionsPanel(): JComponent {
        val outer = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Actions")
        }
        val rows = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

        val navigateRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        navigateRow.add(JBLabel("Destination:"))
        destinationCombo.preferredSize = java.awt.Dimension(220, destinationCombo.preferredSize.height)
        navigateRow.add(destinationCombo)
        navigateRow.add(JBLabel("Params (k=v, comma-sep):"))
        paramsField.preferredSize = java.awt.Dimension(180, paramsField.preferredSize.height)
        navigateRow.add(paramsField)
        navigateRow.add(navigateBtn)

        val deepLinkRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        deepLinkRow.add(JBLabel("Deep link URI:"))
        deepLinkCombo.preferredSize = java.awt.Dimension(360, deepLinkCombo.preferredSize.height)
        deepLinkRow.add(deepLinkCombo)
        deepLinkRow.add(openDeepLinkBtn)

        rows.add(navigateRow)
        rows.add(deepLinkRow)
        outer.add(rows, BorderLayout.CENTER)

        val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        statusRow.add(statusLabel)
        outer.add(statusRow, BorderLayout.SOUTH)
        return outer
    }

    /**
     * Refreshes the destination + deep-link combos from the latest graph
     * JSON. Iterates declared destinations + any deepLinks lists carried on
     * each destination. Preserves the user's current edit so a typed
     * (not-yet-committed) entry isn't wiped on the next poll tick.
     */
    private fun rebuildActionChoices(graph: tools.jackson.databind.JsonNode?) {
        if (graph == null) return
        val destinations = mutableListOf<String>()
        val deepLinks = mutableListOf<String>()
        graph.get("destinations")?.takeIf { it.isArray }?.forEach { dest ->
            dest.get("route")?.asText()?.takeIf { it.isNotBlank() }?.let { destinations.add(it) }
            dest.get("deepLinks")?.takeIf { it.isArray }?.forEach { dl ->
                dl.asText()?.takeIf { it.isNotBlank() }?.let { deepLinks.add(it) }
            }
        }
        replaceComboItems(destinationCombo, destinations)
        replaceComboItems(deepLinkCombo, deepLinks)
    }

    private fun replaceComboItems(combo: JComboBox<String>, items: List<String>) {
        val current = combo.editor.item?.toString() ?: ""
        combo.removeAllItems()
        items.forEach { combo.addItem(it) }
        // Preserve in-flight edit; otherwise default to the first listed item.
        if (current.isNotEmpty()) combo.selectedItem = current
        else if (items.isNotEmpty()) combo.selectedItem = items[0]
    }

    private fun onNavigateClicked() {
        val pkg = service.selectedApp?.packageName
        val device = service.selectedDevice?.serial()
        if (pkg == null || device == null) {
            setStatus("No app/device selected.", error = true)
            return
        }
        val destination = (destinationCombo.editor.item ?: "").toString().trim()
        if (destination.isEmpty()) {
            setStatus("Destination is empty.", error = true)
            return
        }
        val params = parseParams(paramsField.text)
        runOnBackground("navigate", error = "Navigate failed") {
            service.navigate(pkg, device, destination, params)
        }
    }

    private fun onOpenDeepLinkClicked() {
        val pkg = service.selectedApp?.packageName
        val device = service.selectedDevice?.serial()
        if (pkg == null || device == null) {
            setStatus("No app/device selected.", error = true)
            return
        }
        val uri = (deepLinkCombo.editor.item ?: "").toString().trim()
        if (uri.isEmpty()) {
            setStatus("Deep link URI is empty.", error = true)
            return
        }
        runOnBackground("open_deeplink", error = "Open deep link failed") {
            service.openDeepLink(pkg, device, uri)
        }
    }

    /** `a=1, b=hello world` → {a: "1", b: "hello world"}. Empty values OK. */
    private fun parseParams(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (segment in raw.split(',')) {
            val trimmed = segment.trim()
            if (trimmed.isEmpty()) continue
            val eq = trimmed.indexOf('=')
            if (eq < 0) {
                // tolerated — bare key with empty value
                out[trimmed] = ""
            } else {
                out[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
            }
        }
        return out
    }

    private fun runOnBackground(action: String, error: String, block: () -> String) {
        setStatus("$action…", error = false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = try {
                val json = block()
                interpretResult(json)
            } catch (e: Exception) {
                log.warn("$action failed", e)
                "$error: ${e.message}"
            }
            SwingUtilities.invokeLater { setStatus(text, error = text.startsWith("Error:")) }
        }
    }

    /**
     * Pulls the actionable message out of the sidekick response — either
     * "Navigated to <route>" / "Opened <uri>", or the error field if the
     * payload reported one.
     */
    private fun interpretResult(json: String): String {
        return try {
            val node = mapper.readTree(json)
            val err = node.get("error")?.asText()
            if (err != null) "Error: $err"
            else {
                val route = node.get("route")?.asText()
                val uri = node.get("uri")?.asText()
                when {
                    route != null -> "Navigated to $route"
                    uri != null -> "Opened $uri"
                    else -> "OK"
                }
            }
        } catch (e: Exception) { "Result: $json" }
    }

    private fun setStatus(text: String, error: Boolean) {
        statusLabel.text = text
        statusLabel.foreground = if (error) java.awt.Color(0xC0, 0x39, 0x2B) else java.awt.Color.GRAY
    }

    fun update(backstackJson: String?, graphJson: String?) {
        SwingUtilities.invokeLater {
            if (backstackJson != null) {
                try {
                    val node = mapper.readTree(backstackJson)
                    val error = node.get("error")?.stringValue()
                    if (error != null) {
                        backstackArea.text = error
                    } else {
                        val sb = StringBuilder()
                        val backstack = node.get("backstack")
                        val current = node.get("currentDestination")
                        if (current != null) {
                            sb.append("Current: ${current.get("route")?.asText() ?: "?"}")
                            sb.append(" (${current.get("navigatorName")?.asText() ?: ""})\n\n")
                        }
                        sb.append("Backstack (${node.get("size")?.asInt() ?: 0} entries):\n")
                        if (backstack != null && backstack.isArray) {
                            for ((i, entry) in backstack.withIndex()) {
                                val dest = entry.get("destination")
                                val route = dest?.get("route")?.asText() ?: "?"
                                val nav = dest?.get("navigatorName")?.asText() ?: ""
                                sb.append("  ${i + 1}. $route ($nav)\n")
                                val args = entry.get("arguments")
                                if (args != null && args.isObject && args.size() > 0) {
                                    for (prop in args.properties()) {
                                        sb.append("     ${prop.key} = ${prop.value.asText()}\n")
                                    }
                                }
                            }
                        }
                        backstackArea.text = sb.toString()
                    }
                } catch (e: Exception) {
                    backstackArea.text = "Error: ${e.message}"
                }
            }
            if (graphJson != null) {
                try {
                    val node = mapper.readTree(graphJson)
                    val error = node.get("error")?.stringValue()
                    if (error != null) {
                        graphArea.text = error
                    } else {
                        val sb = StringBuilder()
                        val graph = node.get("graph")
                        if (graph != null) {
                            sb.append("Destinations (${graph.get("destinationCount")?.asInt() ?: 0}):\n\n")
                            val dests = graph.get("destinations")
                            if (dests != null && dests.isArray) {
                                for (dest in dests) {
                                    val route = dest.get("route")?.asText() ?: dest.get("label")?.asText() ?: "?"
                                    val nav = dest.get("navigatorName")?.asText() ?: ""
                                    sb.append("  • $route ($nav)")
                                    val className = dest.get("className")?.asText()
                                    if (className != null) sb.append("  → $className")
                                    sb.append("\n")
                                    // Arguments
                                    val destArgs = dest.get("arguments")
                                    if (destArgs != null && destArgs.isArray) {
                                        for (arg in destArgs) {
                                            val name = arg.get("name")?.asText() ?: "?"
                                            val type = arg.get("type")?.asText() ?: ""
                                            val nullable = if (arg.get("nullable")?.asBoolean() == true) "?" else ""
                                            val default = arg.get("defaultValue")?.asText()
                                            sb.append("      arg: $name: $type$nullable")
                                            if (default != null) sb.append(" = $default")
                                            sb.append("\n")
                                        }
                                    }
                                    // Deep links
                                    val deepLinks = dest.get("deepLinks")
                                    if (deepLinks != null && deepLinks.isArray) {
                                        for (dl in deepLinks) {
                                            sb.append("      deepLink: ${dl.asText()}\n")
                                        }
                                    }
                                }
                            }
                        }
                        graphArea.text = sb.toString()
                        rebuildActionChoices(graph)
                    }
                } catch (e: Exception) {
                    graphArea.text = "Error: ${e.message}"
                }
            }
        }
    }
}

// ========================================================================
// Lifecycle sub-panel
// ========================================================================

private class LifecycleSubPanel : JPanel(BorderLayout()) {

    private val mapper = ObjectMapper()
    private val tableModel = LifecycleTableModel()
    private val table = JBTable(tableModel)

    init {
        table.autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    fun update(json: String?) {
        if (json == null) return
        SwingUtilities.invokeLater {
            try {
                val node = mapper.readTree(json)
                val activities = node.get("activities")
                if (activities != null && activities.isArray) {
                    tableModel.update(activities.toList())
                }
            } catch (_: Exception) {}
        }
    }

    private class LifecycleTableModel : AbstractTableModel() {
        private val cols = arrayOf("Activity", "State", "Task ID")
        private var data: List<tools.jackson.databind.JsonNode> = emptyList()

        fun update(nodes: List<tools.jackson.databind.JsonNode>) {
            data = nodes
            fireTableDataChanged()
        }

        override fun getRowCount() = data.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(col: Int) = cols[col]
        override fun getValueAt(row: Int, col: Int): Any? {
            val n = data[row]
            return when (col) {
                0 -> n.get("className")?.asText()?.substringAfterLast('.') ?: ""
                1 -> n.get("state")?.asText() ?: ""
                2 -> n.get("taskId")?.asInt()?.toString() ?: ""
                else -> ""
            }
        }
    }
}

// ========================================================================
// Memory + Threads sub-panel
// ========================================================================

private class MemorySubPanel : JPanel(BorderLayout()) {

    private val mapper = ObjectMapper()
    private val memoryArea = JBTextArea(6, 40).apply {
        isEditable = false
        font = Font("Monospaced", Font.PLAIN, 12)
    }
    private val threadTableModel = ThreadTableModel()
    private val threadTable = JBTable(threadTableModel)

    init {
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
            resizeWeight = 0.3
            border = null
        }

        val memPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Memory")
        }
        memPanel.add(JBScrollPane(memoryArea), BorderLayout.CENTER)

        val threadPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Threads")
        }
        threadTable.autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        threadPanel.add(JBScrollPane(threadTable), BorderLayout.CENTER)

        split.topComponent = memPanel
        split.bottomComponent = threadPanel
        add(split, BorderLayout.CENTER)
    }

    fun update(memoryJson: String?, threadsJson: String?) {
        SwingUtilities.invokeLater {
            if (memoryJson != null) {
                try {
                    val node = mapper.readTree(memoryJson)
                    fun mb(key: String): String {
                        val bytes = node.get(key)?.asLong() ?: 0
                        return "%.1f MB".format(bytes / 1024.0 / 1024.0)
                    }
                    memoryArea.text = buildString {
                        append("Heap:   ${mb("heapUsed")} used / ${mb("heapTotal")} total / ${mb("heapMax")} max\n")
                        append("Free:   ${mb("heapFree")}\n")
                        append("Native: ${mb("nativeHeapAllocated")} allocated / ${mb("nativeHeap")} total\n")
                        append("Native free: ${mb("nativeHeapFree")}")
                    }
                } catch (_: Exception) {}
            }
            if (threadsJson != null) {
                try {
                    val node = mapper.readTree(threadsJson)
                    val threads = node.get("threads")
                    if (threads != null && threads.isArray) {
                        threadTableModel.update(threads.toList())
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private class ThreadTableModel : AbstractTableModel() {
        private val cols = arrayOf("Name", "State", "Group", "Daemon")
        private var data: List<tools.jackson.databind.JsonNode> = emptyList()

        fun update(nodes: List<tools.jackson.databind.JsonNode>) {
            data = nodes
            fireTableDataChanged()
        }

        override fun getRowCount() = data.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(col: Int) = cols[col]
        override fun getValueAt(row: Int, col: Int): Any? {
            val n = data[row]
            return when (col) {
                0 -> n.get("name")?.asText() ?: ""
                1 -> n.get("state")?.asText() ?: ""
                2 -> n.get("group")?.asText() ?: ""
                3 -> if (n.get("daemon")?.asBoolean() == true) "✓" else ""
                else -> ""
            }
        }
    }
}

// ========================================================================
// ViewModels sub-panel
// ========================================================================

/**
 * Master/detail view of live Activity-scoped ViewModels.
 *
 * <p>Left pane: list of VMs (one row per VM, showing class + owner). Right
 * pane: properties of the selected VM in a name/type/value table. The list
 * preserves selection across refreshes — the periodic poll would otherwise
 * blow away whatever the user was looking at.</p>
 */
private class ViewModelsSubPanel : JPanel(BorderLayout()) {

    private val mapper = ObjectMapper()
    private val vmListModel = ViewModelListModel()
    private val vmList = JBTable(vmListModel)
    private val propModel = PropertyTableModel()
    private val propTable = JBTable(propModel)
    private val emptyLabel = JBLabel("No ViewModels — select an app and interact with it.").apply {
        horizontalAlignment = SwingConstants.CENTER
    }

    init {
        vmList.autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        vmList.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) refreshDetail()
        }
        propTable.autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN

        val split = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            JBScrollPane(vmList),
            JBScrollPane(propTable)
        ).apply {
            resizeWeight = 0.4
            border = BorderFactory.createEmptyBorder()
        }

        add(emptyLabel, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)
        emptyLabel.isVisible = true
        split.isVisible = false
    }

    fun update(json: String?) {
        if (json == null) {
            SwingUtilities.invokeLater { setEmpty() }
            return
        }
        SwingUtilities.invokeLater {
            try {
                val node = mapper.readTree(json)
                val vms = node.get("viewModels")
                if (vms == null || !vms.isArray || vms.size() == 0) {
                    setEmpty()
                    return@invokeLater
                }
                val previouslySelected = currentSelectionId()
                vmListModel.update(vms.toList())
                emptyLabel.isVisible = false
                (components.last() as JSplitPane).isVisible = true

                // Re-select by id so selection survives polling refresh.
                val newRow = vmListModel.indexOfId(previouslySelected)
                if (newRow >= 0) {
                    vmList.setRowSelectionInterval(newRow, newRow)
                } else if (vmListModel.rowCount > 0) {
                    vmList.setRowSelectionInterval(0, 0)
                }
                refreshDetail()
            } catch (_: Exception) {
                setEmpty()
            }
        }
    }

    private fun setEmpty() {
        vmListModel.update(emptyList())
        propModel.update(emptyList())
        emptyLabel.isVisible = true
        (components.last() as JSplitPane).isVisible = false
    }

    private fun currentSelectionId(): String? {
        val row = vmList.selectedRow
        return if (row >= 0) vmListModel.idAt(row) else null
    }

    private fun refreshDetail() {
        val row = vmList.selectedRow
        if (row < 0) {
            propModel.update(emptyList())
            return
        }
        val vm = vmListModel.rowAt(row)
        val props = vm.get("properties")
        if (props != null && props.isArray) {
            propModel.update(props.toList())
        } else {
            propModel.update(emptyList())
        }
    }

    private class ViewModelListModel : AbstractTableModel() {
        private val cols = arrayOf("ViewModel", "Owner")
        private var data: List<tools.jackson.databind.JsonNode> = emptyList()

        fun update(nodes: List<tools.jackson.databind.JsonNode>) {
            data = nodes
            fireTableDataChanged()
        }

        fun rowAt(row: Int): tools.jackson.databind.JsonNode = data[row]
        fun idAt(row: Int): String = data[row].get("id")?.asText() ?: ""
        fun indexOfId(id: String?): Int {
            if (id == null) return -1
            return data.indexOfFirst { it.get("id")?.asText() == id }
        }

        override fun getRowCount() = data.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(col: Int) = cols[col]
        override fun getValueAt(row: Int, col: Int): Any? {
            val n = data[row]
            return when (col) {
                0 -> n.get("vmClass")?.asText()?.substringAfterLast('.') ?: ""
                1 -> n.get("owner")?.get("name")?.asText()?.substringAfterLast('.') ?: ""
                else -> ""
            }
        }
    }

    private class PropertyTableModel : AbstractTableModel() {
        private val cols = arrayOf("Property", "Type", "Value")
        private var data: List<tools.jackson.databind.JsonNode> = emptyList()

        fun update(nodes: List<tools.jackson.databind.JsonNode>) {
            data = nodes
            fireTableDataChanged()
        }

        override fun getRowCount() = data.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(col: Int) = cols[col]
        override fun getValueAt(row: Int, col: Int): Any? {
            val n = data[row]
            return when (col) {
                0 -> n.get("name")?.asText() ?: ""
                1 -> n.get("type")?.asText()?.substringAfterLast('.') ?: ""
                2 -> n.get("value")?.asText() ?: ""
                else -> ""
            }
        }
    }
}

// ========================================================================
// AppFunctions sub-panel
// ========================================================================

/**
 * Lists the AppFunctions the host app exposes to Gemini / system AI (the
 * androidx.appfunctions framework). Left column: function list (short
 * name, schema, enabled flag). Right column: parameters table + response
 * detail when a function is selected.
 *
 * <p>The empty-state message distinguishes "host has no functions" from
 * "host doesn't use AppFunctions" by reading the `note` field on the
 * response — KSP only emits the manifest when @AppFunction is declared
 * somewhere, so an absent manifest is the framework-not-used case.</p>
 */
private class AppFunctionsSubPanel : JPanel(BorderLayout()) {

    private val mapper = ObjectMapper()
    private val fnListModel = AppFunctionListModel()
    private val fnList = JBTable(fnListModel)
    private val paramModel = AppFunctionParameterTableModel()
    private val paramTable = JBTable(paramModel)
    private val detailArea = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = true
        wrapStyleWord = true
    }
    private val emptyLabel = JBLabel("No AppFunctions — select an app that uses androidx.appfunctions.").apply {
        horizontalAlignment = SwingConstants.CENTER
    }

    init {
        fnList.autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        fnList.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) refreshDetail()
        }
        paramTable.autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN

        val detailSplit = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            JBScrollPane(paramTable),
            JBScrollPane(detailArea)
        ).apply {
            resizeWeight = 0.6
            border = BorderFactory.createEmptyBorder()
        }

        val split = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            JBScrollPane(fnList),
            detailSplit
        ).apply {
            resizeWeight = 0.4
            border = BorderFactory.createEmptyBorder()
        }

        add(emptyLabel, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)
        emptyLabel.isVisible = true
        split.isVisible = false
    }

    fun update(json: String?) {
        if (json == null) {
            SwingUtilities.invokeLater { setEmpty("No AppFunctions data — connect to an app.") }
            return
        }
        SwingUtilities.invokeLater {
            try {
                val node = mapper.readTree(json)
                val fns = node.get("functions")
                if (fns == null || !fns.isArray || fns.size() == 0) {
                    val note = node.get("note")?.asText()
                    setEmpty(note ?: "No AppFunctions declared on this app.")
                    return@invokeLater
                }
                val previouslySelected = currentSelectionId()
                fnListModel.update(fns.toList())
                emptyLabel.isVisible = false
                (components.last() as JSplitPane).isVisible = true

                val newRow = fnListModel.indexOfId(previouslySelected)
                if (newRow >= 0) {
                    fnList.setRowSelectionInterval(newRow, newRow)
                } else if (fnListModel.rowCount > 0) {
                    fnList.setRowSelectionInterval(0, 0)
                }
                refreshDetail()
            } catch (_: Exception) {
                setEmpty("Failed to parse AppFunctions response.")
            }
        }
    }

    private fun setEmpty(message: String) {
        emptyLabel.text = message
        fnListModel.update(emptyList())
        paramModel.update(emptyList())
        detailArea.text = ""
        emptyLabel.isVisible = true
        (components.last() as JSplitPane).isVisible = false
    }

    private fun currentSelectionId(): String? {
        val row = fnList.selectedRow
        return if (row >= 0) fnListModel.idAt(row) else null
    }

    private fun refreshDetail() {
        val row = fnList.selectedRow
        if (row < 0) {
            paramModel.update(emptyList())
            detailArea.text = ""
            return
        }
        val fn = fnListModel.rowAt(row)
        val params = fn.get("parameters")
        paramModel.update(if (params != null && params.isArray) params.toList() else emptyList())
        detailArea.text = buildDetail(fn)
        detailArea.caretPosition = 0
    }

    private fun buildDetail(fn: tools.jackson.databind.JsonNode): String {
        val sb = StringBuilder()
        fn.get("description")?.asText()?.takeIf { it.isNotBlank() }?.let {
            sb.append(it.trim()).append("\n\n")
        }
        sb.append("Function id: ").append(fn.get("id")?.asText() ?: "—").append('\n')
        fn.get("schemaCategory")?.asText()?.let {
            sb.append("Schema: ").append(it)
            fn.get("schemaName")?.asText()?.let { n -> sb.append('/').append(n) }
            fn.get("schemaVersion")?.let { v -> sb.append(" (v").append(v.asText()).append(')') }
            sb.append('\n')
        }
        val enabled = fn.get("enabledByDefault")
        if (enabled != null) sb.append("Enabled by default: ").append(enabled.asBoolean()).append('\n')
        val response = fn.get("response")
        if (response != null) {
            sb.append("\nReturns: ").append(describeDataType(response.get("dataType"))).append('\n')
            response.get("description")?.asText()?.takeIf { it.isNotBlank() }?.let {
                sb.append("  ").append(it.trim()).append('\n')
            }
        }
        return sb.toString()
    }

    private fun describeDataType(d: tools.jackson.databind.JsonNode?): String {
        if (d == null) return "—"
        val typeName = d.get("typeName")?.asText() ?: ("type#" + (d.get("type")?.asText() ?: "?"))
        val nullable = if (d.get("isNullable")?.asBoolean() == true) "?" else ""
        val ref = d.get("dataTypeReference")?.asText()
        return if (ref != null) "$typeName($ref)$nullable" else "$typeName$nullable"
    }

    private inner class AppFunctionListModel : AbstractTableModel() {
        private val cols = arrayOf("Function", "Schema", "Enabled")
        private var data: List<tools.jackson.databind.JsonNode> = emptyList()

        fun update(nodes: List<tools.jackson.databind.JsonNode>) {
            data = nodes
            fireTableDataChanged()
        }

        fun rowAt(row: Int): tools.jackson.databind.JsonNode = data[row]
        fun idAt(row: Int): String = data[row].get("id")?.asText() ?: ""
        fun indexOfId(id: String?): Int {
            if (id == null) return -1
            return data.indexOfFirst { it.get("id")?.asText() == id }
        }

        override fun getRowCount() = data.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(col: Int) = cols[col]
        override fun getValueAt(row: Int, col: Int): Any? {
            val n = data[row]
            return when (col) {
                // Show just method name (after the # separator) — the FQ id is in the detail pane.
                0 -> n.get("id")?.asText()?.substringAfterLast('#') ?: ""
                1 -> n.get("schemaName")?.asText() ?: ""
                2 -> if (n.get("enabledByDefault")?.asBoolean() == true) "✓" else "—"
                else -> ""
            }
        }
    }

    private inner class AppFunctionParameterTableModel : AbstractTableModel() {
        private val cols = arrayOf("Param", "Type", "Required", "Description")
        private var data: List<tools.jackson.databind.JsonNode> = emptyList()

        fun update(nodes: List<tools.jackson.databind.JsonNode>) {
            data = nodes
            fireTableDataChanged()
        }

        override fun getRowCount() = data.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(col: Int) = cols[col]
        override fun getValueAt(row: Int, col: Int): Any? {
            val n = data[row]
            return when (col) {
                0 -> n.get("name")?.asText() ?: ""
                1 -> describeDataType(n.get("dataType"))
                2 -> if (n.get("isRequired")?.asBoolean() == true) "✓" else "—"
                3 -> n.get("description")?.asText() ?: ""
                else -> ""
            }
        }
    }
}
