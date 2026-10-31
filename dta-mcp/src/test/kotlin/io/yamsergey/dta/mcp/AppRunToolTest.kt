package io.yamsergey.dta.mcp

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.spec.McpSchema
import io.yamsergey.dta.protocol.ops.Catalog
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.run.DeviceSummary
import io.yamsergey.dta.run.Failure
import io.yamsergey.dta.run.Phase
import io.yamsergey.dta.run.RunMode
import io.yamsergey.dta.run.RunOutcome
import io.yamsergey.dta.run.RunProgress
import io.yamsergey.dta.run.RunRequest
import io.yamsergey.dta.run.RunResult
import io.yamsergey.dta.run.Step
import io.yamsergey.dta.run.StepName
import io.yamsergey.dta.run.StepOutcome
import io.yamsergey.dta.run.toJson
import io.yamsergey.dta.testing.TestDaemon
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.http.HttpRequest
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** T029 (US5): `app_run` — the same session and document as `dta run --json`, with progress. */
class AppRunToolTest {
    private val requests = mutableListOf<RunRequest>()

    private val connected = RunResult(
        RunOutcome.CONNECTED, 0, DeviceSummary("emulator-5554"), "/work/shop", ":app", "debug", "io.example.shop", "io.example.shop/.MainActivity", null,
        listOf(Step(StepName.RESOLVE, StepOutcome.OK, 1), Step(StepName.BUILD, StepOutcome.OK, 2), Step(StepName.LAUNCH, StepOutcome.OK, 3), Step(StepName.CONNECT, StepOutcome.OK, 4)),
    )

    private val runner: AppRunner = { request, progress ->
        requests += request
        progress(RunProgress.StepStarted(StepName.RESOLVE, "Resolving"))
        progress(RunProgress.StepDone(connected.steps[0]))
        progress(RunProgress.StepStarted(StepName.BUILD, "Building and installing"))
        progress(RunProgress.PhaseStarted(Phase.COMPILE))
        progress(RunProgress.StepDone(connected.steps[1]))
        connected.copy(project = request.project.path)
    }

    @Test
    fun `the text is the run's JSON document, an error only when it failed`() = runBlocking<Unit> {
        val tool = AppRunTool(runner, projects = { listOf(File("/work/shop")) })
        val outcome = tool.invoke(JsonObject(emptyMap()))
        assertEquals(connected.toJson(), outcome.text)
        assertFalse(outcome.isError)

        val failed = AppRunTool({ _, _ -> connected.copy(outcome = RunOutcome.FAILED, exitCode = 11, failure = Failure(StepName.BUILD, "x", "y")) }, projects = { listOf(File("/w")) })
        assertTrue(failed.invoke(JsonObject(emptyMap())).isError)
    }

    @Test
    fun `arguments reach the request`() = runBlocking<Unit> {
        val tool = AppRunTool(runner, projects = { emptyList() })
        tool.invoke(
            JsonObject(
                mapOf(
                    "project" to JsonPrimitive("/work/other"), "module" to JsonPrimitive(":app"), "variant" to JsonPrimitive("freeDebug"),
                    "activity" to JsonPrimitive(".Deep"), "device" to JsonPrimitive("emulator-5554"), "noLaunch" to JsonPrimitive(true),
                    "noTooling" to JsonPrimitive(true), "reinstall" to JsonPrimitive(true), "timeoutSeconds" to JsonPrimitive(90),
                    "gradleArgs" to JsonArray(listOf(JsonPrimitive("--offline"))),
                ),
            ),
        )
        val r = requests.single()
        assertEquals(File("/work/other"), r.project)
        assertEquals(":app", r.module)
        assertEquals("freeDebug", r.variant)
        assertEquals(".Deep", r.activity)
        assertEquals("emulator-5554", r.device)
        assertEquals(RunMode.NO_LAUNCH, r.mode)
        assertFalse(r.tooling)
        assertTrue(r.reinstall)
        assertEquals(90.seconds, r.timeout)
        assertEquals(listOf("--offline"), r.gradleArgs)
    }

    @Test
    fun `without a project the server's single project is used, several are listed`() = runBlocking<Unit> {
        AppRunTool(runner, projects = { listOf(File("/work/shop")) }).invoke(JsonObject(emptyMap()))
        assertEquals(File("/work/shop"), requests.single().project)

        val several = AppRunTool(runner, projects = { listOf(File("/a"), File("/b")) }).invoke(JsonObject(emptyMap()))
        assertTrue(several.isError && "/a" in several.text && "/b" in several.text, several.text)
        val none = AppRunTool(runner, projects = { emptyList() }).invoke(JsonObject(emptyMap()))
        assertTrue(none.isError && "project" in none.text, none.text)
        assertEquals(1, requests.size, "nothing ran without a project")
    }

    @Test
    fun `progress counts the steps done`() = runBlocking<Unit> {
        val seen = mutableListOf<AppRunTool.Progress>()
        AppRunTool(runner, projects = { listOf(File("/w")) }).invoke(JsonObject(emptyMap())) { seen += it }
        assertEquals(
            listOf(AppRunTool.Progress(0, "Resolving"), AppRunTool.Progress(1, "Building and installing"), AppRunTool.Progress(1, "Building: compile")),
            seen,
        )
    }

    private val daemon = TestDaemon()

    @AfterTest
    fun tearDown() = daemon.close()

    @Test
    fun `over HTTP the tool is listed beside the catalog's, and progress reaches the agent`() {
        val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val server = McpHttpServer(port, "cred", connect = { daemon.connect(ClientKind.MCP_HTTP) }, projects = { listOf(File("/work/shop")) }, runApp = runner)
        server.start()
        val progress = CopyOnWriteArrayList<McpSchema.ProgressNotification>()
        try {
            val transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:$port").endpoint("/mcp")
                .jsonMapper(JacksonMcpJsonMapper(JsonMapper.builder().build()))
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer cred"))
                .build()
            val client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).progressConsumer { progress += it }.build().also { it.initialize() }
            try {
                assertEquals(Catalog.agentTools.map { it.toolName }.toSet() + AppRunTool.NAME, client.listTools().tools().map { it.name() }.toSet())
                val result = client.callTool(McpSchema.CallToolRequest.builder().name(AppRunTool.NAME).arguments(emptyMap<String, Any>()).progressToken("p1").build())
                assertFalse(result.isError() ?: false)
                assertTrue("\"outcome\":\"connected\"" in (result.content().single() as McpSchema.TextContent).text())
                val deadline = System.currentTimeMillis() + 5_000
                while (progress.size < 3 && System.currentTimeMillis() < deadline) Thread.sleep(20)
                assertEquals(listOf("Resolving", "Building and installing", "Building: compile"), progress.map { it.message() })
                assertTrue(progress.all { it.progressToken() == "p1" && it.total() == StepName.entries.size.toDouble() })
            } finally {
                client.closeGracefully()
            }
        } finally {
            server.stop()
        }
    }
}
