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

        fun run(args: List<String>): AdbExec.Result {
            commands += args
            return when {
                args.contains("/proc/net/unix") -> AdbExec.Result(0, procNetUnix, "")
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
