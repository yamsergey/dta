package io.yamsergey.dta.daemon.adb

import io.yamsergey.dta.daemon.discovery.ConnectionKey

class AppNotRunningException(message: String) : Exception(message)
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
        if ("@$socketName" !in scan.stdout) {
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
}
