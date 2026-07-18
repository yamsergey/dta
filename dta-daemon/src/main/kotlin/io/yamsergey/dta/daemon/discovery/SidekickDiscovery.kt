package io.yamsergey.dta.daemon.discovery

import io.yamsergey.dta.daemon.adb.AdbExec

data class Device(val serial: String, val state: State) {
    enum class State { ONLINE, OFFLINE, UNAUTHORIZED }
}

data class SidekickInstance(val serial: String, val packageName: String)

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
    private val socketPattern = Regex("""@dta_sidekick_([A-Za-z0-9._]+)""")

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

    fun listInstances(): List<SidekickInstance> =
        listDevices()
            .filter { it.state == Device.State.ONLINE }
            .flatMap { device ->
                val scan = runAdb(listOf("-s", device.serial, "shell", "cat", "/proc/net/unix"))
                socketPattern.findAll(scan.stdout)
                    .map { SidekickInstance(device.serial, it.groupValues[1]) }
                    .distinct()
            }
}
