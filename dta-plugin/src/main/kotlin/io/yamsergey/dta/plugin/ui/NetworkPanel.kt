package io.yamsergey.dta.plugin.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import io.yamsergey.dta.plugin.DtaService
import io.yamsergey.dta.plugin.DtaService.DtaServiceListener
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

/**
 * Panel displaying captured HTTP network requests in a table.
 * Columns: Method, URL, Status, Duration, Source
 */
class NetworkPanel : JPanel(BorderLayout()), DtaServiceListener {

    private val tableModel = NetworkTableModel()
    private val table = JBTable(tableModel)

    init {
        table.autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        table.columnModel.getColumn(0).preferredWidth = 60   // Method
        table.columnModel.getColumn(1).preferredWidth = 400  // URL
        table.columnModel.getColumn(2).preferredWidth = 60   // Status
        table.columnModel.getColumn(3).preferredWidth = 80   // Duration
        table.columnModel.getColumn(4).preferredWidth = 80   // Source

        add(JBScrollPane(table), BorderLayout.CENTER)

        DtaService.getInstance().addListener(this)
    }

    override fun onNetworkDataChanged(json: String?) {
        if (json != null) {
            tableModel.updateData(parseRequests(json))
        } else {
            tableModel.updateData(emptyList())
        }
    }

    /**
     * Parses the network requests JSON array into row data.
     * Expected format: [ { "method": "GET", "url": "...", "statusCode": 200, "durationMs": 123, "source": "OkHttp" }, ... ]
     */
    private fun parseRequests(json: String): List<NetworkRequest> {
        val requests = mutableListOf<NetworkRequest>()
        val trimmed = json.trim()

        // Response may be {"count":N,"requests":[...]} or a direct array [...]
        val arrayJson = if (trimmed.startsWith("[")) {
            trimmed
        } else {
            extractArray(trimmed, "requests") ?: return requests
        }

        // Split into individual objects
        var i = 0
        while (i < arrayJson.length) {
            if (arrayJson[i] == '{') {
                val objEnd = findClosingBrace(arrayJson, i)
                val obj = arrayJson.substring(i, objEnd + 1)
                requests.add(parseRequest(obj))
                i = objEnd + 1
            } else {
                i++
            }
        }
        return requests
    }

    private fun parseRequest(obj: String): NetworkRequest {
        return NetworkRequest(
            method = extractString(obj, "method") ?: "",
            url = extractString(obj, "url") ?: "",
            status = extractInt(obj, "statusCode") ?: extractInt(obj, "status") ?: 0,
            durationMs = extractLong(obj, "durationMs") ?: extractLong(obj, "duration") ?: 0L,
            source = extractString(obj, "source") ?: ""
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

    private fun extractLong(json: String, field: String): Long? {
        val match = Regex("\"$field\"\\s*:\\s*(-?\\d+)").find(json)
        return match?.groupValues?.get(1)?.toLongOrNull()
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

    data class NetworkRequest(
        val method: String,
        val url: String,
        val status: Int,
        val durationMs: Long,
        val source: String
    )

    private class NetworkTableModel : AbstractTableModel() {
        private val columns = arrayOf("Method", "URL", "Status", "Duration", "Source")
        private var rows: List<NetworkRequest> = emptyList()

        fun updateData(data: List<NetworkRequest>) {
            rows = data
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val req = rows[rowIndex]
            return when (columnIndex) {
                0 -> req.method
                1 -> req.url
                2 -> if (req.status > 0) req.status.toString() else "-"
                3 -> if (req.durationMs > 0) "${req.durationMs}ms" else "-"
                4 -> req.source
                else -> ""
            }
        }
    }
}
