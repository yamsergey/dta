package io.yamsergey.dta.present.data

import io.yamsergey.dta.protocol.data.Cell
import io.yamsergey.dta.protocol.data.CellType
import io.yamsergey.dta.protocol.data.DbObject
import io.yamsergey.dta.protocol.data.PrefEntry
import io.yamsergey.dta.protocol.data.PrefType
import io.yamsergey.dta.protocol.data.QueryPage
import io.yamsergey.dta.protocol.data.SqlStatements
import io.yamsergey.dta.protocol.data.StoreKind
import io.yamsergey.dta.protocol.data.StoreList
import kotlinx.serialization.json.JsonPrimitive

enum class DataNodeKind { GROUP, DATABASE, TABLE, PREFS, PROTO, FOLDER, FILE }

/** One node of the Data tab's tree (feature 027 US6). */
data class DataNode(
    val kind: DataNodeKind,
    val label: String,
    val detail: String = "",
    val store: String? = null,
    val table: String? = null,
    val path: String? = null,
    val children: List<DataNode> = emptyList(),
)

object DataBrowser {
    fun tree(list: StoreList): List<DataNode> {
        fun group(label: String, kind: StoreKind) = list.stores.filter { it.kind == kind }.map { s ->
            val nodeKind = when {
                kind == StoreKind.DATABASE -> DataNodeKind.DATABASE
                kind == StoreKind.DATASTORE && s.flavor == "proto" -> DataNodeKind.PROTO
                else -> DataNodeKind.PREFS
            }
            DataNode(nodeKind, s.name, "${s.flavor} · ${s.access.name.lowercase()}", store = s.id)
        }.let { DataNode(DataNodeKind.GROUP, label, children = it) }
        return listOf(
            group("Databases", StoreKind.DATABASE),
            group("Preferences", StoreKind.PREFS),
            group("DataStore", StoreKind.DATASTORE),
            DataNode(DataNodeKind.GROUP, "Files", children = list.roots.map { DataNode(DataNodeKind.FOLDER, "${it.name}:/", it.path, path = "${it.name}:/") }),
        )
    }

    private val INTERNAL = setOf("android_metadata", "sqlite_sequence")

    fun tables(store: String, objects: List<DbObject>): List<DataNode> =
        objects.filter { (it.type == "table" || it.type == "view") && it.name !in INTERNAL }.map { o ->
            val detail = if (o.type == "view") "view" else o.rowCount?.let { "$it ${if (it == 1L) "row" else "rows"}" } ?: ""
            DataNode(DataNodeKind.TABLE, o.name, detail, store = store, table = o.name)
        }
}

/**
 * A table shown as a grid (feature 027 US6): the page query, cells as text, and the statements an
 * edit makes — by rowid, or by the primary key of a WITHOUT ROWID table; views are read-only.
 */
class RowGrid(private val table: DbObject) {
    private val name = SqlStatements.identifier(table.name)
    private val byRowid = table.type == "table" && !table.withoutRowid
    private val keyColumns = table.columns.filter { it.primaryKey > 0 }.sortedBy { it.primaryKey }.map { it.name }

    val readOnlyReason: String? = when {
        table.type != "table" -> "views are read-only"
        !byRowid && keyColumns.isEmpty() -> "a table without rowid or primary key cannot be edited by row"
        else -> null
    }

    val editable: RowGrid? get() = if (readOnlyReason == null) this else null

    val select: String = if (byRowid) "SELECT rowid AS \"$ROWID\", * FROM $name" else "SELECT * FROM $name"

    private var page: QueryPage? = null
    var columns: List<String> = emptyList()
        private set
    private var rows: List<List<Cell>> = emptyList()
    private var rowids: List<String?> = emptyList()

    fun show(p: QueryPage) {
        page = p
        val skip = if (byRowid && p.columns.firstOrNull() == ROWID) 1 else 0
        columns = p.columns.drop(skip)
        rows = p.rows.map { it.drop(skip) }
        rowids = p.rows.map { if (skip == 1) it.first().value else null }
    }

    val rowCount get() = rows.size

    fun text(row: Int, column: Int): String = DataText.cell(rows[row][column])

    fun cell(row: Int, column: Int): Cell = rows[row][column]

    fun update(row: Int, column: Int, text: String): String {
        require(readOnlyReason == null) { readOnlyReason!! }
        require(rows[row][column].type != CellType.BLOB) { "blobs are not edited in place: run an UPDATE with X'…' in the SQL console" }
        val declared = table.columns.firstOrNull { it.name == columns[column] }?.type.orEmpty().uppercase()
        val literal = when {
            text == "NULL" -> "NULL"
            ("INT" in declared || "REAL" in declared || "FLOA" in declared || "DOUB" in declared || "NUM" in declared) && text.toDoubleOrNull() != null -> text
            else -> SqlStatements.literal(Cell(CellType.TEXT, text))
        }
        return "UPDATE $name SET ${SqlStatements.identifier(columns[column])} = $literal WHERE ${where(row)}"
    }

    fun delete(row: Int): String {
        require(readOnlyReason == null) { readOnlyReason!! }
        return "DELETE FROM $name WHERE ${where(row)}"
    }

    fun insert(): String {
        require(readOnlyReason == null) { readOnlyReason!! }
        return "INSERT INTO $name DEFAULT VALUES"
    }

    private fun where(row: Int): String =
        if (byRowid) "rowid = ${rowids[row]}"
        else keyColumns.joinToString(" AND ") { k -> "${SqlStatements.identifier(k)} = ${SqlStatements.literal(rows[row][columns.indexOf(k)])}" }

    companion object {
        const val ROWID = "_dta_rowid"
    }
}

/** A preferences store as key / type / value rows, edited with the key's type. */
class PrefsGrid(val entries: List<PrefEntry>) {
    fun row(i: Int): List<String> = entries[i].let { e -> listOf(e.key, e.type.wireName, (e.value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: e.value.toString()) }

    /** The set an edit of row [i]'s value makes: key, value text, type; refuses a value that does not fit. */
    fun edit(i: Int, text: String): Triple<String, String, PrefType> {
        val e = entries[i]
        e.type.parseFor(e.key, text)
        return Triple(e.key, text, e.type)
    }
}
