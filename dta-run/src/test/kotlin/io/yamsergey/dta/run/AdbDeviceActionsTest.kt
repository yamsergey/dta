package io.yamsergey.dta.run

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** T019: the device commands' output, as recorded from an emulator. */
class AdbDeviceActionsTest {

    @Test
    fun `the launcher activity is the last line of resolve-activity`() {
        assertEquals(
            "io.example.shop/.MainActivity",
            AdbDeviceActions.parseLauncher("priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\nio.example.shop/.MainActivity\n"),
        )
        assertNull(AdbDeviceActions.parseLauncher("No activity found\n"))
        assertNull(AdbDeviceActions.parseLauncher(""))
    }

    @Test
    fun `am start succeeds, or says why not`() {
        val ok = """
            Stopping: io.example.shop
            Starting: Intent { cmp=io.example.shop/.MainActivity }
            Status: ok
            LaunchState: COLD
            Activity: io.example.shop/.MainActivity
            TotalTime: 512
            WaitTime: 515
            Complete
        """.trimIndent()
        assertEquals(LaunchOutcome.Started(restarted = false), AdbDeviceActions.parseStart(ok, 0))
        val missing = "Starting: Intent { cmp=io.example.shop/.Nope }\nError type 3\nError: Activity class {io.example.shop/io.example.shop.Nope} does not exist.\n"
        assertEquals(LaunchOutcome.Failed("Activity class {io.example.shop/io.example.shop.Nope} does not exist."), AdbDeviceActions.parseStart(missing, 0))
        assertEquals(LaunchOutcome.Failed("Security exception: Permission Denial"), AdbDeviceActions.parseStart("Security exception: Permission Denial\n", 255))
    }

    @Test
    fun `pidof gives a pid or nothing`() {
        assertEquals(4242L, AdbDeviceActions.parsePid("4242\n"))
        assertEquals(4242L, AdbDeviceActions.parsePid("4242 4250\n"))
        assertNull(AdbDeviceActions.parsePid("\n"))
    }
}
