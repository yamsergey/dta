package io.yamsergey.dta.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.yamsergey.dta.plugin.DtaService
import io.yamsergey.dta.plugin.DtaService.DtaServiceListener
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

/**
 * Panel displaying captured WebSocket connections with 3-level navigation:
 * 1. Connections list
 * 2. Messages list for a selected connection
 * 3. Message detail with full payload
 */
class WebSocketPanel : JPanel(BorderLayout()), DtaServiceListener {

    private val connectionsTableModel = ConnectionsTableModel()
    private val connectionsTable = JBTable(connectionsTableModel)

    private val messagesTableModel = MessagesTableModel()
    private val messagesTable = JBTable(messagesTableModel)

    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)

    // Current connection context for messages list header
    private val messagesHeaderLabel = JBLabel()

    // Detail view
    private val detailArea = JTextArea().apply {
        isEditable = false
        font = Font("JetBrains Mono", Font.PLAIN, 12)
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
    }

    // Keep loaded messages for navigating to detail
    private var currentMessages: List<WsMessage> = emptyList()
    private var currentConnectionUrl: String = ""

    init {
        // -- Connections table setup --
        connectionsTable.autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        connectionsTable.columnModel.getColumn(0).preferredWidth = 400
        connectionsTable.columnModel.getColumn(1).preferredWidth = 80
        connectionsTable.columnModel.getColumn(2).preferredWidth = 80
        connectionsTable.columnModel.getColumn(3).preferredWidth = 150

        connectionsTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val row = connectionsTable.selectedRow
                if (row in 0 until connectionsTableModel.rowCount) {
                    fetchConnectionDetail(connectionsTableModel.getConnection(row))
                }
            }
        }

        // -- Messages table setup --
        messagesTable.autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        messagesTable.columnModel.getColumn(0).preferredWidth = 40
        messagesTable.columnModel.getColumn(1).preferredWidth = 60
        messagesTable.columnModel.getColumn(2).preferredWidth = 500
        messagesTable.columnModel.getColumn(3).preferredWidth = 80

        messagesTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val row = messagesTable.selectedRow
                if (row in 0 until messagesTableModel.rowCount) {
                    showMessageDetail(row)
                }
            }
        }

        // -- Card 1: Connections list --
        val connectionsPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(connectionsTable), BorderLayout.CENTER)
        }

        // -- Card 2: Messages list --
        val messagesBackButton = JButton("\u2190 Back").apply {
            addActionListener { cardLayout.show(cardPanel, "connections") }
        }
        val messagesToolbar = JPanel(BorderLayout()).apply {
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                add(messagesBackButton)
                add(messagesHeaderLabel)
            }
            add(left, BorderLayout.WEST)
        }
        val messagesPanel = JPanel(BorderLayout()).apply {
            add(messagesToolbar, BorderLayout.NORTH)
            add(JBScrollPane(messagesTable), BorderLayout.CENTER)
        }

        // -- Card 3: Message detail --
        val detailBackButton = JButton("\u2190 Back").apply {
            addActionListener { cardLayout.show(cardPanel, "messages") }
        }
        val detailCopyButton = JButton("Copy").apply {
            addActionListener {
                val text = detailArea.text
                if (text.isNotEmpty()) {
                    CopyPasteManager.getInstance().setContents(StringSelection(text))
                }
            }
        }
        val detailToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(detailBackButton)
            add(detailCopyButton)
        }
        val detailPanel = JPanel(BorderLayout()).apply {
            add(detailToolbar, BorderLayout.NORTH)
            add(JBScrollPane(detailArea), BorderLayout.CENTER)
        }

        // -- Assemble cards --
        cardPanel.add(connectionsPanel, "connections")
        cardPanel.add(messagesPanel, "messages")
        cardPanel.add(detailPanel, "detail")

        add(cardPanel, BorderLayout.CENTER)

        DtaService.getInstance().addListener(this)
    }

    // ========================================================================
    // Navigation actions
    // ========================================================================

    private fun fetchConnectionDetail(connection: WsConnection) {
        if (connection.id.isEmpty()) return
        val service = DtaService.getInstance()
        val pkg = service.selectedApp?.packageName() ?: return
        val device = service.selectedDevice?.serial()

        messagesHeaderLabel.text = " ${connection.url}"
        messagesTableModel.updateData(emptyList())
        currentMessages = emptyList()
        currentConnectionUrl = connection.url
        cardLayout.show(cardPanel, "messages")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val json = service.fetchWebSocketDetail(pkg, connection.id, device)
                val messages = parseMessages(extractArray(json, "messages") ?: "[]")
                SwingUtilities.invokeLater {
                    currentMessages = messages
                    messagesTableModel.updateData(messages)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    messagesHeaderLabel.text = " Error: ${e.message}"
                }
            }
        }
    }

    private fun showMessageDetail(row: Int) {
        if (row < 0 || row >= currentMessages.size) return
        val msg = currentMessages[row]

        val sb = StringBuilder()
        val dir = if (msg.direction == "SENT") "Sent" else "Received"
        sb.appendLine("Direction: $dir")
        sb.appendLine("Type: ${msg.type}")
        if (msg.timestamp > 0) {
            sb.appendLine("Timestamp: ${formatTimestamp(msg.timestamp)}")
        }
        if (msg.payloadSize > 0) {
            sb.appendLine("Size: ${msg.payloadSize} bytes")
        }
        sb.appendLine()

        if (msg.payload.isNotEmpty()) {
            // Try to pretty-print if it looks like JSON
            val trimmed = msg.payload.trim()
            val body = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                prettyPrintJson(trimmed)
            } else {
                msg.payload
            }
            sb.append(body)
        } else {
            sb.append("(empty payload)")
        }

        detailArea.text = sb.toString()
        detailArea.caretPosition = 0
        cardLayout.show(cardPanel, "detail")
    }

    // ========================================================================
    // Listener
    // ========================================================================

    override fun onWebSocketDataChanged(json: String?) {
        if (json != null) {
            connectionsTableModel.updateData(parseConnections(json))
        } else {
            connectionsTableModel.updateData(emptyList())
            messagesTableModel.updateData(emptyList())
            currentMessages = emptyList()
            detailArea.text = ""
            cardLayout.show(cardPanel, "connections")
        }
    }

    // ========================================================================
    // Parsing — connections list
    // ========================================================================

    private fun parseConnections(json: String): List<WsConnection> {
        val connections = mutableListOf<WsConnection>()
        val trimmed = json.trim()
        val arrayJson = if (trimmed.startsWith("[")) trimmed
        else extractArray(trimmed, "connections") ?: return connections

        var i = 0
        while (i < arrayJson.length) {
            if (arrayJson[i] == '{') {
                val objEnd = findClosingBrace(arrayJson, i)
                val obj = arrayJson.substring(i, objEnd + 1)
                connections.add(parseConnection(obj))
                i = objEnd + 1
            } else {
                i++
            }
        }
        return connections
    }

    private fun parseConnection(obj: String) = WsConnection(
        id = extractStringValue(obj, "id") ?: "",
        url = extractStringValue(obj, "url") ?: "",
        status = extractStringValue(obj, "status") ?: "UNKNOWN",
        messageCount = extractInt(obj, "messageCount") ?: 0,
        connectedAt = extractLong(obj, "connectedAt") ?: extractLong(obj, "startTime") ?: 0L
    )

    // ========================================================================
    // Parsing — messages from detail response
    // ========================================================================

    private fun parseMessages(arrayJson: String): List<WsMessage> {
        val messages = mutableListOf<WsMessage>()
        var i = 0
        while (i < arrayJson.length) {
            if (arrayJson[i] == '{') {
                val objEnd = findClosingBrace(arrayJson, i)
                val obj = arrayJson.substring(i, objEnd + 1)
                messages.add(
                    WsMessage(
                        id = extractStringValue(obj, "id") ?: "",
                        direction = extractStringValue(obj, "direction") ?: "",
                        type = extractStringValue(obj, "type") ?: "",
                        payload = extractStringValue(obj, "textPayload") ?: "",
                        timestamp = extractLong(obj, "timestamp") ?: 0L,
                        payloadSize = extractInt(obj, "payloadSize") ?: 0
                    )
                )
                i = objEnd + 1
            } else {
                i++
            }
        }
        return messages
    }

    // ========================================================================
    // JSON helpers — hand-written, handles escaped quotes properly
    // ========================================================================

    private fun findClosingBrace(json: String, start: Int): Int {
        var depth = 0; var inStr = false; var esc = false
        for (i in start until json.length) {
            val c = json[i]
            if (esc) { esc = false; continue }
            if (c == '\\') { esc = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (!inStr) {
                if (c == '{') depth++ else if (c == '}') { depth--; if (depth == 0) return i }
            }
        }
        return json.length - 1
    }

    /**
     * Extracts a JSON string value for the given field, properly handling
     * escaped quotes and other escape sequences inside the value.
     */
    private fun extractStringValue(json: String, field: String): String? {
        val key = "\"$field\""
        val keyIdx = json.indexOf(key)
        if (keyIdx < 0) return null
        val colonIdx = json.indexOf(':', keyIdx + key.length)
        if (colonIdx < 0) return null
        val quoteStart = json.indexOf('"', colonIdx + 1)
        if (quoteStart < 0) return null
        var i = quoteStart + 1
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                when (json[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    else -> { sb.append(c); sb.append(json[i + 1]) }
                }
                i += 2
            } else if (c == '"') {
                return sb.toString()
            } else {
                sb.append(c)
                i++
            }
        }
        return null
    }

    private fun extractInt(json: String, field: String): Int? {
        val key = "\"$field\""
        val keyIdx = json.indexOf(key)
        if (keyIdx < 0) return null
        val colonIdx = json.indexOf(':', keyIdx + key.length)
        if (colonIdx < 0) return null
        val numStart = json.indexOfFirst(colonIdx + 1) { it.isDigit() || it == '-' }
        if (numStart < 0) return null
        var numEnd = numStart + 1
        while (numEnd < json.length && json[numEnd].isDigit()) numEnd++
        return json.substring(numStart, numEnd).toIntOrNull()
    }

    private fun extractLong(json: String, field: String): Long? {
        val key = "\"$field\""
        val keyIdx = json.indexOf(key)
        if (keyIdx < 0) return null
        val colonIdx = json.indexOf(':', keyIdx + key.length)
        if (colonIdx < 0) return null
        val numStart = json.indexOfFirst(colonIdx + 1) { it.isDigit() || it == '-' }
        if (numStart < 0) return null
        var numEnd = numStart + 1
        while (numEnd < json.length && json[numEnd].isDigit()) numEnd++
        return json.substring(numStart, numEnd).toLongOrNull()
    }

    private fun extractArray(json: String, field: String): String? {
        val keyIdx = json.indexOf("\"$field\"")
        if (keyIdx < 0) return null
        val arrStart = json.indexOf('[', keyIdx)
        if (arrStart < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (i in arrStart until json.length) {
            val c = json[i]
            if (esc) { esc = false; continue }
            if (c == '\\') { esc = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (!inStr) {
                if (c == '[') depth++ else if (c == ']') { depth--; if (depth == 0) return json.substring(arrStart, i + 1) }
            }
        }
        return null
    }

    /** Helper to find the first index >= [startIndex] where [predicate] matches. */
    private inline fun String.indexOfFirst(startIndex: Int, predicate: (Char) -> Boolean): Int {
        for (i in startIndex until length) {
            if (predicate(this[i])) return i
        }
        return -1
    }

    /**
     * Simple JSON pretty-printer without external libraries.
     */
    private fun prettyPrintJson(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return text
        if (trimmed[0] != '{' && trimmed[0] != '[') return text

        val sb = StringBuilder()
        var indent = 0
        var inStr = false
        var esc = false
        val indentStr = "    "

        for (c in trimmed) {
            if (esc) {
                sb.append(c)
                esc = false
                continue
            }
            if (c == '\\' && inStr) {
                sb.append(c)
                esc = true
                continue
            }
            if (c == '"') {
                inStr = !inStr
                sb.append(c)
                continue
            }
            if (inStr) {
                sb.append(c)
                continue
            }
            when (c) {
                '{', '[' -> {
                    sb.append(c)
                    sb.append('\n')
                    indent++
                    repeat(indent) { sb.append(indentStr) }
                }
                '}', ']' -> {
                    sb.append('\n')
                    indent--
                    repeat(indent) { sb.append(indentStr) }
                    sb.append(c)
                }
                ',' -> {
                    sb.append(c)
                    sb.append('\n')
                    repeat(indent) { sb.append(indentStr) }
                }
                ':' -> sb.append(": ")
                ' ', '\t', '\n', '\r' -> { /* skip existing whitespace */ }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun formatTimestamp(epochMs: Long): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            sdf.format(Date(epochMs))
        } catch (_: Exception) {
            epochMs.toString()
        }
    }

    // ========================================================================
    // Data / Model
    // ========================================================================

    data class WsConnection(
        val id: String, val url: String, val status: String,
        val messageCount: Int, val connectedAt: Long
    )

    data class WsMessage(
        val id: String, val direction: String, val type: String,
        val payload: String, val timestamp: Long, val payloadSize: Int
    )

    private class ConnectionsTableModel : AbstractTableModel() {
        private val columns = arrayOf("URL", "Status", "Messages", "Connected At")
        private var rows: List<WsConnection> = emptyList()

        fun updateData(data: List<WsConnection>) { rows = data; fireTableDataChanged() }
        fun getConnection(row: Int): WsConnection = rows[row]
        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val conn = rows[rowIndex]
            return when (columnIndex) {
                0 -> conn.url
                1 -> conn.status
                2 -> conn.messageCount.toString()
                3 -> if (conn.connectedAt > 0) {
                    try {
                        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(conn.connectedAt))
                    } catch (_: Exception) {
                        conn.connectedAt.toString()
                    }
                } else "-"
                else -> ""
            }
        }
    }

    private class MessagesTableModel : AbstractTableModel() {
        private val columns = arrayOf("Dir", "Type", "Preview", "Size")
        private var rows: List<WsMessage> = emptyList()

        fun updateData(data: List<WsMessage>) { rows = data; fireTableDataChanged() }
        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val msg = rows[rowIndex]
            return when (columnIndex) {
                0 -> if (msg.direction == "SENT") "Sent" else "Received"
                1 -> msg.type
                2 -> {
                    val preview = msg.payload.replace('\n', ' ').replace('\r', ' ')
                    if (preview.length > 80) preview.substring(0, 80) + "\u2026" else preview
                }
                3 -> if (msg.payloadSize > 0) "${msg.payloadSize}B" else "-"
                else -> ""
            }
        }
    }
}
