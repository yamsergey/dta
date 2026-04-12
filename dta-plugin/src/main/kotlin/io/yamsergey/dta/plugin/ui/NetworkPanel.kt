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
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

/**
 * Panel displaying captured HTTP network requests.
 * List view shows all requests in a table; clicking a row navigates to
 * a detail view with organised sections and copy buttons.
 */
class NetworkPanel : JPanel(BorderLayout()), DtaServiceListener {

    private val tableModel = NetworkTableModel()
    private val table = JBTable(tableModel)
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)

    // Detail view components
    private val detailContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    init {
        table.autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        table.columnModel.getColumn(0).preferredWidth = 60
        table.columnModel.getColumn(1).preferredWidth = 400
        table.columnModel.getColumn(2).preferredWidth = 60
        table.columnModel.getColumn(3).preferredWidth = 80
        table.columnModel.getColumn(4).preferredWidth = 80

        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val row = table.selectedRow
                if (row in 0 until tableModel.rowCount) {
                    fetchDetail(tableModel.getRequest(row).id)
                }
            }
        }

        // Detail view with back button
        val backButton = JButton("\u2190 Back").apply {
            addActionListener { cardLayout.show(cardPanel, "list") }
        }
        val detailToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(backButton)
        }
        val detailPanel = JPanel(BorderLayout()).apply {
            add(detailToolbar, BorderLayout.NORTH)
            add(JBScrollPane(detailContainer), BorderLayout.CENTER)
        }

        // List view with clear button
        val listToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JButton("Clear").apply {
                addActionListener { clearNetworkRequests() }
            })
        }
        val listPanel = JPanel(BorderLayout()).apply {
            add(listToolbar, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
        }

        cardPanel.add(listPanel, "list")
        cardPanel.add(detailPanel, "detail")

        add(cardPanel, BorderLayout.CENTER)

        DtaService.getInstance().addListener(this)
    }

    // ========================================================================
    // Detail fetching and rendering
    // ========================================================================

    private fun fetchDetail(requestId: String) {
        if (requestId.isEmpty()) return
        val service = DtaService.getInstance()
        val pkg = service.selectedApp?.packageName() ?: return
        val device = service.selectedDevice?.serial()

        detailContainer.removeAll()
        detailContainer.add(JBLabel("Loading..."))
        detailContainer.revalidate()
        detailContainer.repaint()
        cardLayout.show(cardPanel, "detail")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val json = service.fetchNetworkRequestDetail(pkg, requestId, device)
                SwingUtilities.invokeLater { renderDetail(json) }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    detailContainer.removeAll()
                    detailContainer.add(JBLabel("Error: ${e.message}"))
                    detailContainer.revalidate()
                    detailContainer.repaint()
                }
            }
        }
    }

    private fun renderDetail(json: String) {
        detailContainer.removeAll()

        val method = extractStringValue(json, "method") ?: ""
        val url = extractStringValue(json, "url") ?: ""
        val responseCode = extractInt(json, "responseCode")
        val responseMessage = extractStringValue(json, "responseMessage") ?: ""
        val protocol = extractStringValue(json, "protocol") ?: ""
        val source = extractStringValue(json, "source") ?: ""
        val duration = extractLong(json, "duration")
        val status = extractStringValue(json, "status") ?: ""
        val mocked = extractBoolean(json, "mocked")

        // General section
        val generalLines = StringBuilder()
        generalLines.appendLine("Method: $method")
        generalLines.appendLine("URL: $url")
        if (responseCode != null) generalLines.appendLine("Response: $responseCode $responseMessage")
        if (protocol.isNotEmpty()) generalLines.appendLine("Protocol: $protocol")
        if (source.isNotEmpty()) generalLines.appendLine("Source: $source")
        if (duration != null) generalLines.appendLine("Duration: ${duration}ms")
        if (status.isNotEmpty()) generalLines.appendLine("Status: $status")
        if (mocked != null) generalLines.appendLine("Mocked: $mocked")
        detailContainer.add(createSection("General", generalLines.toString().trimEnd()))

        // Request Headers
        val reqHeaders = extractObject(json, "requestHeaders")
        if (reqHeaders != null) {
            val headersText = formatHeadersToString(reqHeaders)
            if (headersText.isNotEmpty()) {
                detailContainer.add(createSection("Request Headers", headersText))
            }
        }

        // Response Headers
        val respHeaders = extractObject(json, "responseHeaders")
        if (respHeaders != null) {
            val headersText = formatHeadersToString(respHeaders)
            if (headersText.isNotEmpty()) {
                detailContainer.add(createSection("Response Headers", headersText))
            }
        }

        // Response Body
        val body = extractStringValue(json, "responseBody")
        if (!body.isNullOrEmpty()) {
            val contentType = extractStringValue(json, "responseContentType") ?: ""
            val displayBody = if (contentType.contains("application/json", ignoreCase = true)) {
                prettyPrintJson(body)
            } else {
                body
            }
            detailContainer.add(createSection("Response Body", displayBody))
        }

        detailContainer.revalidate()
        detailContainer.repaint()
    }

    private fun createSection(title: String, content: String): JPanel {
        val section = JPanel(BorderLayout())
        section.border = BorderFactory.createCompoundBorder(
            JBUI.Borders.empty(4, 8),
            BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title),
                JBUI.Borders.empty(4)
            )
        )
        section.alignmentX = LEFT_ALIGNMENT

        val textArea = JTextArea(content).apply {
            isEditable = false
            font = Font("JetBrains Mono", Font.PLAIN, 12)
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(4)
        }

        val copyButton = JButton("Copy").apply {
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(content))
            }
        }
        val buttonBar = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(copyButton)
        }

        section.add(buttonBar, BorderLayout.NORTH)
        section.add(textArea, BorderLayout.CENTER)

        // Let the section size itself based on content
        section.maximumSize = java.awt.Dimension(Int.MAX_VALUE, section.preferredSize.height)

        return section
    }

    // ========================================================================
    // Listener
    // ========================================================================

    override fun onNetworkDataChanged(json: String?) {
        if (json != null) {
            tableModel.updateData(parseRequests(json))
        } else {
            tableModel.updateData(emptyList())
            detailContainer.removeAll()
            detailContainer.revalidate()
            cardLayout.show(cardPanel, "list")
        }
    }

    // ========================================================================
    // List parsing
    // ========================================================================

    private fun parseRequests(json: String): List<NetworkRequest> {
        val requests = mutableListOf<NetworkRequest>()
        val trimmed = json.trim()
        val arrayJson = if (trimmed.startsWith("[")) trimmed
        else extractArray(trimmed, "requests") ?: return requests

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

    private fun parseRequest(obj: String) = NetworkRequest(
        id = extractStringValue(obj, "id") ?: "",
        method = extractStringValue(obj, "method") ?: "",
        url = extractStringValue(obj, "url") ?: "",
        status = extractInt(obj, "responseCode") ?: extractInt(obj, "statusCode") ?: 0,
        durationMs = extractLong(obj, "durationMs") ?: extractLong(obj, "duration") ?: 0L,
        source = extractStringValue(obj, "source") ?: ""
    )

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

    private fun extractBoolean(json: String, field: String): Boolean? {
        val key = "\"$field\""
        val keyIdx = json.indexOf(key)
        if (keyIdx < 0) return null
        val colonIdx = json.indexOf(':', keyIdx + key.length)
        if (colonIdx < 0) return null
        val rest = json.substring(colonIdx + 1).trimStart()
        return when {
            rest.startsWith("true") -> true
            rest.startsWith("false") -> false
            else -> null
        }
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

    private fun extractObject(json: String, field: String): String? {
        val key = "\"$field\""
        val keyIdx = json.indexOf(key)
        if (keyIdx < 0) return null
        val colonIdx = json.indexOf(':', keyIdx + key.length)
        if (colonIdx < 0) return null
        val objStart = json.indexOf('{', colonIdx + 1)
        if (objStart < 0) return null
        // Make sure no other value type appears before the brace
        val between = json.substring(colonIdx + 1, objStart).trim()
        if (between.isNotEmpty()) return null
        return json.substring(objStart, findClosingBrace(json, objStart) + 1)
    }

    private fun formatHeadersToString(headersObj: String): String {
        val sb = StringBuilder()
        // Parse key-value pairs from JSON object using proper string extraction
        var i = 0
        while (i < headersObj.length) {
            // Find next key
            val keyQuoteStart = headersObj.indexOf('"', i)
            if (keyQuoteStart < 0) break
            val keyResult = readJsonString(headersObj, keyQuoteStart)
            if (keyResult == null) break
            val key = keyResult.first
            i = keyResult.second
            // Find colon
            val colonIdx = headersObj.indexOf(':', i)
            if (colonIdx < 0) break
            i = colonIdx + 1
            // Skip whitespace to find value start
            while (i < headersObj.length && headersObj[i].isWhitespace()) i++
            if (i >= headersObj.length) break
            if (headersObj[i] == '"') {
                val valResult = readJsonString(headersObj, i)
                if (valResult != null) {
                    sb.appendLine("$key: ${valResult.first}")
                    i = valResult.second
                } else {
                    break
                }
            } else {
                // Non-string value — read until comma or closing brace
                val valueEnd = headersObj.indexOfAny(charArrayOf(',', '}'), i)
                if (valueEnd < 0) break
                sb.appendLine("$key: ${headersObj.substring(i, valueEnd).trim()}")
                i = valueEnd + 1
            }
        }
        return sb.toString().trimEnd()
    }

    /**
     * Reads a JSON string starting at the opening quote at [start].
     * Returns the unescaped string content and the index after the closing quote.
     */
    private fun readJsonString(json: String, start: Int): Pair<String, Int>? {
        if (start >= json.length || json[start] != '"') return null
        var i = start + 1
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
                return Pair(sb.toString(), i + 1)
            } else {
                sb.append(c)
                i++
            }
        }
        return null
    }

    /**
     * Simple JSON pretty-printer that does not depend on any JSON library.
     * Handles nested objects, arrays, strings (with escape sequences), and primitives.
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

    /** Helper to find the first index >= [startIndex] where [predicate] matches. */
    private inline fun String.indexOfFirst(startIndex: Int, predicate: (Char) -> Boolean): Int {
        for (i in startIndex until length) {
            if (predicate(this[i])) return i
        }
        return -1
    }

    // ========================================================================
    // Data / Model
    // ========================================================================

    data class NetworkRequest(
        val id: String, val method: String, val url: String,
        val status: Int, val durationMs: Long, val source: String
    )

    private class NetworkTableModel : AbstractTableModel() {
        private val columns = arrayOf("Method", "URL", "Status", "Duration", "Source")
        private var rows: List<NetworkRequest> = emptyList()

        fun updateData(data: List<NetworkRequest>) { rows = data; fireTableDataChanged() }
        fun getRequest(row: Int): NetworkRequest = rows[row]
        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val req = rows[rowIndex]
            return when (columnIndex) {
                0 -> req.method; 1 -> req.url
                2 -> if (req.status > 0) req.status.toString() else "-"
                3 -> if (req.durationMs > 0) "${req.durationMs}ms" else "-"
                4 -> req.source; else -> ""
            }
        }
    }

    private fun clearNetworkRequests() {
        val service = DtaService.getInstance()
        val pkg = service.selectedApp?.packageName() ?: return
        val device = service.selectedDevice?.serial()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                service.clearNetworkRequests(pkg, device)
                SwingUtilities.invokeLater {
                    tableModel.updateData(emptyList())
                    detailContainer.removeAll()
                    detailContainer.repaint()
                    cardLayout.show(cardPanel, "list")
                }
            } catch (_: Exception) {}
        }
    }
}
