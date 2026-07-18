package io.yamsergey.dta.daemon.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class AdbExecTest {

    @Test
    fun `resolves adb from ANDROID_HOME first`() {
        val path = AdbExec.resolveAdb(
            env = mapOf("ANDROID_HOME" to "/sdk", "ANDROID_SDK_ROOT" to "/other", "PATH" to "/usr/bin"),
            fileExists = { it == "/sdk/platform-tools/adb" },
        )
        assertEquals("/sdk/platform-tools/adb", path)
    }

    @Test
    fun `falls back to ANDROID_SDK_ROOT`() {
        val path = AdbExec.resolveAdb(
            env = mapOf("ANDROID_SDK_ROOT" to "/other", "PATH" to "/usr/bin"),
            fileExists = { it == "/other/platform-tools/adb" },
        )
        assertEquals("/other/platform-tools/adb", path)
    }

    @Test
    fun `falls back to PATH probe and returns the absolute path, never bare adb`() {
        val path = AdbExec.resolveAdb(
            env = mapOf("PATH" to "/usr/local/bin:/usr/bin"),
            fileExists = { it == "/usr/bin/adb" },
        )
        assertEquals("/usr/bin/adb", path)
    }

    @Test
    fun `unresolvable adb fails with an actionable message`() {
        val e = assertFailsWith<IllegalStateException> {
            AdbExec.resolveAdb(env = emptyMap(), fileExists = { false })
        }
        assertTrue("ANDROID_HOME" in e.message!!, "must tell the user how to fix it: ${e.message}")
    }

    @Test
    fun `runs a process and captures stdout and exit code`() {
        val result = AdbExec(binaryPath = "/bin/echo").run("hello", "world")
        assertEquals(0, result.exitCode)
        assertEquals("hello world", result.stdout.trim())
    }

    @Test
    fun `nonzero exit and stderr are captured, not thrown`() {
        val result = AdbExec(binaryPath = "/bin/sh").run("-c", "echo oops >&2; exit 3")
        assertEquals(3, result.exitCode)
        assertEquals("oops", result.stderr.trim())
    }

    @Test
    fun `command exceeding its timeout fails fast with a timeout error`() {
        val e = assertFailsWith<AdbTimeoutException> {
            AdbExec(binaryPath = "/bin/sleep").run("5", timeout = 200.milliseconds)
        }
        assertTrue("sleep" in e.message!!)
    }
}
