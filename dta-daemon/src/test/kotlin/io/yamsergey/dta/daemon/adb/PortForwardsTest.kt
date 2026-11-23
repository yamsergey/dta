package io.yamsergey.dta.daemon.adb

import io.yamsergey.dta.daemon.discovery.ConnectionKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val PROC_NET_UNIX_WITH_FIXTURE = """
Num       RefCount Protocol Flags    Type St Inode Path
0000000000000000: 00000002 00000000 00010000 0001 01 52412 @dta_sidekick_io.yamsergey.dta.fixture.channel
0000000000000000: 00000002 00000000 00010000 0001 01 12345 @chrome_devtools_remote_1234
"""

class PortForwardsTest {

    private class FakeAdb {
        val commands = mutableListOf<List<String>>()
        var procNetUnix: String = PROC_NET_UNIX_WITH_FIXTURE
        var forwardStdout: String = "37543\n"
        var pidof: String = ""
        /** `cat /proc/uptime /proc/<pid>/stat`: empty when the process's age cannot be read. */
        var uptimeAndStat: String = ""
        /** The socket appears from this scan on (1 = the first); 0: as [procNetUnix] says. */
        var bindsAtScan = 0
        var scans = 0

        fun run(args: List<String>): AdbExec.Result {
            commands += args
            return when {
                args.contains("/proc/uptime") -> AdbExec.Result(if (uptimeAndStat.isEmpty()) 1 else 0, uptimeAndStat, "")
                args.contains("/proc/net/unix") -> {
                    scans++
                    AdbExec.Result(0, if (bindsAtScan in 1..scans) PROC_NET_UNIX_WITH_FIXTURE else procNetUnix, "")
                }
                args.contains("pidof") -> AdbExec.Result(if (pidof.isBlank()) 1 else 0, pidof, "")
                args.contains("forward") && !args.contains("--remove") && !args.contains("--list") ->
                    AdbExec.Result(0, forwardStdout, "")
                else -> AdbExec.Result(0, "", "")
            }
        }
    }

    private val key = ConnectionKey("emulator-5554", "io.yamsergey.dta.fixture.channel")

    @Test
    fun `forward pre-flights the socket then allocates an ephemeral port`() {
        val fake = FakeAdb()
        val forwards = PortForwards { fake.run(it) }

        val port = forwards.forward(key)

        assertEquals(37543, port)
        val forwardCmd = fake.commands.single { it.contains("forward") }
        assertEquals(
            listOf("-s", "emulator-5554", "forward", "tcp:0", "localabstract:dta_sidekick_io.yamsergey.dta.fixture.channel"),
            forwardCmd,
        )
        assertTrue(fake.commands.indexOfFirst { it.contains("/proc/net/unix") } <
            fake.commands.indexOfFirst { it.contains("forward") }, "pre-flight must run before forward")
    }

    @Test
    fun `forward refuses when the socket is absent - adb forward lies`() {
        val fake = FakeAdb()
        fake.procNetUnix = "Num RefCount Protocol\n0000: 0002 0000 @some_other_socket\n"
        val forwards = PortForwards { fake.run(it) }

        val e = assertFailsWith<AppNotRunningException> { forwards.forward(key) }
        assertTrue("launch it first" in e.message!!, "actionable message expected: ${e.message}")
        assertTrue(fake.commands.none { it.contains("forward") }, "no forward may be created")
    }

    @Test
    fun `a running app without the socket is stated as running without DTA, not as not running`() {
        val fake = FakeAdb()
        fake.procNetUnix = "Num RefCount Protocol\n0000: 0002 0000 @some_other_socket\n"
        fake.pidof = "27080\n"
        val forwards = PortForwards { fake.run(it) }

        val e = assertFailsWith<AppWithoutDtaException> { forwards.forward(key) }
        assertEquals("io.yamsergey.dta.fixture.channel is running on emulator-5554 without DTA — build it with DTA", e.message)
        assertTrue(fake.commands.none { it.contains("forward") }, "no forward may be created")
    }

    /** `/proc/uptime` then `/proc/<pid>/stat` of a process started [ageMs] ago (Android's clock ticks: 100 a second). */
    private fun uptimeAndStat(ageMs: Long): String {
        val uptimeMs = 3_600_000L
        val startTicks = (uptimeMs - ageMs) / 10
        val fields = (3..52).map { if (it == 22) startTicks.toString() else "0" }.toMutableList()
        fields[0] = "S"
        return "${uptimeMs / 1000.0} 7000.00\n27080 (yamsergey.dta.fixture.channel) ${fields.joinToString(" ")}\n"
    }

