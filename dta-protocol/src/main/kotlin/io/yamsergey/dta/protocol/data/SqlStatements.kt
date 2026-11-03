package io.yamsergey.dta.protocol.data

/** How a statement runs (research R2): a query returns rows, the others report changes. */
enum class SqlKind { QUERY, UPDATE_DELETE, INSERT, OTHER }

object SqlStatements {
    private val QUERY_WORDS = setOf("SELECT", "PRAGMA", "WITH", "EXPLAIN", "VALUES")

    fun kind(sql: String): SqlKind = when (firstWord(sql)) {
        in QUERY_WORDS -> SqlKind.QUERY
        "UPDATE", "DELETE" -> SqlKind.UPDATE_DELETE
        "INSERT", "REPLACE" -> SqlKind.INSERT
        else -> SqlKind.OTHER
    }

    private fun firstWord(sql: String): String {
        var i = 0
        while (i < sql.length) {
            when {
                sql[i].isWhitespace() -> i++
                sql.startsWith("--", i) -> i = sql.indexOf('\n', i).let { if (it < 0) sql.length else it + 1 }
                sql.startsWith("/*", i) -> i = sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }
                else -> break
            }
        }
        val end = (i until sql.length).firstOrNull { !sql[it].isLetter() } ?: sql.length
        return sql.substring(i, end).uppercase()
    }

    fun identifier(name: String) = "\"" + name.replace("\"", "\"\"") + "\""

    /** A cell as a SQL literal (for statements the plugin builds from a grid edit). */
    fun literal(cell: Cell): String = when (cell.type) {
        CellType.NULL -> "NULL"
        CellType.INTEGER, CellType.REAL -> cell.value ?: "NULL"
        CellType.TEXT -> "'" + (cell.value ?: "").replace("'", "''") + "'"
        CellType.BLOB -> "X'" + java.util.Base64.getDecoder().decode(cell.value ?: "").joinToString("") { "%02x".format(it) } + "'"
    }
}
