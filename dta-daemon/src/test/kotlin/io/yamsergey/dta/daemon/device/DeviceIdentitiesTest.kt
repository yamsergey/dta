package io.yamsergey.dta.daemon.device

import io.yamsergey.dta.daemon.adb.AdbExec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** 011 T016 (research R4): a device is the same device over time, whatever its adb serial. */
class DeviceIdentitiesTest {
    private fun props(fingerprint: String, avd: String? = null, serialno: String? = null, model: String = "Pixel 8") = buildString {
        appendLine("[ro.build.fingerprint]: [$fingerprint]")
        avd?.let { appendLine("[ro.boot.qemu.avd_name]: [$it]") }
        serialno?.let { appendLine("[ro.serialno]: [$it]") }
        appendLine("[ro.product.model]: [$model]")
        appendLine("[ro.build.version.release]: [16]")
    }

    @Test
    fun `two emulators on the same serial are two devices, one emulator on two serials is one`() {
        var output = props("google/sdk/emu:16/X/1:user/release-keys", avd = "Pixel_8a")
        val ids = DeviceIdentities { AdbExec.Result(0, output, "") }
        val a = ids.of("emulator-5554")
        output = props("google/sdk/emu:16/X/1:user/release-keys", avd = "API_35")
        val b = DeviceIdentities { AdbExec.Result(0, output, "") }.of("emulator-5554")
        assertNotEquals(a.key, b.key)
        output = props("google/sdk/emu:16/X/1:user/release-keys", avd = "Pixel_8a")
        assertEquals(a.key, DeviceIdentities { AdbExec.Result(0, output, "") }.of("emulator-5556").key)
        assertEquals("Pixel 8", a.model)
        assertEquals("16", a.release)
    }

    @Test
    fun `phones are told apart by their hardware serial`() {
        val one = DeviceIdentities { AdbExec.Result(0, props("samsung/q7:16/X/1:user/release-keys", serialno = "R5CX1"), "") }.of("R5CX1")
        val two = DeviceIdentities { AdbExec.Result(0, props("samsung/q7:16/X/1:user/release-keys", serialno = "R5CX2"), "") }.of("R5CX2")
        assertNotEquals(one.key, two.key)
    }

    @Test
    fun `adb is asked once per device, and a failure falls back to the serial, stated by the key`() {
        var calls = 0
        val ids = DeviceIdentities { calls++; AdbExec.Result(1, "", "device offline") }
        val id = ids.of("emulator-5554")
        ids.of("emulator-5554")
        assertEquals("serial:emulator-5554", id.key)
        assertEquals(1, calls)
    }
}
