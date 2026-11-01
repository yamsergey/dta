package io.yamsergey.dta.daemon.adb

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class AdbTimeoutException(message: String) : Exception(message)

/**
 * The ONE way the daemon executes adb (constitution VI). Resolves the binary
 * explicitly — never a bare "adb" (silently broken inside IDE processes).
 */
class AdbExec(
    val binaryPath: String = resolveAdb(),
    private val defaultTimeout: Duration = 30.seconds,
) {
    data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    fun run(vararg args: String, timeout: Duration = defaultTimeout): Result {
        val command = listOf(binaryPath) + args
        val process = ProcessBuilder(command).start()

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outReader = thread(isDaemon = true) { process.inputStream.reader().copyTo(stdout) }
        val errReader = thread(isDaemon = true) { process.errorStream.reader().copyTo(stderr) }

        val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw AdbTimeoutException("Timed out after $timeout: ${command.joinToString(" ")}")
        }
        outReader.join(1_000)
        errReader.join(1_000)
        return Result(process.exitValue(), stdout.toString(), stderr.toString())
    }

    class BytesResult(val exitCode: Int, val stdout: ByteArray, val stderr: String)

    /** [run] for commands whose stdout is binary (`exec-out screencap -p`, feature 014). */
    fun runBytes(vararg args: String, timeout: Duration = defaultTimeout): BytesResult {
        val command = listOf(binaryPath) + args
        val process = ProcessBuilder(command).start()

        val stdout = java.io.ByteArrayOutputStream()
        val stderr = StringBuilder()
        val outReader = thread(isDaemon = true) { runCatching { process.inputStream.copyTo(stdout) } }
        val errReader = thread(isDaemon = true) { process.errorStream.reader().copyTo(stderr) }

        val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw AdbTimeoutException("Timed out after $timeout: ${command.joinToString(" ")}")
        }
        outReader.join(5_000)
        errReader.join(1_000)
        return BytesResult(process.exitValue(), synchronized(stdout) { stdout.toByteArray() }, stderr.toString())
    }

    private fun java.io.Reader.copyTo(target: StringBuilder) {
        try {
            forEachLine { target.appendLine(it) }
        } catch (_: Exception) {
            // Stream torn down with the process; partial output is fine.
        }
    }

    companion object {
        fun resolveAdb(
            env: Map<String, String> = System.getenv(),
            fileExists: (String) -> Boolean = { File(it).canExecute() },
        ): String {
            env["DTA_ADB"]?.takeIf { it.isNotBlank() }?.let { explicit ->
                if (fileExists(explicit)) return explicit
                error("DTA_ADB=$explicit is not an executable adb — fix or unset DTA_ADB")
            }
            for (variable in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
                val home = env[variable] ?: continue
                val candidate = "$home/platform-tools/adb"
                if (fileExists(candidate)) return candidate
            }
            env["PATH"]?.split(File.pathSeparator)?.forEach { dir ->
                val candidate = "$dir/adb"
                if (fileExists(candidate)) return candidate
            }
            error(
                "adb not found (looked in DTA_ADB, ANDROID_HOME, ANDROID_SDK_ROOT and PATH) — set ANDROID_HOME " +
                    "(or ANDROID_SDK_ROOT) to your Android SDK, or add platform-tools to PATH",
            )
        }
    }
}