    private class Clock {
        var now = 0L
        val slept = mutableListOf<Long>()
        fun sleep(ms: Long) { slept += ms; now += ms }
    }

    /**
     * D-050: a process that has just started has no socket for a moment. Asked in that moment, the answer was
     * "without DTA — build it with DTA", wrong for an app that is starting: a young process is given a short
     * grace for its sidekick to listen.
     */
    @Test
    fun `a young process whose sidekick listens a moment later is forwarded, not called without DTA`() {
        val fake = FakeAdb()
        fake.procNetUnix = "Num RefCount Protocol\n0000: 0002 0000 @some_other_socket\n"
        fake.pidof = "27080\n"
        fake.uptimeAndStat = uptimeAndStat(ageMs = 1_000)
        fake.bindsAtScan = 3
        val clock = Clock()
        val forwards = PortForwards({ fake.run(it) }, clock = { clock.now }, sleep = clock::sleep)

        assertEquals(37543, forwards.forward(key))
        assertEquals(3, fake.scans)
        assertTrue(clock.slept.isNotEmpty() && clock.now < PortForwards.YOUNG_MS, "waited a moment only: ${clock.slept}")
    }

    @Test
    fun `a young process that never listens is called without DTA once it is no longer young`() {
        val fake = FakeAdb()
        fake.procNetUnix = "Num RefCount Protocol\n0000: 0002 0000 @some_other_socket\n"
        fake.pidof = "27080\n"
        fake.uptimeAndStat = uptimeAndStat(ageMs = 2_000)
        val clock = Clock()
        val forwards = PortForwards({ fake.run(it) }, clock = { clock.now }, sleep = clock::sleep)

        assertFailsWith<AppWithoutDtaException> { forwards.forward(key) }
        assertTrue(clock.now in (PortForwards.YOUNG_MS - 2_000)..PortForwards.YOUNG_MS, "waited until the process was ${PortForwards.YOUNG_MS} ms old: ${clock.now}")
        assertTrue(fake.commands.none { it.contains("forward") }, "no forward may be created")
    }

    @Test
    fun `an older process without the socket is called without DTA at once`() {
        val fake = FakeAdb()
        fake.procNetUnix = "Num RefCount Protocol\n0000: 0002 0000 @some_other_socket\n"
        fake.pidof = "27080\n"
        fake.uptimeAndStat = uptimeAndStat(ageMs = 60_000)
        val clock = Clock()
        val forwards = PortForwards({ fake.run(it) }, clock = { clock.now }, sleep = clock::sleep)

        assertFailsWith<AppWithoutDtaException> { forwards.forward(key) }
        assertEquals(emptyList(), clock.slept)
        assertEquals(1, fake.scans)
    }

    @Test
    fun `a young process that ends while waiting is called not running`() {
        val fake = FakeAdb()
        fake.procNetUnix = "Num RefCount Protocol\n0000: 0002 0000 @some_other_socket\n"
        fake.pidof = "27080\n"
        fake.uptimeAndStat = uptimeAndStat(ageMs = 500)
        val clock = Clock()
        val forwards = PortForwards({ fake.run(it) }, clock = { clock.now }, sleep = { ms -> clock.sleep(ms); fake.pidof = "" })

        val e = assertFailsWith<AppNotRunningException> { forwards.forward(key) }
        assertTrue(e !is AppWithoutDtaException, "the process ended: not running, not without DTA")
    }

    @Test
    fun `unparseable forward output fails with an actionable error`() {
        val fake = FakeAdb()
        fake.forwardStdout = "error: device offline\n"
        val forwards = PortForwards { fake.run(it) }

        assertFailsWith<AdbCommandException> { forwards.forward(key) }
    }

    @Test
    fun `remove issues the exact remove command`() {
        val fake = FakeAdb()
        val forwards = PortForwards { fake.run(it) }

        forwards.remove("emulator-5554", 37543)

        assertEquals(
            listOf("-s", "emulator-5554", "forward", "--remove", "tcp:37543"),
            fake.commands.single(),
        )
    }
}
