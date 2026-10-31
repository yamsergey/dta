package io.yamsergey.dta.run

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The device commands of a run (research R4) through the daemon's adb ([adb] resolves it):
 * the launcher activity, `am start -W -S`, and the app's pid.
 */
class AdbDeviceActions(private val adb: suspend () -> String) : DeviceActions {

    override suspend fun resolveLauncher(serial: String, applicationId: String): String? =
        parseLauncher(shell(serial, "cmd", "package", "resolve-activity", "--brief", "-c", "android.intent.category.LAUNCHER", applicationId).output)

    override suspend fun start(serial: String, component: String): LaunchOutcome {
        val result = shell(serial, "am", "start", "-W", "-S", "--user", "current", "-n", component)
        return parseStart(result.output, result.exit)
    }

    override suspend fun pidOf(serial: String, applicationId: String): Long? = parsePid(shell(serial, "pidof", applicationId).output)

    private data class Result(val exit: Int, val output: String)

    private suspend fun shell(serial: String, vararg command: String): Result = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf(adb(), "-s", serial, "shell") + command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = thread(isDaemon = true) { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@withContext Result(-1, "adb did not answer within $TIMEOUT_SECONDS s: ${command.joinToString(" ")}")
        }
        reader.join(1_000)
        Result(process.exitValue(), output.toString())
    }

    companion object {
        private const val TIMEOUT_SECONDS = 60L

        /** `resolve-activity --brief` prints the match last, as `package/.Activity`. */
        fun parseLauncher(output: String): String? =
            output.lines().map { it.trim() }.lastOrNull { '/' in it && ' ' !in it && !it.startsWith("No activity") }

        /** `am start -W` reports `Error: …` (or `Error type N` alone) when the activity did not start. */
        fun parseStart(output: String, exit: Int): LaunchOutcome {
            val lines = output.lines().map { it.trim() }
            lines.firstOrNull { it.startsWith("Error:") }?.let { return LaunchOutcome.Failed(it.removePrefix("Error:").trim()) }
            lines.firstOrNull { it.startsWith("Error type") }?.let { return LaunchOutcome.Failed(it) }
            if (exit != 0) return LaunchOutcome.Failed(lines.lastOrNull { it.isNotEmpty() } ?: "am start exited with $exit")
            return LaunchOutcome.Started(restarted = false)
        }

        /** `pidof` prints nothing when the app does not run; several pids (several processes) give the first. */
        fun parsePid(output: String): Long? = output.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
    }
}
