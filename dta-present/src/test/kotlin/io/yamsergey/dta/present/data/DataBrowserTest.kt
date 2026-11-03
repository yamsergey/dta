package io.yamsergey.dta.present.data

import io.yamsergey.dta.protocol.data.Access
import io.yamsergey.dta.protocol.data.Cell
import io.yamsergey.dta.protocol.data.CellType
import io.yamsergey.dta.protocol.data.DbColumn
import io.yamsergey.dta.protocol.data.DbObject
import io.yamsergey.dta.protocol.data.FileRoot
import io.yamsergey.dta.protocol.data.PrefEntry
import io.yamsergey.dta.protocol.data.PrefType
import io.yamsergey.dta.protocol.data.QueryPage
import io.yamsergey.dta.protocol.data.StoreInfo
import io.yamsergey.dta.protocol.data.StoreKind
import io.yamsergey.dta.protocol.data.StoreList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DataBrowserTest {
    private val list = StoreList(
        listOf(
            StoreInfo("db:notes.db", StoreKind.DATABASE, "notes.db", "/d/notes.db", 4096, "room", Access.LIVE, "the app's open database", 3),
            StoreInfo("prefs:settings", StoreKind.PREFS, "settings", "/p", 10, "plain", Access.LIVE, "own", 4),
            StoreInfo("datastore:settings.preferences_pb", StoreKind.DATASTORE, "settings.preferences_pb", "/x", 15, "preferences", Access.LIVE, "flow", 1),
            StoreInfo("datastore:profile.pb", StoreKind.DATASTORE, "profile.pb", "/y", 9, "proto", Access.FILE, "file"),
        ),
        listOf(FileRoot("data", "/data/user/0/app")),
    )

    @Test
    fun `the tree groups stores by kind, names their flavor and access, and ends with the file roots`() {
        val groups = DataBrowser.tree(list)
        assertEquals(listOf("Databases", "Preferences", "DataStore", "Files"), groups.map { it.label })
        val db = groups[0].children.single()
        assertEquals(DataNodeKind.DATABASE, db.kind)
        assertEquals("notes.db", db.label)
        assertEquals("room · live", db.detail)
        assertEquals(DataNodeKind.PREFS, groups[2].children[0].kind)
        assertEquals(DataNodeKind.PROTO, groups[2].children[1].kind)
        assertEquals(DataNodeKind.FOLDER, groups[3].children.single().kind)
        assertEquals("data:/", groups[3].children.single().path)
    }

    @Test
    fun `a database's tables and views become its children, sqlite internals left out`() {
        val tables = DataBrowser.tables(
            "db:notes.db",
            listOf(
                DbObject("notes", "table", rowCount = 2), DbObject("room_master_table", "table", rowCount = 1),
                DbObject("v", "view"), DbObject("ix", "index", table = "notes"), DbObject("android_metadata", "table", rowCount = 1),
            ),
        )
        assertEquals(listOf("notes" to "2 rows", "room_master_table" to "1 row", "v" to "view"), tables.map { it.label to it.detail })
        assertEquals(DataNodeKind.TABLE, tables[0].kind)
    }

    private val notes = DbObject(
        "notes", "table",
        columns = listOf(DbColumn("id", "INTEGER", primaryKey = 1), DbColumn("title", "TEXT", notNull = true), DbColumn("done", "INTEGER")),
        rowCount = 2,
    )

    @Test
    fun `a table's page is read with its rowid, which the grid hides and edits by`() {
        val grid = RowGrid(notes)
        assertEquals("SELECT rowid AS \"_dta_rowid\", * FROM \"notes\"", grid.select)
        val page = QueryPage(
            "db:notes.db", listOf("_dta_rowid", "id", "title", "done"),
            listOf(listOf(Cell(CellType.INTEGER, "1"), Cell(CellType.INTEGER, "1"), Cell(CellType.TEXT, "Milk"), Cell(CellType.INTEGER, "0"))),
            0, 2,
        )
        grid.show(page)
        assertEquals(listOf("id", "title", "done"), grid.columns)
        assertEquals("Milk", grid.text(0, 1))
        assertEquals("UPDATE \"notes\" SET \"title\" = 'O''Brien' WHERE rowid = 1", grid.update(0, 1, "O'Brien"))
        assertEquals("UPDATE \"notes\" SET \"done\" = 1 WHERE rowid = 1", grid.update(0, 2, "1"))
        assertEquals("UPDATE \"notes\" SET \"done\" = NULL WHERE rowid = 1", grid.update(0, 2, "NULL"))
        assertEquals("DELETE FROM \"notes\" WHERE rowid = 1", grid.delete(0))
        assertEquals("INSERT INTO \"notes\" DEFAULT VALUES", grid.insert())
    }

    @Test
    fun `a WITHOUT ROWID table edits by its primary key, a view not at all, blobs not in place`() {
        val kv = DbObject("kv", "table", columns = listOf(DbColumn("k", "TEXT", primaryKey = 1), DbColumn("v", "BLOB")), withoutRowid = true)
        val grid = RowGrid(kv)
        assertEquals("SELECT * FROM \"kv\"", grid.select)
        grid.show(QueryPage("db:x", listOf("k", "v"), listOf(listOf(Cell(CellType.TEXT, "a"), Cell(CellType.BLOB, "00ff", 2))), 0, 1))
        assertEquals("<2 B 00ff>", grid.text(0, 1))
        assertEquals("UPDATE \"kv\" SET \"k\" = 'b' WHERE \"k\" = 'a'", grid.update(0, 0, "b"))
        assertEquals("blobs are not edited in place: run an UPDATE with X'…' in the SQL console", assertFailsWith<IllegalArgumentException> { grid.update(0, 1, "x") }.message)
        val view = RowGrid(DbObject("v", "view", columns = listOf(DbColumn("a"))))
        assertNull(view.editable)
        assertEquals("views are read-only", view.readOnlyReason)
    }

    @Test
    fun `preferences edit with the key's type, a new key with a chosen one`() {
        val prefs = PrefsGrid(listOf(PrefEntry("dark", PrefType.BOOLEAN, JsonPrimitive(false)), PrefEntry("tags", PrefType.STRING_SET, JsonArray(listOf(JsonPrimitive("a"))))))
        assertEquals(listOf("dark", "boolean", "false"), prefs.row(0))
        assertEquals(listOf("tags", "stringSet", "[\"a\"]"), prefs.row(1))
        assertEquals(Triple("dark", "true", PrefType.BOOLEAN), prefs.edit(0, "true"))
        assertEquals("dark takes a boolean (true or false); got \"yes\"", assertFailsWith<IllegalArgumentException> { prefs.edit(0, "yes") }.message)
    }
}
