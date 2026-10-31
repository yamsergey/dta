package io.yamsergey.dta.run

/**
 * Reads Gradle's plain console output (research R3, R8): which phase the build is in, what
 * went wrong when it failed, and why an install was refused. Pure, so it is tested on samples.
 */
object BuildOutput {
    private val TASK = Regex("""^> Task (\S+)""")
    private val KOTLIN_ERROR = Regex("""^e: (?:file://)?(\S+?:\d+:\d+) (.*)$""")
    private val JAVAC_ERROR = Regex("""^(\S+\.java:\d+): error: (.*)$""")
    private val INSTALL_CODE = Regex("""\b(INSTALL_(?:PARSE_)?FAILED_[A-Z_]+)\b""")

    /** The phase a task line starts, or null for any other line. */
    fun phaseOf(line: String): Phase? {
        val path = TASK.find(line)?.groupValues?.get(1) ?: return null
        val name = path.substringAfterLast(':')
        return when {
            name == "dtaRun" -> null
            name.startsWith("install") || name.startsWith("uninstall") -> Phase.INSTALL
            name.startsWith("package") || name.startsWith("assemble") || name.startsWith("sign") || name.startsWith("zipalign") -> Phase.PACKAGE
            else -> Phase.COMPILE
        }
    }

    /** Durations per phase; a phase starts at its first task and only later phases follow. */
    class PhaseTracker(private val clock: () -> Long = System::currentTimeMillis) {
        private val durations = linkedMapOf<Phase, Long>()
        private var current = Phase.CONFIGURE
        private var since = clock()

        /** The phase the line moved to, or null when it stayed. */
        fun line(line: String): Phase? {
            val phase = phaseOf(line) ?: return null
            if (phase.ordinal <= current.ordinal) return null
            close(clock())
            current = phase
            return phase
        }

        fun finish(): Map<Phase, Long> {
            close(clock())
            return durations.toMap()
        }

        private fun close(now: Long) {
            durations[current] = (durations[current] ?: 0) + (now - since)
            since = now
        }
    }

    data class BuildFailure(val summary: String, val location: String?, val message: String?)

    /** "What went wrong" (up to "Try"), and the first compiler error with its file:line. */
    fun failure(lines: List<String>): BuildFailure {
        val start = lines.indexOfFirst { it.trim() == "* What went wrong:" }
        val summary = if (start < 0) {
            lines.lastOrNull { it.isNotBlank() }.orEmpty()
        } else {
            lines.drop(start + 1).takeWhile { !it.trim().startsWith("* ") }.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        }
        val error = lines.firstNotNullOfOrNull { line ->
            KOTLIN_ERROR.find(line)?.let { it.groupValues[1] to it.groupValues[2] }
                ?: JAVAC_ERROR.find(line)?.let { it.groupValues[1] to it.groupValues[2] }
        }
        return BuildFailure(summary, error?.first, error?.second)
    }

    data class InstallFailure(val code: String, val cause: String, val next: String)

    /** The package manager's refusal, when the output carries one. */
    fun installFailure(lines: List<String>): InstallFailure? {
        val code = lines.firstNotNullOfOrNull { INSTALL_CODE.find(it)?.groupValues?.get(1) } ?: return null
        val (cause, next) = when (code) {
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE" ->
                "An app with the same package and a different signature is installed on the device." to
                    "Run again with --reinstall to uninstall it first (this deletes its data), or uninstall it yourself."
            "INSTALL_FAILED_VERSION_DOWNGRADE" ->
                "A newer version of the app is installed on the device." to
                    "Run again with --reinstall to uninstall it first (this deletes its data)."
            "INSTALL_FAILED_OLDER_SDK" ->
                "The app's minSdk is higher than the device's Android version." to "Use a device or emulator with a newer Android version."
            "INSTALL_FAILED_INSUFFICIENT_STORAGE" ->
                "The device does not have enough storage for the app." to "Free some space on the device and run again."
            "INSTALL_FAILED_USER_RESTRICTED" ->
                "The device refused the install from the computer." to "Allow installing apps over USB in the device's developer options."
            "INSTALL_FAILED_NO_MATCHING_ABIS" ->
                "The app has native code for none of the device's CPU architectures." to "Use a device or emulator whose architecture the app supports."
            else -> "The package manager refused the install: $code." to "See the build log for the full message."
        }
        return InstallFailure(code, cause, next)
    }

    /** The Android Gradle Plugin found no device to install on. */
    fun noDevice(lines: List<String>): Boolean = lines.any { "No connected devices" in it }
}
