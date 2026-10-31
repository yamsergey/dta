package io.yamsergey.dta.present.archive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 011 T022: the archive browser's rows, shared by the plugin and (later) the terminal UI. */
class ArchiveTreeTest {
    private val listing = Json.parseToJsonElement(
        """{"devices":[{"key":"abc","serial":"emulator-5554","model":"Pixel 8","apps":[{"packageName":"io.example","installations":[
            {"id":3,"versionName":"1.4.0","versionCode":14,"epochs":[
              {"id":"e1","openedMs":0,"closedMs":60000,"reason":"cleared","entries":120,"bytes":2097152,"neverReceived":3},
              {"id":"e2","openedMs":60000,"entries":4,"bytes":0}]}]}]}],"bytes":2097152}""",
    ).jsonObject

    @Test
    fun `rows nest device, app, install and epochs, each with what delete and show need`() {
        val rows = ArchiveTree.rows(listing, ZoneOffset.UTC)
        assertEquals(listOf(0, 1, 2, 3, 3), rows.map { it.depth })
        assertEquals("Pixel 8 · emulator-5554", rows[0].text)
        assertEquals("io.example", rows[1].text)
        assertEquals("io.example", rows[1].packageName)
        assertEquals("1.4.0 (14)", rows[2].text)
        assertEquals(3L, rows[2].installation)
        val closed = rows[3]
        assertEquals("e1", closed.epoch)
        assertEquals("1970-01-01 00:00 → 00:01 · cleared · 120 entries · 2.0 MB", closed.text)
        assertEquals("3 entries never reached this computer", closed.note)
        val live = rows[4]
        assertEquals(true, live.live)
        assertEquals("1970-01-01 00:01 → now · live · 4 entries · 0 B", live.text)
        assertNull(live.note)
    }

    @Test
    fun `an empty archive says so`() {
        assertEquals(emptyList(), ArchiveTree.rows(Json.parseToJsonElement("""{"devices":[],"bytes":0}""").jsonObject, ZoneOffset.UTC))
        assertEquals("Nothing archived yet: when a device clears its history, this computer keeps it here.", ArchiveTree.EMPTY)
    }
}
