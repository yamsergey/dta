package io.yamsergey.dta.daemon

import io.yamsergey.dta.daemon.adb.AdbExec
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DtaDaemonOpsTest {
    private val unix = """
        Num       RefCount Protocol Flags    Type St Inode Path
        0000000000000000: 00000002 00000000 00010000 0001 01 12345 @dta_sidekick_com.example.shop
        0000000000000000: 00000002 00000000 00010000 0001 01 12346 @dta_sidekick_com.example.news
    """.trimIndent()

    private fun daemon(adb: (List<String>) -> AdbExec.Result) =
        DtaDaemon(runAdb = adb, adbPath = "/fake/adb", dataDir = Files.createTempDirectory("dta").toFile())

    private val fake: (List<String>) -> AdbExec.Result = { args ->
        when {
            args == listOf("devices", "-l") -> AdbExec.Result(0, "List of devices attached\nemulator-5554 device product:x model:Pixel_8\nR5CT unauthorized\n", "")
            args.takeLast(2) == listOf("getprop", "ro.product.model") -> AdbExec.Result(0, "Pixel 8\n", "")
            args.takeLast(2) == listOf("cat", "/proc/net/unix") -> AdbExec.Result(0, unix, "")
            "dumpsys" in args -> AdbExec.Result(0, "mCurrentFocus=Window{1 u0 com.example.shop/com.example.shop.Main}", "")
            else -> AdbExec.Result(0, "", "")
        }
    }

    @Test
    fun `devices with state and model`() {
        val devices = daemon(fake).devices()
        assertEquals(listOf("emulator-5554", "R5CT"), devices.map { it.serial })
        assertEquals("online", devices[0].state)
        assertEquals("Pixel 8", devices[0].model)
        assertEquals("unauthorized", devices[1].state)
        assertEquals(null, devices[1].model)
    }

    @Test
    fun `apps with DTA inside and the foreground flag`() {
        val apps = daemon(fake).apps("emulator-5554")
        assertEquals("com.example.shop", apps.foreground)
        assertEquals(listOf("com.example.shop" to true, "com.example.news" to false), apps.apps.map { it.packageName to it.foreground })
    }

    @Test
    fun `adb unavailable is reported, not thrown at construction`() {
        val d = DtaDaemon.withoutAdb("adb not found — set ANDROID_HOME", Files.createTempDirectory("dta").toFile())
        assertEquals(false, d.adbInfo().available)
        assertEquals("adb not found — set ANDROID_HOME", d.adbInfo().reason)
        assertFailsWith<AdbUnavailableException> { d.devices() }
        runBlocking { d.shutdown() }
    }
}
