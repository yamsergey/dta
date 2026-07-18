package io.yamsergey.dta.daemon.layout

import io.yamsergey.dta.daemon.adb.AdbExec

/**
 * Determines the foreground package from `dumpsys window`. Pure parser +
 * thin AdbExec adapter (constitution IV).
 */
class ForegroundDetector(private val runAdb: (List<String>) -> AdbExec.Result) {

    fun foregroundPackage(serial: String): String? =
        parseForegroundPackage(runAdb(listOf("-s", serial, "shell", "dumpsys", "window")).stdout)

    companion object {
        private val FOCUS = Regex("""mCurrentFocus=Window\{[^}]*\s([A-Za-z0-9._]+)/[A-Za-z0-9._]+}""")
        private val FOCUSED_APP = Regex("""mFocusedApp=.*\s([A-Za-z0-9._]+)/[A-Za-z0-9._.]+""")

        fun parseForegroundPackage(dumpsys: String): String? {
            FOCUS.find(dumpsys)?.let { return it.groupValues[1] }
            FOCUSED_APP.find(dumpsys)?.let { return it.groupValues[1] }
            return null
        }
    }
}
