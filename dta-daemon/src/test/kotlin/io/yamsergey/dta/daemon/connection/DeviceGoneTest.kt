package io.yamsergey.dta.daemon.connection

import io.yamsergey.dta.daemon.adb.AdbExec
import io.yamsergey.dta.daemon.adb.DeviceGoneException
import io.yamsergey.dta.daemon.adb.PortForwards
import io.yamsergey.dta.daemon.discovery.ConnectionKey
import io.yamsergey.dta.daemon.discovery.SidekickDiscovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeviceGoneTest {

    @Test
    fun `forwarding to a vanished device says which device disconnected`() {
        val forwards = PortForwards { args ->
            AdbExec.Result(1, "", "adb: device 'emulator-5554' not found")
        }
        val e = assertFailsWith<DeviceGoneException> {
            forwards.forward(ConnectionKey("emulator-5554", "com.example.app"))
        }
        assertTrue("emulator-5554" in e.message!!)
        assertTrue("disconnected" in e.message!!)
    }

    @Test
    fun `discovery skips a device that vanishes mid-scan instead of failing the whole listing`() {
        val discovery = SidekickDiscovery { args ->
            when {
                args.contains("devices") -> AdbExec.Result(
                    0,
                    "List of devices attached\nemulator-5554 device\nemulator-5556 device\n",
                    "",
                )
                args.contains("emulator-5554") ->
                    AdbExec.Result(1, "", "adb: device 'emulator-5554' not found")
                else -> AdbExec.Result(
                    0,
                    "0000: 00000002 00000000 00010000 0001 01 1 @dta_sidekick_com.ok.app\n",
                    "",
                )
            }
        }
        val instances = discovery.listInstances()
        assertEquals(1, instances.size)
        assertEquals("com.ok.app", instances.single().packageName)
        assertEquals("emulator-5556", instances.single().serial)
    }
}
