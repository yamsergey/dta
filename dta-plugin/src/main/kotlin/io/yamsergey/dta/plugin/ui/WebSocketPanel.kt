package io.yamsergey.dta.plugin.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import io.yamsergey.dta.plugin.DtaService
import io.yamsergey.dta.plugin.DtaService.DtaServiceListener
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

/**
 * Panel displaying captured WebSocket connections in a table.
 * Columns: URL, Status, Messages, Connected At
 */
class WebSocketPanel : JPanel(BorderLayout()), DtaServiceListener {

    private val tableModel = WebSocketTableModel()
    private val table = JBTable(tableModel)

    init {
        table.autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        table.columnModel.getColumn(0).preferredWidth = 400  // URL
        table.columnModel.getColumn(1).preferredWidth = 80   // Status
        table.columnModel.getColumn(2).preferredWidth = 80   // Messages
        table.columnModel.getColumn(3).preferredWidth = 150  // Connected At

        add(JBScrollPane(table), BorderLayout.CENTER)

        DtaService.getInstance().addListener(this)
    }

    override fun onWebSocketDataChanged(json: String?) {
        if (json != null) {
            tableModel.updateData(parseConnections(json))
        } else {
            tableModel.updateData(emptyList())
        }
    }

    /**
     * Parses the WebSocket connections JSON array into row data.
     * Expected format: [ { "url": "wss://...", "status": "OPEN", "messageCount": 5, "connectedAt": "..." }, ... ]
     */
    private fun parseConnections(json: String): List<WsConnection> {
        val connections = mutableListOf<WsConnection>()
        val trimmed = json.trim()

        // Response may be {"count":N,"connections":[...]} or a direct array [...]
        val arrayJson = if (trimmed.startsWith("[")) {
            trimmed
        } else {
            extractArray(trimmed, "connections") ?: return connections
        }

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

    private fun parseConnection(obj: String): WsConnection {
        return WsConnection(
            url = extractString(obj, "url") ?: "",
            status = extractString(obj, "status") ?: "UNKNOWN",
            messageCount = extractInt(obj, "messageCount") ?: extractInt(obj, "messages") ?: 0,
            connectedAt = extractString(obj, "connectedAt") ?: extractString(obj, "startTime") ?: ""
        )
    }

    private fun findClosingBrace(json: String, start: Int): Int {
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until json.length) {
            val c = json[i]
            if (esc) { esc = false; continue }
            if (c == '\\') { esc = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (!inStr) {
                if (c == '{') depth++
                else if (c == '}') { depth--; if (depth == 0) return i }
            }
        }
        return json.length - 1
    }

    private fun extractString(json: String, field: String): String? {
        val match = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"").find(json)
        return match?.groupValues?.get(1)
    }

    private fun extractInt(json: String, field: String): Int? {
        val match = Regex("\"$field\"\\s*:\\s*(-?\\d+)").find(json)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractArray(json: String, field: String): String? {
        val key = "\"$field\""
        val keyIdx = json.indexOf(key)
        if (keyIdx < 0) return null
        val arrStart = json.indexOf('[', keyIdx + key.length)
        if (arrStart < 0) return null
        var depth = 0
        for (i in arrStart until json.length) {
            when (json[i]) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return json.substring(arrStart, i + 1) }
            }
        }
        return null
    }

    // ========================================================================
    // Data / Model
    // ========================================================================

    data class WsConnection(
        val url: String,
        val status: String,
        val messageCount: Int,
        val connectedAt: String
    )

    private class WebSocketTableModel : AbstractTableModel() {
        private val columns = arrayOf("URL", "Status", "Messages", "Connected At")
        private var rows: List<WsConnection> = emptyList()

        fun updateData(data: List<WsConnection>) {
            rows = data
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val conn = rows[rowIndex]
            return when (columnIndex) {
                0 -> conn.url
                1 -> conn.status
                2 -> conn.messageCount.toString()
                3 -> conn.connectedAt
                else -> ""
            }
        }
    }
}
