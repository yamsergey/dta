package io.yamsergey.dta.cli

import io.yamsergey.dta.cli.format.Formatters
import io.yamsergey.dta.cli.format.Style
import io.yamsergey.dta.testing.TestDaemon
import io.yamsergey.dta.protocol.ops.Catalog
import io.yamsergey.dta.protocol.ops.DeviceInfo
import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliTest {
    private val daemon = TestDaemon()
    private val outBytes = ByteArrayOutputStream()
    private val errBytes = ByteArrayOutputStream()
    private val out = PrintStream(outBytes, true)
    private val err = PrintStream(errBytes, true)
    private val stdout get() = outBytes.toString()
    private val stderr get() = errBytes.toString()

    private var existing: (suspend () -> io.yamsergey.dta.client.DaemonClient?)? = null

    @AfterTest
    fun close() = daemon.close()

    // --- daemon status / stop (T045)

    @Test
    fun `stop with no daemon says so and starts nothing`() {
        existing = { null }
        assertEquals(0, dta("daemon", "stop"))
        assertEquals("No DTA daemon is running.", stdout.trim())
    }

    @Test
    fun `stop reaches the running daemon whatever its version`() {
        existing = { daemon.connect().client }
        assertEquals(0, dta("daemon", "stop"))
        assertTrue("is stopping" in stdout, stdout)
    }

    private fun dta(vararg args: String, cwd: File = File("/work")): Int {
        val runner = Runner(connect = { daemon.connect() }, existing = { existing?.invoke() }, out = out, err = err, formatters = { Formatters(Style.PLAIN) })
        return Cli(runner = runner::run, mcp = { 0 }, out = out, err = err, cwd = cwd).run(arrayOf(*args))
    }

    // --- Command tree (T025)

    @Test
    fun `one leaf per catalog operation, plus mcp`() {
        val cli = Cli(runner = { _, _, _ -> 0 }, mcp = { 0 })
        assertEquals((Catalog.all.map { it.cliPath } + listOf(listOf("mcp"))).toSet(), cli.leafPaths().toSet())
        assertTrue(listOf("--url", "--status", "--kinds", "--since-ms", "--redact") .all { it in cli.parameterNames(listOf("network", "list")) })
        assertTrue("ID" in cli.parameterNames(listOf("network", "entry")))
    }

    // --- JSON output (T028)

    @Test
    fun `json prints exactly the selected document`() {
        assertEquals(0, dta("--json", "network", "list"))
        val selected = DtaJson.decodeFromString(Selected.serializer(), stdout.trim())
        assertEquals("emulator-5554", selected.selection!!.device)
        assertEquals(3, selected.result.jsonObject["total"]!!.jsonPrimitive.content.toInt())
        assertEquals("", stderr, "no selection chatter in json mode")
    }

    @Test
    fun `human mode states the automatic selection on stderr`() {
        assertEquals(0, dta("network", "list"))
        assertTrue("device emulator-5554 (only device) · app com.example.shop (foreground)" in stderr, stderr)
        assertTrue("POST" in stdout && "503" in stdout && "* scripted" in stdout && ".. pending" in stdout, stdout)
        assertTrue("3 of 3 shown" in stdout, stdout)
    }

    @Test
    fun `named device and app are not restated`() {
        assertEquals(0, dta("-d", "emulator-5554", "-a", "com.example.shop", "network", "clear"))
        assertEquals("", stderr)
        assertEquals("Cleared 3 entries.", stdout.trim())
    }

    @Test
    fun `paths are made absolute against the working directory`() {
        val dir = kotlin.io.path.createTempDirectory("dta-cwd").toFile()
        File(dir, "s.js").writeText("// script")
        assertEquals(0, dta("network", "script", "set", "s.js", cwd = dir))
        assertTrue("Network script abc123 active" in stdout, stdout)
    }

    // --- Exit codes (T027)

    @Test
    fun `usage errors exit 2`() {
        assertEquals(ExitCodes.USAGE, dta("network", "list", "--kinds", "carrier-pigeon"))
        assertEquals(ExitCodes.USAGE, dta("network", "entry"))
        assertEquals(ExitCodes.USAGE, dta("network", "list", "--status", "teapot"))
    }

    @Test
    fun `not found and not available exit 3 with the reason`() {
        assertEquals(ExitCodes.NOT_AVAILABLE, dta("network", "mapping", "remove", "nope"))
        assertTrue("No mapping 'nope'" in stderr, stderr)
        daemon.ops.deviceList = listOf(DeviceInfo("a", "online"), DeviceInfo("b", "online"))
        assertEquals(ExitCodes.NOT_AVAILABLE, dta("network", "list"))
        assertTrue("Several devices" in stderr && "a, b" in stderr, stderr)
    }

    // --- Formatters (T026)

    @Test
    fun `layout and status read like the design system`() {
        assertEquals(0, dta("layout"))
        assertTrue("view FrameLayout android:id/content #1" in stdout, stdout)
        assertTrue("    view Button #2 \"Pay\"" in stdout, stdout)
        outBytes.reset()
        assertEquals(0, dta("daemon", "status"))
        assertTrue("DTA daemon 0.1.0 (build 2026-10-28T10:00:00Z)  pid 42  up 1m 5s" in stdout, stdout)
        assertTrue("clients 1" in stdout && "adb /sdk/adb" in stdout, stdout)
    }

    @Test
    fun `colour uses the 16-colour mapping only when asked`() {
        val plain = Formatters(Style.PLAIN).format("network.clear", JsonObject(mapOf("clearedEntries" to kotlinx.serialization.json.JsonPrimitive(1))))
        assertFalse('\u001b' in plain)
        val coloured = Style(color = true, unicode = true).mark(Style.Statement.SCRIPTED, "scripted")
        assertEquals("\u001b[95m✎ scripted\u001b[0m", coloured)
    }

    @Test
    fun `version`() {
        assertEquals(0, dta("--version"))
        assertTrue(stdout.startsWith("dta "), stdout)
    }
}
