package io.yamsergey.dta.daemon.discovery

import io.yamsergey.dta.daemon.adb.AdbExec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ADB_DEVICES_OUTPUT = """List of devices attached
emulator-5554          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:1
R58M123ABC             offline transport_id:2
emulator-5556          unauthorized transport_id:3

"""

private const val PROC_NET_UNIX = """Num       RefCount Protocol Flags    Type St Inode Path
0000000000000000: 00000002 00000000 00010000 0001 01 52412 @dta_sidekick_io.yamsergey.dta.fixture.channel
0000000000000000: 00000002 00000000 00010000 0001 01 52413 @dta_sidekick_com.example.other
0000000000000000: 00000002 00000000 00010000 0001 01 12345 @chrome_devtools_remote_1234
0000000000000000: 00000002 00000000 00010000 0001 01 12346 @dta_sidekick_
0000000000000000: 00000002 00000000 00010000 0001 01 12347 /dev/socket/not_abstract
garbage line that should be ignored
"""

class DiscoveryTest {

    private fun discovery(
        devicesOutput: String = ADB_DEVICES_OUTPUT,
        unixOutput: String = PROC_NET_UNIX,
        commands: MutableList<List<String>> = mutableListOf(),
    ) = SidekickDiscovery { args ->
        commands += args
        when {
            args.contains("devices") -> AdbExec.Result(0, devicesOutput, "")
            args.contains("/proc/net/unix") -> AdbExec.Result(0, unixOutput, "")
            else -> AdbExec.Result(1, "", "unexpected: $args")
        }
    }

    @Test
    fun `lists only online devices`() {
        val devices = discovery().listDevices()
        assertEquals(listOf("emulator-5554"), devices.filter { it.state == Device.State.ONLINE }.map { it.serial })
        assertEquals(3, devices.size)
    }

    @Test
    fun `finds sidekick instances on online devices and ignores foreign or malformed sockets`() {
        val instances = discovery().listInstances()
        assertEquals(
            listOf(
                SidekickInstance("emulator-5554", "io.yamsergey.dta.fixture.channel"),
                SidekickInstance("emulator-5554", "com.example.other"),
            ),
            instances,
        )
    }

    @Test
    fun `offline and unauthorized devices are never scanned`() {
        val commands = mutableListOf<List<String>>()
        discovery(commands = commands).listInstances()
        val scans = commands.filter { it.contains("/proc/net/unix") }
        assertTrue(scans.all { it.contains("emulator-5554") }, "scanned: $scans")
        assertEquals(1, scans.size)
    }

    @Test
    fun `no devices means no instances, not an error`() {
        assertEquals(emptyList(), discovery(devicesOutput = "List of devices attached\n\n").listInstances())
    }

    @Test
    fun `connection key is one canonical type`() {
        val key = ConnectionKey("emulator-5554", "com.example.app")
        assertEquals("emulator-5554", key.serial)
        assertEquals("com.example.app", key.packageName)
        assertEquals(key, ConnectionKey("emulator-5554", "com.example.app"))
    }
}
