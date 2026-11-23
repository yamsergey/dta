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
    private val clock: () -> Long,
    private val sleep: (Long) -> Unit,
) {
    constructor(runAdb: (List<String>) -> AdbExec.Result) : this(runAdb, System::currentTimeMillis, Thread::sleep)

    /** Pre-flights the socket, then forwards tcp:0 and returns the allocated port. */
    fun forward(key: ConnectionKey): Int {
        val socketName = "dta_sidekick_${key.packageName}"
        fun listening(): Boolean {
            val scan = runAdb(listOf("-s", key.serial, "shell", "cat", "/proc/net/unix"))
            if (scan.exitCode != 0 && "not found" in scan.stderr) {
                throw DeviceGoneException("device ${key.serial} disconnected")
            }
            return "@$socketName" in scan.stdout
        }
        fun pid(): String = runAdb(listOf("-s", key.serial, "shell", "pidof", key.packageName)).stdout.trim()
        if (!listening()) {
            var pid = pid()
            // D-050: a process that has just started does not listen yet; until it is [YOUNG_MS] old, wait for it.
            val age = pid.takeIf { it.isNotEmpty() }?.let { ageMs(key.serial, it) }
            var bound = false
            if (age != null && age < YOUNG_MS) {
                val until = clock() + (YOUNG_MS - age)
                while (!bound && pid.isNotEmpty() && clock() < until) {
                    sleep(GRACE_POLL_MS)
                    bound = listening()
                    if (!bound) pid = pid()
                }
            }
            if (!bound) {
                if (pid.isNotEmpty()) {
                    throw AppWithoutDtaException("${key.packageName} is running on ${key.serial} without DTA — build it with DTA")
                }
                throw AppNotRunningException(
                    "${key.packageName} is not running on ${key.serial} — launch it first",
                )
            }
        }
        val result = runAdb(listOf("-s", key.serial, "forward", "tcp:0", "localabstract:$socketName"))
        return result.stdout.trim().toIntOrNull()
            ?: throw AdbCommandException(
                "adb forward returned no port for $socketName: " +
                    (result.stdout + result.stderr).trim().ifEmpty { "empty output" },
            )
    }

    companion object {
        /** D-050: a process younger than this may not listen yet. */
        const val YOUNG_MS = 8_000L
        private const val GRACE_POLL_MS = 250L
    }

    /** How long ago process [pid] started (`/proc/uptime` against its start in clock ticks, 100 a second), or null. */
    private fun ageMs(serial: String, pid: String): Long? {
        val pidOnly = pid.lineSequence().first().trim().substringBefore(' ')
        val out = runAdb(listOf("-s", serial, "shell", "cat", "/proc/uptime", "/proc/$pidOnly/stat"))
        if (out.exitCode != 0) return null
        val lines = out.stdout.lines()
        val uptimeMs = lines.firstOrNull()?.trim()?.substringBefore(' ')?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: return null
        // Field 22 (starttime); fields after the command's closing ')' start at field 3.
        val startTicks = lines.getOrNull(1)?.substringAfterLast(')')?.trim()?.split(Regex("\\s+"))?.getOrNull(19)?.toLongOrNull() ?: return null
        return (uptimeMs - startTicks * 10).coerceAtLeast(0)
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
