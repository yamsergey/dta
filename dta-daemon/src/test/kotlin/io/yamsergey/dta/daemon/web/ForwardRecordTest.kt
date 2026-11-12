package io.yamsergey.dta.daemon.web

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Feature 061: the daemon removes the DevTools forwards it left behind, and only those. */
class ForwardRecordTest {
    private fun file(): File = Files.createTempDirectory("fwd").toFile().resolve("web-forwards.json")

    @Test
    fun `forwards are kept in the file until removed`() {
        val f = file()
        val r = ForwardRecord(f)
        r.add("emulator-5554", 40001, "webview_devtools_remote_123")
        r.add("emulator-5554", 40002, "chrome_devtools_remote")
        r.remove("emulator-5554", 40001)
        assertEquals(mapOf("emulator-5554" to mapOf(40002 to "chrome_devtools_remote")), ForwardRecord(f).all())
    }

    @Test
    fun `leftovers are the recorded ones still listed, pointing where they did`() {
        val f = file()
        val r = ForwardRecord(f)
        r.add("emulator-5554", 40001, "webview_devtools_remote_123")
        r.add("emulator-5554", 40002, "chrome_devtools_remote")
        r.add("R5CT", 40003, "chrome_devtools_remote")
        val list = """
            emulator-5554 tcp:40001 localabstract:webview_devtools_remote_123
            emulator-5554 tcp:9222 localabstract:chrome_devtools_remote
            emulator-5554 tcp:40002 localabstract:something_else
            other-device tcp:40003 localabstract:chrome_devtools_remote
        """.trimIndent()
        // 9222 is another tool's; 40002 now points elsewhere (the port was reused); 40003 is on another device.
        assertEquals(listOf("emulator-5554" to 40001), ForwardRecord(f).leftovers(list))
    }

    @Test
    fun `a missing or broken file is an empty record`() {
        val f = file()
        assertEquals(emptyMap(), ForwardRecord(f).all())
        f.writeText("not json")
        assertEquals(emptyMap(), ForwardRecord(f).all())
        ForwardRecord(f).clear()
        assertEquals(emptyMap(), ForwardRecord(f).all())
    }
}
