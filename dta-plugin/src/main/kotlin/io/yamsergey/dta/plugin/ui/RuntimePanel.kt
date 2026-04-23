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
 * Top-level "Runtime" tab with sub-tabs: Data, Navigation, Lifecycle, Memory.
 */
class RuntimePanel : JPanel(BorderLayout()), DtaServiceListener {

    private val service = DtaService.getInstance()
    private val dataPanel = DataPanel()
    private val navigationPanel = NavigationSubPanel()
    private val lifecyclePanel = LifecycleSubPanel()
    private val memoryPanel = MemorySubPanel()

    init {
        service.addListener(this)
        val subTabs = JBTabbedPane()
        subTabs.addTab("Data", dataPanel)
        subTabs.addTab("Navigation", navigationPanel)
        subTabs.addTab("Lifecycle", lifecyclePanel)
        subTabs.addTab("Memory", memoryPanel)
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
}

// ========================================================================
// Navigation sub-panel
// ========================================================================

private class NavigationSubPanel : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(NavigationSubPanel::class.java)
    private val mapper = ObjectMapper()
    private val backstackArea = JBTextArea().apply {
        isEditable = false
        font = Font("Monospaced", Font.PLAIN, 12)
    }
    private val graphArea = JBTextArea().apply {
        isEditable = false
        font = Font("Monospaced", Font.PLAIN, 12)
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
                                    val route = dest.get("route")?.asText() ?: "?"
                                    val nav = dest.get("navigatorName")?.asText() ?: ""
                                    sb.append("  • $route ($nav)\n")
                                }
                            }
                        }
                        graphArea.text = sb.toString()
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
