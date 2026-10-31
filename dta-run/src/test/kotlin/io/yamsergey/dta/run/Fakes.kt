package io.yamsergey.dta.run

import io.yamsergey.dta.inject.InjectionOutcome
import io.yamsergey.dta.inject.InjectionReport
import io.yamsergey.dta.inject.ProjectInjection
import io.yamsergey.dta.inject.RunOutcome as SelectionOutcome
import io.yamsergey.dta.inject.RunSelection
import io.yamsergey.dta.inject.ToolingInjection
import io.yamsergey.dta.inject.ToolingOutcome
import io.yamsergey.dta.inject.VariantInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration

const val APP_ID = "io.example.shop"
const val SERIAL = "emulator-5554"

/** A project folder with a wrapper; nothing in it is run. */
fun fakeProject(): File = Files.createTempDirectory("dta-run-project").toFile().apply { File(this, "gradlew").writeText("#!/bin/sh\n") }

fun selectedReport(
    outcome: InjectionOutcome = InjectionOutcome.INJECTED,
    reason: String? = null,
    selection: RunSelection = RunSelection(SelectionOutcome.SELECTED, ":app", "debug", ":app:installDebug", APP_ID),
) = InjectionReport(
    "0.11.0",
    listOf(
        ProjectInjection(
            ":app", outcome, reason, ToolingInjection(ToolingOutcome.NOT_APPLICABLE),
            listOf(VariantInfo("debug", "debug", emptyList(), true, APP_ID), VariantInfo("release", "release", emptyList(), false, APP_ID)),
        ),
    ),
    selection,
)

/**
 * Gradle that prints [lines], writes [report] where the arguments say, and exits [exit].
 * Records what it was given.
 */
class FakeGradle(
    var exit: Int = 0,
    var lines: List<String> = listOf("> Task :app:compileDebugKotlin", "> Task :app:packageDebug", "> Task :app:installDebug", "> Task :dtaRun", "BUILD SUCCESSFUL"),
    var report: InjectionReport? = selectedReport(),
) : GradleRunner {
    var args: List<String> = emptyList()
    var env: Map<String, String> = emptyMap()
    var request: JsonObject? = null
    var runs = 0

    override suspend fun run(project: File, args: List<String>, env: Map<String, String>, log: File, onLine: (String) -> Unit): Int {
        runs++
        this.args = args
        this.env = env
        request = property(args, "dta.run.request")?.let { Json.parseToJsonElement(File(it).readText()).jsonObject }
        log.parentFile.mkdirs()
        log.writeText(lines.joinToString("\n"))
        lines.forEach(onLine)
        report?.let { r -> property(args, "dta.inject.report")?.let { File(it).apply { parentFile.mkdirs() }.writeText(r.toJson()) } }
        return exit
    }

    fun property(args: List<String>, name: String): String? = args.firstOrNull { it.startsWith("-P$name=") }?.substringAfter('=')
}

class FakeDevice(
    var launcher: String? = "$APP_ID/.MainActivity",
    var launch: LaunchOutcome = LaunchOutcome.Started(restarted = false),
    /** pidOf answers in order (before the build when a last run is known, before the launch, after it); the last repeats. */
    var pids: MutableList<Long?> = mutableListOf(null, 4242),
) : DeviceActions {
    val started = mutableListOf<String>()
    val calls = mutableListOf<String>()

    override suspend fun resolveLauncher(serial: String, applicationId: String): String? = launcher.also { calls += "resolve" }

    override suspend fun start(serial: String, component: String): LaunchOutcome {
        calls += "start"
        started += component
        return launch
    }

    override suspend fun pidOf(serial: String, applicationId: String): Long? {
        calls += "pidof"
        return if (pids.size > 1) pids.removeAt(0) else pids.firstOrNull()
    }
}

class FakeDaemon(
    var choice: DeviceChoice = DeviceChoice.Chosen(SERIAL, "Pixel 8"),
    /** What the watch answers, in order; the last repeats. */
    var outcomes: MutableList<ConnectionOutcome> = mutableListOf(ConnectionOutcome.Connected),
    var socket: Boolean = false,
    var unavailable: DaemonUnavailableException? = null,
) : DaemonView {
    val calls = mutableListOf<String>()
    var watched: Pair<String, String>? = null
    var closed = false

    override suspend fun chooseDevice(serial: String?): DeviceChoice {
        calls += "choose:$serial"
        unavailable?.let { throw it }
        return choice
    }

    override suspend fun watch(serial: String, applicationId: String): ConnectionWatch {
        calls += "watch"
        watched = serial to applicationId
        return object : ConnectionWatch {
            override suspend fun next(timeout: Duration): ConnectionOutcome = if (outcomes.size > 1) outcomes.removeAt(0) else outcomes.first()
            override fun close() {
                closed = true
            }
        }
    }

    override suspend fun sidekickPresent(serial: String, applicationId: String): Boolean = socket
}

class Harness(
    val gradle: FakeGradle = FakeGradle(),
    val device: FakeDevice = FakeDevice(),
    val daemon: FakeDaemon = FakeDaemon(),
) {
    val home: File = Files.createTempDirectory("dta-run-home").toFile()
    val progress = mutableListOf<RunProgress>()
    val files = RunFiles(home)
    val session = RunSession(gradle, device, daemon, files, sidekickVersion = "0.11.0")

    /** A project this home has run before: its last report names the app. */
    fun ranBefore(project: File = fakeProject()): File = project.also { files.report(it).writeText(selectedReport().toJson()) }

    suspend fun run(request: RunRequest = RunRequest(fakeProject())): RunResult = session.run(request) { progress += it }
}
