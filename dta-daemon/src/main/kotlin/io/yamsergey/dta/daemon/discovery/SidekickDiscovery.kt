package io.yamsergey.dta.daemon.discovery

import io.yamsergey.dta.daemon.adb.AdbExec

data class Device(val serial: String, val state: State) {
    enum class State { ONLINE, OFFLINE, UNAUTHORIZED }
}

data class SidekickInstance(
    val serial: String,
    val packageName: String,
    /** D-074: the socket's inode in /proc/net/unix; a new one is a new process of the app. */
    val socket: String? = null,
)

/** THE identity currency for (device, app) across all daemon components. */
data class ConnectionKey(val serial: String, val packageName: String)

/**
 * On-demand discovery of running sidekick instances: `adb devices -l` joined
 * with a /proc/net/unix scan for `@dta_sidekick_<package>` abstract sockets
 * (discovery contract §2).
 */
class SidekickDiscovery(
    private val runAdb: (List<String>) -> AdbExec.Result,
) {
    /** The socket's inode (the field before its path, D-074) and the package. */
    private val socketPattern = Regex("""(?:(\d+)\s+)?@dta_sidekick_([A-Za-z0-9._]+)""")

    fun listDevices(): List<Device> =
        runAdb(listOf("devices", "-l")).stdout
            .lineSequence()
            .drop(1) // "List of devices attached"
            .mapNotNull { line ->
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size < 2) return@mapNotNull null
                val state = when (fields[1]) {
                    "device" -> Device.State.ONLINE
                    "offline" -> Device.State.OFFLINE
                    "unauthorized" -> Device.State.UNAUTHORIZED
                    else -> return@mapNotNull null
                }
                Device(fields[0], state)
            }
            .toList()

    /**
     * D-074: the apps of [serial] with one adb shell call (every device when null). A device that is gone
     * has none; any other failed look throws, so a watcher keeps what it knew.
     */
    fun listInstances(serial: String?): List<SidekickInstance> {
        if (serial == null) return listInstances()
        val scan = runAdb(listOf("-s", serial, "shell", "cat", "/proc/net/unix"))
        if (scan.exitCode != 0) {
            val why = (scan.stderr + scan.stdout).lowercase()
            if ("not found" in why || "offline" in why || "unauthorized" in why) return emptyList()
            throw IllegalStateException("scan of $serial failed: ${scan.stderr.trim()}")
        }
        return instancesIn(serial, scan.stdout)
    }

    private fun instancesIn(serial: String, unix: String): List<SidekickInstance> =
        socketPattern.findAll(unix)
            .map { SidekickInstance(serial, it.groupValues[2], it.groupValues[1].ifEmpty { null }) }
            .distinctBy { it.packageName }
            .toList()

    fun listInstances(): List<SidekickInstance> =
        listDevices()
            .filter { it.state == Device.State.ONLINE }
            .flatMap { device ->
                instancesIn(device.serial, runAdb(listOf("-s", device.serial, "shell", "cat", "/proc/net/unix")).stdout)
            }
}
