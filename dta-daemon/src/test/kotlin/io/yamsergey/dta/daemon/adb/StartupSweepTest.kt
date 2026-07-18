package io.yamsergey.dta.daemon.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FORWARD_LIST = """emulator-5554 tcp:37543 localabstract:dta_sidekick_com.example.app
emulator-5554 tcp:9222 localabstract:chrome_devtools_remote
emulator-5556 tcp:41000 localabstract:dta_sidekick_other.pkg
emulator-5554 tcp:8081 tcp:8081
"""

class StartupSweepTest {

    private class FakeAdb {
        val commands = mutableListOf<List<String>>()

        fun run(args: List<String>): AdbExec.Result {
            commands += args
            return if (args.contains("--list")) AdbExec.Result(0, FORWARD_LIST, "")
            else AdbExec.Result(0, "", "")
        }
    }

    @Test
    fun `sweep removes only dta_sidekick forwards, on the right devices`() {
        val fake = FakeAdb()
        val removed = PortForwards { fake.run(it) }.startupSweep()

        assertEquals(2, removed)
        val removes = fake.commands.filter { it.contains("--remove") }
        assertEquals(
            listOf(
                listOf("-s", "emulator-5554", "forward", "--remove", "tcp:37543"),
                listOf("-s", "emulator-5556", "forward", "--remove", "tcp:41000"),
            ),
            removes,
        )
    }

    @Test
    fun `foreign forwards are never touched`() {
        val fake = FakeAdb()
        PortForwards { fake.run(it) }.startupSweep()

        val removedTargets = fake.commands.filter { it.contains("--remove") }.map { it.last() }
        assertTrue("tcp:9222" !in removedTargets, "chrome_devtools_remote must survive the sweep")
        assertTrue("tcp:8081" !in removedTargets, "tcp-to-tcp forwards must survive the sweep")
    }

    @Test
    fun `empty forward list sweeps nothing`() {
        var removed = -1
        val forwards = PortForwards { args ->
            if (args.contains("--list")) AdbExec.Result(0, "", "") else AdbExec.Result(0, "", "")
        }
        removed = forwards.startupSweep()
        assertEquals(0, removed)
    }
}
