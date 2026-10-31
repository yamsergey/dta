package io.yamsergey.dta.run

import java.io.File
import kotlin.time.Duration

/** Runs the project's Gradle wrapper (research R1, R8); the real one is [ProcessGradleRunner]. */
fun interface GradleRunner {
    /** Runs to the end, or until the calling coroutine is cancelled (then Gradle is stopped). */
    suspend fun run(project: File, args: List<String>, env: Map<String, String>, log: File, onLine: (String) -> Unit): Int
}

/** What `am start` said. */
sealed interface LaunchOutcome {
    data class Started(val restarted: Boolean) : LaunchOutcome
    data class Failed(val error: String) : LaunchOutcome
}

/** Device commands the run needs beyond Gradle (research R4); the real one is [AdbDeviceActions]. */
interface DeviceActions {
    /** `package/.Activity` of the launcher activity, or null when the app has none. */
    suspend fun resolveLauncher(serial: String, applicationId: String): String?

    suspend fun start(serial: String, component: String): LaunchOutcome

    suspend fun pidOf(serial: String, applicationId: String): Long?
}

/** The device the daemon's rule chose, or why none. */
sealed interface DeviceChoice {
    data class Chosen(val serial: String, val model: String?) : DeviceChoice
    data object NoDevice : DeviceChoice
    data class Several(val serials: List<String>) : DeviceChoice
    data class NotFound(val serial: String, val online: List<String>) : DeviceChoice
}

sealed interface ConnectionOutcome {
    data object Connected : ConnectionOutcome
    data class Refused(val reason: String) : ConnectionOutcome
    data object TimedOut : ConnectionOutcome
}

/** Follows one app's connection through the daemon; opened before launch so no event is missed. */
interface ConnectionWatch : AutoCloseable {
    /** The next `connected` or `refused` for the app, or [ConnectionOutcome.TimedOut]. */
    suspend fun next(timeout: Duration): ConnectionOutcome
}

/** The daemon as the run sees it (constitution II: liveness only from the daemon). */
interface DaemonView {
    suspend fun chooseDevice(serial: String?): DeviceChoice

    suspend fun watch(serial: String, applicationId: String): ConnectionWatch

    /** Whether the daemon sees the app's sidekick on the device right now. */
    suspend fun sidekickPresent(serial: String, applicationId: String): Boolean
}

/** The daemon could not be reached or refused this client (version), with the statement. */
class DaemonUnavailableException(message: String, val versionConflict: Boolean = false) : Exception(message)
