package io.yamsergey.dta.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.plugin.DtaService
import io.yamsergey.dta.plugin.DtaService.DtaServiceListener
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.*
import javax.swing.table.AbstractTableModel

class DataPanel : JPanel(BorderLayout()), DtaServiceListener {

    private val log = Logger.getInstance(DataPanel::class.java)
    private val mapper = ObjectMapper()
    private val service = DtaService.getInstance()

    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)

    // Database list
    private val dbTableModel = DbTableModel()
    private val dbTable = JBTable(dbTableModel)

    // Prefs list
    private val prefsTableModel = PrefsTableModel()
    private val prefsTable = JBTable(prefsTableModel)

    // Detail views
    private val schemaArea = JBTextArea().apply {
        isEditable = false
        font = Font("Monospaced", Font.PLAIN, 12)
    }
    private val queryInput = JBTextArea(3, 40).apply {
        font = Font("Monospaced", Font.PLAIN, 12)
        text = "SELECT * FROM "
    }
    private val queryResultArea = JBTextArea().apply {
        isEditable = false
        font = Font("Monospaced", Font.PLAIN, 12)
    }
    private val prefsDetailArea = JBTextArea().apply {
        isEditable = false
        font = Font("Monospaced", Font.PLAIN, 12)
    }

    private var currentDbName: String? = null

    init {
        service.addListener(this)

        // === List view (databases + prefs side by side) ===
        val listPanel = JPanel(BorderLayout())

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
            resizeWeight = 0.5
            border = null
        }

        // Databases section
        val dbPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Databases")
        }
        dbTable.autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        dbTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val row = dbTable.selectedRow
                if (row >= 0) showDatabaseDetail(dbTableModel.getDbName(row))
            }
        }
        dbPanel.add(JBScrollPane(dbTable), BorderLayout.CENTER)

        // Prefs section
        val prefsPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("SharedPreferences")
        }
        prefsTable.autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        prefsTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val row = prefsTable.selectedRow
                if (row >= 0) showPrefsDetail(prefsTableModel.getPrefsName(row))
            }
        }
        prefsPanel.add(JBScrollPane(prefsTable), BorderLayout.CENTER)

        splitPane.topComponent = dbPanel
        splitPane.bottomComponent = prefsPanel
        listPanel.add(splitPane, BorderLayout.CENTER)

        // Refresh button
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        toolbar.add(JButton("Refresh").apply {
            addActionListener { refreshData() }
        })
        listPanel.add(toolbar, BorderLayout.NORTH)

        // === Database detail view (schema + query) ===
        val dbDetailPanel = JPanel(BorderLayout())
        val backFromDb = JButton("← Back").apply {
            addActionListener { cardLayout.show(cardPanel, "list") }
        }
        val dbDetailToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        dbDetailToolbar.add(backFromDb)
        dbDetailPanel.add(dbDetailToolbar, BorderLayout.NORTH)

        val dbDetailSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
            resizeWeight = 0.4
            border = null
        }

        val schemaPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Schema")
        }
        schemaPanel.add(JBScrollPane(schemaArea), BorderLayout.CENTER)

        val queryPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Query")
        }
        val queryTop = JPanel(BorderLayout())
        queryTop.add(JBScrollPane(queryInput), BorderLayout.CENTER)
        val runBtn = JButton("Run Query").apply {
            addActionListener { runQuery() }
        }
        queryTop.add(runBtn, BorderLayout.EAST)
        queryPanel.add(queryTop, BorderLayout.NORTH)
        queryPanel.add(JBScrollPane(queryResultArea), BorderLayout.CENTER)

        dbDetailSplit.topComponent = schemaPanel
        dbDetailSplit.bottomComponent = queryPanel
        dbDetailPanel.add(dbDetailSplit, BorderLayout.CENTER)

        // === Prefs detail view ===
        val prefsDetailPanel = JPanel(BorderLayout())
        val backFromPrefs = JButton("← Back").apply {
            addActionListener { cardLayout.show(cardPanel, "list") }
        }
        val prefsToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        prefsToolbar.add(backFromPrefs)
        prefsDetailPanel.add(prefsToolbar, BorderLayout.NORTH)
        prefsDetailPanel.add(JBScrollPane(prefsDetailArea), BorderLayout.CENTER)

        // Wire cards
        cardPanel.add(listPanel, "list")
        cardPanel.add(dbDetailPanel, "db_detail")
        cardPanel.add(prefsDetailPanel, "prefs_detail")
        add(cardPanel, BorderLayout.CENTER)
    }

    override fun onDataStoreChanged(databasesJson: String?, prefsJson: String?) {
        updateData(databasesJson, prefsJson)
    }

    fun updateData(databasesJson: String?, prefsJson: String?) {
        SwingUtilities.invokeLater {
            if (databasesJson != null) {
                try {
                    val node = mapper.readTree(databasesJson)
                    val dbs = node.get("databases")
                    if (dbs != null && dbs.isArray) dbTableModel.update(dbs)
                } catch (e: Exception) {
                    log.debug("Failed to parse databases: ${e.message}")
                }
            }
            if (prefsJson != null) {
                try {
                    val node = mapper.readTree(prefsJson)
                    val files = node.get("files")
                    if (files != null && files.isArray) prefsTableModel.update(files)
                } catch (e: Exception) {
                    log.debug("Failed to parse prefs: ${e.message}")
                }
            }
        }
    }

    private fun refreshData() {
        // Data comes from polling via onDataStoreChanged — manual refresh triggers a poll
        service.selectedApp ?: return
        service.selectedDevice ?: return
        // The polling loop will pick it up on next tick
    }

    private fun showDatabaseDetail(dbName: String) {
        currentDbName = dbName
        schemaArea.text = "Loading schema..."
        queryResultArea.text = ""
        queryInput.text = "SELECT * FROM "
        cardLayout.show(cardPanel, "db_detail")

        val pkg = service.selectedApp?.packageName() ?: return
        val device = service.selectedDevice?.serial()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val json = service.fetchDatabaseSchema(pkg, dbName, device)
                val node = mapper.readTree(json)
                val sb = StringBuilder()
                val tables = node.get("tables")
                if (tables != null && tables.isArray) {
                    for (table in tables) {
                        val name = table.get("name")?.stringValue() ?: "?"
                        val rowCount = table.get("rowCount")?.asLong() ?: 0
                        sb.append("$name ($rowCount rows)\n")
                        val columns = table.get("columns")
                        if (columns != null && columns.isArray) {
                            for (col in columns) {
                                val cName = col.get("name")?.stringValue() ?: "?"
                                val cType = col.get("type")?.stringValue() ?: "?"
                                val pk = if (col.get("primaryKey")?.asBoolean() == true) " PK" else ""
                                sb.append("  $cName: $cType$pk\n")
                            }
                        }
                        sb.append("\n")
                        if (queryInput.text == "SELECT * FROM ") {
                            SwingUtilities.invokeLater { queryInput.text = "SELECT * FROM $name LIMIT 50" }
                        }
                    }
                }
                SwingUtilities.invokeLater { schemaArea.text = sb.toString() }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { schemaArea.text = "Error: ${e.message}" }
            }
        }
    }

    private fun runQuery() {
        val dbName = currentDbName ?: return
        val sql = queryInput.text.trim()
        if (sql.isEmpty()) return
        queryResultArea.text = "Running..."

        val pkg = service.selectedApp?.packageName() ?: return
        val device = service.selectedDevice?.serial()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val body = """{"sql":"${sql.replace("\"", "\\\"")}","readOnly":true}"""
                val json = service.fetchDatabaseQuery(pkg, dbName, body, device)
                val node = mapper.readTree(json)

                val error = node.get("error")?.stringValue()
                if (error != null) {
                    SwingUtilities.invokeLater { queryResultArea.text = "Error: $error" }
                    return@executeOnPooledThread
                }

                val sb = StringBuilder()
                val columns = node.get("columns")
                val rows = node.get("rows")
                if (columns != null && columns.isArray) {
                    sb.append(columns.joinToString(" | ") { it.asText("") })
                    sb.append("\n")
                    sb.append("-".repeat(sb.length))
                    sb.append("\n")
                }
                if (rows != null && rows.isArray) {
                    for (row in rows) {
                        sb.append(row.joinToString(" | ") { it.asText("") })
                        sb.append("\n")
                    }
                }
                val rowCount = node.get("rowCount")?.asInt() ?: 0
                sb.append("\n$rowCount row(s)")
                SwingUtilities.invokeLater { queryResultArea.text = sb.toString() }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { queryResultArea.text = "Error: ${e.message}" }
            }
        }
    }

    private fun showPrefsDetail(prefsName: String) {
        prefsDetailArea.text = "Loading..."
        cardLayout.show(cardPanel, "prefs_detail")

        val pkg = service.selectedApp?.packageName() ?: return
        val device = service.selectedDevice?.serial()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val json = service.fetchSharedPrefs(pkg, prefsName, device)
                val node = mapper.readTree(json)

                val sb = StringBuilder()
                val encrypted = node.get("encrypted")?.asBoolean() == true
                sb.append("File: $prefsName")
                if (encrypted) sb.append(" (encrypted)")
                sb.append("\n\n")

                val entries = node.get("entries")
                if (entries != null && entries.isObject) {
                    val fields = entries.properties().toList().sortedBy { it.key }
                    for ((key, value) in fields) {
                        sb.append("$key = $value\n")
                    }
                    sb.append("\n${fields.size} entries")
                }

                val error = node.get("error")?.stringValue()
                if (error != null) sb.append("\nError: $error")

                SwingUtilities.invokeLater { prefsDetailArea.text = sb.toString() }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { prefsDetailArea.text = "Error: ${e.message}" }
            }
        }
    }

    // === Table Models ===

    private class DbTableModel : AbstractTableModel() {
        private val cols = arrayOf("Name", "Size", "Version", "Room", "Cipher")
        private var data: List<JsonNode> = emptyList()

        fun update(nodes: JsonNode) {
            data = nodes.toList()
            fireTableDataChanged()
        }

        fun getDbName(row: Int): String = data[row].get("name")?.stringValue() ?: ""

        override fun getRowCount() = data.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(col: Int) = cols[col]
        override fun getValueAt(row: Int, col: Int): Any? {
            val n = data[row]
            return when (col) {
                0 -> n.get("name")?.stringValue() ?: ""
                1 -> {
                    val bytes = n.get("sizeBytes")?.asLong() ?: 0
                    if (bytes > 1024 * 1024) "${bytes / 1024 / 1024} MB"
                    else if (bytes > 1024) "${bytes / 1024} KB"
                    else "$bytes B"
                }
                2 -> n.get("version")?.asInt()?.toString() ?: ""
                3 -> n.get("roomIdentityHash")?.stringValue()?.take(8) ?: ""
                4 -> n.get("cipher")?.stringValue() ?: ""
                else -> ""
            }
        }
    }

    private class PrefsTableModel : AbstractTableModel() {
        private val cols = arrayOf("Name", "Size", "Encrypted")
        private var data: List<JsonNode> = emptyList()

        fun update(nodes: JsonNode) {
            data = nodes.toList()
            fireTableDataChanged()
        }

        fun getPrefsName(row: Int): String = data[row].get("name")?.stringValue() ?: ""

        override fun getRowCount() = data.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(col: Int) = cols[col]
        override fun getValueAt(row: Int, col: Int): Any? {
            val n = data[row]
            return when (col) {
                0 -> n.get("name")?.stringValue() ?: ""
                1 -> {
                    val bytes = n.get("fileSizeBytes")?.asLong() ?: 0
                    if (bytes > 1024) "${bytes / 1024} KB" else "$bytes B"
                }
                2 -> if (n.get("encrypted")?.asBoolean() == true) "🔒" else ""
                else -> ""
            }
        }
    }
}
