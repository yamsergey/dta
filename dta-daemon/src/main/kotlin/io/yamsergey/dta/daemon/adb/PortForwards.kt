package io.yamsergey.dta.daemon.adb

import io.yamsergey.dta.daemon.discovery.ConnectionKey

open class AppNotRunningException(message: String) : Exception(message)

/** The app runs, but no DTA sidekick listens in it: it was built without DTA. */
class AppWithoutDtaException(message: String) : AppNotRunningException(message)
class DeviceGoneException(message: String) : Exception(message)
class AdbCommandException(message: String) : Exception(message)

/**
 * ADB port-forward operations for sidekick sockets. Always pre-flights the
 * socket in /proc/net/unix — `adb forward` reports success even when nothing
 * listens (prototype lesson, discovery contract §3).
 */
class PortForwards(
    private val runAdb: (List<String>) -> AdbExec.Result,
) {
    /** Pre-flights the socket, then forwards tcp:0 and returns the allocated port. */
    fun forward(key: ConnectionKey): Int {
        val socketName = "dta_sidekick_${key.packageName}"
        val scan = runAdb(listOf("-s", key.serial, "shell", "cat", "/proc/net/unix"))
        if (scan.exitCode != 0 && "not found" in scan.stderr) {
            throw DeviceGoneException("device ${key.serial} disconnected")
        }
        if ("@$socketName" !in scan.stdout) {
            val pid = runAdb(listOf("-s", key.serial, "shell", "pidof", key.packageName)).stdout.trim()
            if (pid.isNotEmpty()) {
                throw AppWithoutDtaException("${key.packageName} is running on ${key.serial} without DTA — build it with DTA")
            }
            throw AppNotRunningException(
                "${key.packageName} is not running on ${key.serial} — launch it first",
            )
        }
        val result = runAdb(listOf("-s", key.serial, "forward", "tcp:0", "localabstract:$socketName"))
        return result.stdout.trim().toIntOrNull()
            ?: throw AdbCommandException(
                "adb forward returned no port for $socketName: " +
                    (result.stdout + result.stderr).trim().ifEmpty { "empty output" },
            )
    }

    fun remove(serial: String, port: Int) {
        runAdb(listOf("-s", serial, "forward", "--remove", "tcp:$port"))
    }

    /** Removes forwards leaked by a previous run — ONLY ours (`dta_sidekick_*`);
     * other tools' forwards are never touched (FR-011, discovery contract §3). */
    fun startupSweep(): Int {
        val listing = runAdb(listOf("forward", "--list"))
        var removed = 0
        listing.stdout.lineSequence().forEach { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 3) return@forEach
            if (!fields[2].startsWith("localabstract:dta_sidekick_")) return@forEach
            val port = fields[1].removePrefix("tcp:").toIntOrNull() ?: return@forEach
            remove(fields[0], port)
            removed++
        }
        return removed
    }
}
