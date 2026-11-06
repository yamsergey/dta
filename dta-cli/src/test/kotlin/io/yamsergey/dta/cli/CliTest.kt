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

    // --- saved filters (043)

    @Test
    fun `filter words starting with a dash are values, also -h`() {
        assertEquals(0, dta("network", "filters", "add", "-host:a.example.test", "status:2xx", "-url:/health"))
        assertEquals(listOf("-host:a.example.test", "status:2xx", "-url:/health"), daemon.ops.savedWords.toList())
        assertEquals(0, dta("network", "filters", "remove", "-url:/health"))
        assertEquals(listOf("-host:a.example.test", "status:2xx"), daemon.ops.savedWords.toList())
    }

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

    // --- Timeline and state (features 015/016)

    private val click = io.yamsergey.dta.protocol.timeline.TimelineEvent(
        "1:1", 1, 1, 1_793_000_000_001, "input", "input/click",
        input = io.yamsergey.dta.protocol.timeline.InputPayload(io.yamsergey.dta.protocol.timeline.InputAction.CLICK, text = "Delete", via = "view"),
    )
    private val toast = io.yamsergey.dta.protocol.timeline.TimelineEvent(
        "1:2", 2, 2, 1_793_000_000_002, "ui", "ui/toast",
        ui = io.yamsergey.dta.protocol.ui.UiEvent("1:2", 2, 2, 1_793_000_000_002, io.yamsergey.dta.protocol.ui.UiEventKind.TOAST, io.yamsergey.dta.protocol.ui.UiSource.TOAST, text = "Saved", durationMs = 2_000),
        causes = listOf(io.yamsergey.dta.protocol.timeline.CauseLink("1:1", io.yamsergey.dta.protocol.timeline.Basis.EXACT, "issued-during:click")),
        facets = listOf(io.yamsergey.dta.protocol.timeline.FacetChange("app.cart", "empty", "items")),
    )

    @Test
    fun `timeline events lists every source one line per event with causes and the next cursor`() {
        daemon.ops.timeline.ingest(io.yamsergey.dta.protocol.timeline.TimelineRead(1, listOf(click, toast), lastSeq = 2))
        assertEquals(0, dta("timeline", "events"), stderr)
        val lines = stdout.trim().lines()
        assertTrue(lines[0].endsWith("input    click \"Delete\""), stdout)
        assertTrue(lines[1].endsWith("ui       toast  \"Saved\" (short)  · app.cart items → empty  ← click \"Delete\" (exact)"), stdout)
        assertEquals("next: t1:2", lines.last())
    }

    @Test
    fun `timeline causes prints the chain and state prints the facets`() {
        daemon.ops.timeline.ingest(io.yamsergey.dta.protocol.timeline.TimelineRead(1, listOf(click, toast), lastSeq = 2))
        assertEquals(0, dta("timeline", "causes", "1:2"), stderr)
        assertTrue(stdout.lines()[1].startsWith("  ← exact issued-during:click  1:1  input"), stdout)
        assertEquals(0, dta("state"), stderr)
        assertTrue("app.cart = empty" in stdout, stdout)
        assertEquals(0, dta("state", "graph", "--facets", "app.cart"), stderr)
        assertTrue("items → empty" in stdout, stdout)
    }

    // --- Command tree (T025)

    @Test
    fun `one leaf per catalog operation, plus the host-local mcp and run`() {
        val cli = Cli(runner = { _, _, _ -> 0 }, mcp = { 0 })
        assertEquals((Catalog.all.map { it.cliPath } + listOf(listOf("mcp"), listOf("run"))).toSet(), cli.leafPaths().toSet())
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

    // --- Layout with the screen (feature 014, T008)

    @Test
    fun `layout --screen saves the image to a file and says how it lines up`() {
        daemon.ops.screenCapture = daemon.ops.capture.copy(
            screenImage = io.yamsergey.dta.protocol.layout.ScreenImage(
                state = io.yamsergey.dta.protocol.layout.ScreenImageState.PRESENT, widthPx = 1080, heightPx = 2400,
                alignment = io.yamsergey.dta.protocol.layout.Alignment(io.yamsergey.dta.protocol.layout.AlignmentKind.ALIGNED),
                file = io.yamsergey.dta.protocol.layout.FileRef("/work/shot.png", 36_167, "ab"),
            ),
            degraded = listOf(io.yamsergey.dta.protocol.rpc.DegradationMarker("layout", "screen-changed-during-capture: Toast appeared")),
        )
        assertEquals(0, dta("layout", "--screen", "shot.png"))
        assertEquals(io.yamsergey.dta.daemon.layout.ScreenRequest("/work/shot.png"), daemon.ops.screenRequests.last())
        assertTrue("Screen: /work/shot.png — 1080×2400, aligned" in stdout, stdout)
        assertTrue("screen-changed-during-capture: Toast appeared" in stdout, stdout)
    }

    @Test
    fun `layout --screen needs a file, and the inline image is never offered on the command line`() {
        assertEquals(ExitCodes.USAGE, dta("layout", "--screen"))
        val names = Cli(runner = { _, _, _ -> 0 }, mcp = { 0 }).parameterNames(listOf("layout"))
        assertTrue("--screen" in names, "$names")
        assertFalse("--screen-output" in names, "$names")
    }

    @Test
    fun `an image that was not taken is said so`() {
        daemon.ops.screenCapture = daemon.ops.capture.copy(
            screenImage = io.yamsergey.dta.protocol.layout.ScreenImage(
                state = io.yamsergey.dta.protocol.layout.ScreenImageState.BLOCKED, reason = "pkg/.Main is a secure window",
            ),
        )
        assertEquals(0, dta("layout", "--screen", "shot.png"))
        assertTrue("Screen: not saved — blocked: pkg/.Main is a secure window" in stdout, stdout)
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
        val coloured = Style(color = true, unicode = true).mark(io.yamsergey.dta.present.StatementKind.SCRIPTED, "scripted")
        assertEquals("\u001b[95m✎ scripted\u001b[0m", coloured)
    }

    @Test
    fun `version`() {
        assertEquals(0, dta("--version"))
        assertTrue(stdout.startsWith("dta "), stdout)
    }

    // --- apps watch (feature 009, T015)

    @Test
    fun `apps still lists the apps, apps watch follows their connections`() {
        assertEquals(0, dta("apps"))
        assertTrue("com.example.shop" in stdout, stdout)
        outBytes.reset()

        val exit = java.util.concurrent.atomic.AtomicInteger(-1)
        val watch = kotlin.concurrent.thread { exit.set(dta("-d", "emulator-5554", "apps", "watch")) }
        waitFor { "Watching app connections" in stderr }
        daemon.ops.appEventFlow.tryEmit(io.yamsergey.dta.protocol.ops.AppConnectionEvent("emulator-5554", "com.example.shop", "connected"))
        daemon.ops.appEventFlow.tryEmit(io.yamsergey.dta.protocol.ops.AppConnectionEvent("emulator-5554", "com.example.shop", "lost", "the app's sidekick stopped answering"))
        waitFor { "lost" in stdout }
        daemon.close()
        watch.join(5_000)

        val lines = stdout.lines().filter { it.isNotBlank() }
        assertTrue(lines[0].endsWith("emulator-5554  com.example.shop  running (foreground)"), lines[0])
        assertTrue(lines[1].endsWith("emulator-5554  com.example.shop  connected"), lines[1])
        assertTrue(lines[2].endsWith("emulator-5554  com.example.shop  lost: the app's sidekick stopped answering"), lines[2])
        assertEquals(ExitCodes.NOT_AVAILABLE, exit.get())
    }

    @Test
    fun `apps watch json prints each event as one JSON line`() {
        val watch = kotlin.concurrent.thread { dta("--json", "apps", "watch") }
        waitFor { stdout.lines().any { it.startsWith("{") } }
        daemon.ops.appEventFlow.tryEmit(io.yamsergey.dta.protocol.ops.AppConnectionEvent("emulator-5554", "com.example.shop", "ended"))
        waitFor { "ended" in stdout }
        daemon.close()
        watch.join(5_000)
        val event = DtaJson.parseToJsonElement(stdout.lines().first { "ended" in it }).jsonObject
        assertEquals("ended", event["state"]!!.jsonPrimitive.content)
        assertEquals("com.example.shop", event["app"]!!.jsonPrimitive.content)
    }

    @Test
    fun `network watch says when another client cleared the history`() {
        val watch = kotlin.concurrent.thread { dta("-d", "emulator-5554", "-a", "com.example.shop", "network", "watch") }
        waitFor { "Watching network traffic" in stderr }
        kotlinx.coroutines.runBlocking { daemon.connect(io.yamsergey.dta.protocol.ops.ClientKind.MCP).client.call("network.clear", kotlinx.serialization.json.buildJsonObject {
            put("device", kotlinx.serialization.json.JsonPrimitive("emulator-5554")); put("app", kotlinx.serialization.json.JsonPrimitive("com.example.shop"))
        }) }
        waitFor { "cleared" in stdout }
        daemon.close()
        watch.join(5_000)
        assertTrue("History cleared by mcp: 3 entries removed." in stdout, stdout)
    }

    private fun waitFor(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!check()) {
            check(System.currentTimeMillis() < deadline) { "timed out; stdout=$stdout stderr=$stderr" }
            Thread.sleep(20)
        }
    }
}
