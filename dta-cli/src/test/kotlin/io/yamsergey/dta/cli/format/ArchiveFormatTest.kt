package io.yamsergey.dta.cli.format

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 011 T021: the archive in the terminal. */
class ArchiveFormatTest {
    private val f = Formatters(Style.PLAIN)

    @Test
    fun `the archive is a tree - device, app, install, epochs with why they closed`() {
        val listing = Json.parseToJsonElement(
            """{"devices":[{"key":"abc","serial":"emulator-5554","model":"Pixel 8","apps":[{"packageName":"io.example","installations":[
                {"id":3,"versionName":"1.4.0","versionCode":14,"epochs":[
                  {"id":"e1","openedMs":0,"closedMs":60000,"reason":"cleared","entries":120,"bytes":2097152,"neverReceived":3},
                  {"id":"e2","openedMs":60000,"entries":4,"bytes":0}]}]}]}],"bytes":2097152}""",
        )
        val text = f.format("archive.list", listing)
        val lines = text.lines()
        assertEquals("Pixel 8  emulator-5554", lines[0])
        assertEquals("  io.example", lines[1])
        assertEquals("    1.4.0 (14)  install 3", lines[2])
        assertTrue(lines[3].startsWith("      e1  ") && "cleared" in lines[3] && "120 entries" in lines[3] && "2.0 MB" in lines[3] && "3 never reached this computer" in lines[3], lines[3])
        assertTrue(lines[4].startsWith("      e2  ") && "live" in lines[4], lines[4])
        assertTrue("2.0 MB on disk" in text, text)
        assertEquals("Nothing archived yet.", f.format("archive.list", Json.parseToJsonElement("""{"devices":[],"bytes":0}""")))
    }

    @Test
    fun `delete says what it would remove, and what it removed`() {
        val preview = f.format("archive.delete", Json.parseToJsonElement("""{"confirmed":false,"removed":{"epochs":2,"entries":40,"bodiesFreed":5,"bytesFreed":1048576}}"""))
        assertEquals("Would remove 2 epochs, 40 entries and 5 bodies (1.0 MB). Run again with --confirm to delete them.", preview)
        val done = f.format("archive.delete", Json.parseToJsonElement("""{"confirmed":true,"removed":{"epochs":2,"entries":40,"bodiesFreed":5,"bytesFreed":1048576}}"""))
        assertEquals("Removed 2 epochs, 40 entries and 5 bodies (1.0 MB).", done)
    }

    @Test
    fun `archived entries are marked in the list`() {
        val page = Json.parseToJsonElement(
            """{"entries":[{"kind":"http","id":"r1-1:1","runId":"r1-1","timeMs":0,"library":"okhttp3","method":"GET","url":"https://api/cart","state":"complete","status":200}],
               "total":1,"coverage":{"searched":1,"stored":1,"persisted":true},
               "archived":{"r1-1:1":{"epoch":"e1","reason":"cleared","closedMs":5,"version":"1.4.0"}}}""",
        )
        assertTrue("! archived" in f.format("network.list", page) || "archived" in f.format("network.list", page).lines().first(), f.format("network.list", page))
    }
}
