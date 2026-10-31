package io.yamsergey.dta.run

import io.yamsergey.dta.client.DaemonLauncher
import io.yamsergey.dta.client.DtaHome
import io.yamsergey.dta.client.VersionInfo
import io.yamsergey.dta.protocol.ops.ClientKind

/**
 * A run against the real Gradle, device and daemon, as a client of [kind] (CLI or MCP).
 * The daemon connection lasts for the run; [disconnect] ends it.
 */
class RunEnvironment(
    kind: ClientKind,
    home: DtaHome = DtaHome.resolve(),
    version: VersionInfo = VersionInfo.current(),
) {
    val sidekickVersion: String = version.version
    private val daemon = ClientDaemonView(connect = { DaemonLauncher(home = home, version = version).ensure(kind) })
    private val device = AdbDeviceActions(adb = {
        daemon.adbPath() ?: throw DaemonUnavailableException("The DTA daemon has no adb: set ANDROID_HOME or DTA_ADB and restart it with `dta daemon stop`.")
    })
    val session = RunSession(ProcessGradleRunner(), device, daemon, RunFiles(home.root), sidekickVersion)

    suspend fun disconnect() = daemon.disconnect()
}
