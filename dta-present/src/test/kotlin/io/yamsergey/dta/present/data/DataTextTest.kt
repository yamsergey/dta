package io.yamsergey.dta.present.data

import io.yamsergey.dta.protocol.data.Access
import io.yamsergey.dta.protocol.data.Cell
import io.yamsergey.dta.protocol.data.CellType
import io.yamsergey.dta.protocol.data.FileEntry
import io.yamsergey.dta.protocol.data.FileListing
import io.yamsergey.dta.protocol.data.PrefEntry
import io.yamsergey.dta.protocol.data.PrefType
import io.yamsergey.dta.protocol.data.PrefsContent
import io.yamsergey.dta.protocol.data.QueryPage
import io.yamsergey.dta.protocol.data.StoreInfo
import io.yamsergey.dta.protocol.data.StoreKind
import io.yamsergey.dta.protocol.data.StoreList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class DataTextTest {
    @Test
    fun `stores are grouped by kind with their access`() {
        val list = StoreList(
            listOf(
                StoreInfo("db:notes.db", StoreKind.DATABASE, "notes.db", "/d/notes.db", 20480, "room", Access.LIVE, "the app's open database", 3),
                StoreInfo("prefs:secure", StoreKind.PREFS, "secure", "/p/secure.xml", 900, "encrypted", Access.FILE, "encrypted; DTA tries the default master key"),
            ),
        )
        assertEquals(
            """
            Databases
              db:notes.db   room       20.0 KB  3 tables  live: the app's open database
            Preferences
              prefs:secure  encrypted  900 B              file: encrypted; DTA tries the default master key
            """.trimIndent(),
            DataText.stores(list),
        )
    }

    @Test
    fun `rows print as a table with typed cells and the page position`() {
        val page = QueryPage(
            "db:notes.db", listOf("id", "title", "icon"),
            listOf(
                listOf(Cell(CellType.INTEGER, "1"), Cell(CellType.TEXT, "Milk"), Cell(CellType.BLOB, "89504e47", 120, truncated = true)),
                listOf(Cell(CellType.INTEGER, "2"), Cell.NULL, Cell(CellType.BLOB, "00", 1)),
            ),
            offset = 0, total = 5,
        )
        assertEquals(
            """
            id  title  icon
            1   Milk   <120 B 89504e47…>
            2   NULL   <1 B 00>
            Rows 1–2 of 5.
            """.trimIndent(),
            DataText.rows(page),
        )
    }

    @Test
    fun `preferences show type and value, a folder its entries`() {
        val prefs = PrefsContent(
            "prefs:settings",
            listOf(PrefEntry("dark", PrefType.BOOLEAN, JsonPrimitive(true)), PrefEntry("tags", PrefType.STRING_SET, JsonArray(listOf(JsonPrimitive("a"))))),
            Access.LIVE, "the app's own SharedPreferences",
        )
        assertEquals("dark  boolean    true\ntags  stringSet  [\"a\"]\n(live: the app's own SharedPreferences)", DataText.prefs(prefs))
        val listing = FileListing("data:/files", listOf(FileEntry("cache", "data:/files/cache", true, children = 2), FileEntry("a.txt", "data:/files/a.txt", false, 1536, 0)), total = 2)
        assertEquals("data:/files (2 entries)\n  cache/  2 entries\n  a.txt   1.5 KB", DataText.files(listing))
    }
}
