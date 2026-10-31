package io.yamsergey.dta.daemon.device

import io.yamsergey.dta.daemon.adb.AdbExec
import io.yamsergey.dta.daemon.store.DeviceIdentity
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Which device a serial is (feature 011, research R4): adb reuses `emulator-5554` for different
 * emulators, so the host archive keys devices by the build fingerprint plus the emulator's AVD
 * name or the phone's hardware serial. Read once per serial with one `getprop` (FR-025); when
 * that fails the key is the serial itself, prefixed so it is never mistaken for a stable one.
 */
class DeviceIdentities(private val getprop: (String) -> AdbExec.Result) {
    private val known = ConcurrentHashMap<String, DeviceIdentity>()

    fun of(serial: String): DeviceIdentity = known.computeIfAbsent(serial) { read(it) }

    private fun read(serial: String): DeviceIdentity {
        val result = runCatching { getprop(serial) }.getOrNull()
        if (result == null || result.exitCode != 0) return DeviceIdentity(key = "serial:$serial", serial = serial)
        val props = PROP.findAll(result.stdout).associate { it.groupValues[1] to it.groupValues[2] }
        val fingerprint = props["ro.build.fingerprint"] ?: return DeviceIdentity(key = "serial:$serial", serial = serial)
        val which = props["ro.boot.qemu.avd_name"] ?: props["ro.kernel.qemu.avd_name"] ?: props["ro.serialno"] ?: serial
        val key = MessageDigest.getInstance("SHA-256").digest("$fingerprint|$which".toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        return DeviceIdentity(key = key, serial = serial, model = props["ro.product.model"], release = props["ro.build.version.release"])
    }

    private companion object {
        val PROP = Regex("""\[([^\]]+)]: \[([^\]]*)]""")
    }
}
