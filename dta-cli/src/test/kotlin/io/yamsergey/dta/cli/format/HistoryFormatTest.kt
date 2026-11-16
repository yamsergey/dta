package io.yamsergey.dta.cli.format

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 066 T030: the app's history in the terminal (contracts/operations.md). */
class HistoryFormatTest {
    private val f = Formatters(Style.PLAIN)
    private val mb = 1024L * 1024

    @Test
    fun `the device and this computer, then each installation with what deletes it`() {
        val text = f.format("network.history", Json.parseToJsonElement(
            """{"device":{"entries":1204,"bytes":${212 * mb},"earliestMs":0,"latestMs":60000,"archivedEntries":1156,"keepBytes":${256 * mb},"warnBytes":${512 * mb}},
               "archive":{"entries":8930,"bytes":${1434 * mb},"earliestMs":0,"latestMs":60000,"installations":[
                 {"id":3,"versionName":"1.4.2","versionCode":42,"epochs":[
                   {"id":"e1","openedMs":0,"closedMs":30000,"reason":"cleared","entries":1930,"bytes":${410 * mb}},
                   {"id":"e2","openedMs":30000,"entries":5100,"bytes":${820 * mb}}]}]},
               "hostOnly":3896}""",
        ))
        val lines = text.lines()
        assertTrue(lines[0].startsWith("Device") && "1204 entries" in lines[0] && "212.0 MB" in lines[0] && "96% archived" in lines[0], lines[0])
        assertTrue("keeps 256.0 MB, warns above 512.0 MB" in lines[1], lines[1])
        assertTrue(lines[2].startsWith("This computer") && "8930 entries" in lines[2] && "1.4 GB" in lines[2] && "3896 kept here only" in lines[2], lines[2])
        assertEquals("  1.4.2 (42)  install 3", lines[3])
        assertTrue("cleared" in lines[4] && "delete: dta archive delete --epoch e1" in lines[4], lines[4])
        assertTrue("live" in lines[5] && "delete" !in lines[5], lines[5])
    }

    @Test
    fun `an unknown device part says why, and a held trim its reason`() {
        val unknown = f.format("network.history", Json.parseToJsonElement(
            """{"deviceUnknown":"the app is not running","archive":{"entries":0,"bytes":0}}""",
        ))
        assertTrue(unknown.lines()[0].startsWith("Device") && "unknown: the app is not running" in unknown.lines()[0], unknown)
        val held = f.format("network.history", Json.parseToJsonElement(
            """{"device":{"entries":70712,"bytes":${1126 * mb},"keepBytes":${256 * mb},"warnBytes":${512 * mb},"trimHeld":"no-archive","overWarning":true},"archive":{"entries":0,"bytes":0}}""",
        ))
        assertTrue("over its warning size" in held && "nothing archived yet" in held, held)
    }

    @Test
    fun `settings in force, and a clear that could not keep everything`() {
        assertEquals(
            "The device keeps 128.0 MB and warns above 384.0 MB; it removes what this computer archived, oldest first, until the history fits.",
            f.format("network.history.set", Json.parseToJsonElement("""{"keepBytes":${128 * mb},"warnBytes":${384 * mb}}""")),
        )
        assertEquals("Cleared 12 entries.", f.format("network.clear", Json.parseToJsonElement("""{"clearedEntries":12,"clearedAtMs":1}""")))
        assertEquals(
            "Cleared 12 entries. 2 entries and 3 bodies were not on this computer yet and are gone: the copy did not finish in 20 s.",
            f.format("network.clear", Json.parseToJsonElement("""{"clearedEntries":12,"clearedAtMs":1,"notKept":{"entries":2,"bodies":3,"reason":"the copy did not finish in 20 s"}}""")),
        )
    }
}
