package io.yamsergey.dta.run

import io.yamsergey.dta.inject.InitScript
import io.yamsergey.dta.inject.InjectionConfig
import io.yamsergey.dta.inject.InjectionOutcome
import io.yamsergey.dta.inject.InjectionReport
import io.yamsergey.dta.inject.ProjectInjection
import io.yamsergey.dta.inject.RunOutcome as Selection
import io.yamsergey.dta.inject.RunSelection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * `dta run` (feature 010, data-model state transitions): resolve the project and the device,
 * build and install with DTA injected in one Gradle invocation, launch, and wait for the app's
 * DTA connection through the daemon. Every failure names the step, the cause and the next
 * step (FR-007); "ran without DTA" is its own outcome. One session for the CLI, MCP and the
 * plugin (FR-019).
 */
class RunSession(
    private val gradle: GradleRunner,
    private val device: DeviceActions,
    private val daemon: DaemonView,
    private val files: RunFiles,
    /** The sidekick version to inject: this release (009 InjectionConfig.forRelease). */
    private val sidekickVersion: String,
) {
    suspend fun run(request: RunRequest, progress: (RunProgress) -> Unit = {}): RunResult =
        Run(request, progress).execute()

    private inner class Run(private val request: RunRequest, private val progress: (RunProgress) -> Unit) {
        private val steps = mutableListOf<Step>()
        private var chosen: DeviceChoice.Chosen? = null
        private var log: File? = null
        private var selection: RunSelection? = null
        private var injection: ProjectInjection? = null
        private var activity: String? = null

        private fun started(step: StepName, message: String) = progress(RunProgress.StepStarted(step, message))

        private fun done(step: Step) {
            steps += step
            progress(RunProgress.StepDone(step))
        }

        private fun result(outcome: RunOutcome, code: ExitCode, failure: Failure? = null) = RunResult(
            outcome = outcome,
            exitCode = code.code,
            device = chosen?.let { DeviceSummary(it.serial, it.model) },
            project = request.project.path,
            module = selection?.module,
            variant = selection?.variant,
            applicationId = selection?.applicationId,
            activity = activity,
            injection = injection,
            steps = steps.toList(),
            log = log?.path,
            failure = failure,
        )

        private fun fail(
            step: StepName,
            mark: TimeSource.Monotonic.ValueTimeMark,
            code: ExitCode,
            cause: String,
            next: String,
            details: JsonObject? = null,
            phases: Map<Phase, Long>? = null,
        ): RunResult {
            done(Step(step, StepOutcome.FAILED, mark.elapsedNow().inWholeMilliseconds, phases))
            return result(RunOutcome.FAILED, code, Failure(step, cause, next, details))
        }

        suspend fun execute(): RunResult {
            // Resolve: the project and, unless only building, the device.
            val resolving = TimeSource.Monotonic.markNow()
            started(StepName.RESOLVE, "Resolving the project and the device")
            if (!File(request.project, "gradlew").isFile) {
                return fail(
                    StepName.RESOLVE, resolving, ExitCode.PROJECT,
                    "No Gradle wrapper (gradlew) in ${request.project}: this is not the root of an Android project.",
                    "Run dta run in the folder with gradlew, or pass --project <folder>.",
                )
            }
            if (request.mode != RunMode.BUILD_ONLY) {
                val choice = try {
                    daemon.chooseDevice(request.device)
                } catch (e: DaemonUnavailableException) {
                    return if (e.versionConflict) {
                        fail(StepName.RESOLVE, resolving, ExitCode.VERSION_CONFLICT, e.message.orEmpty(), "Use the same DTA release for the CLI, the plugin and the daemon.")
                    } else {
                        fail(StepName.RESOLVE, resolving, ExitCode.INTERNAL, e.message.orEmpty(), "Run `dta daemon status` to see the daemon's state.")
                    }
                }
                when (choice) {
                    is DeviceChoice.Chosen -> chosen = choice
                    DeviceChoice.NoDevice -> return fail(
                        StepName.RESOLVE, resolving, ExitCode.NOT_AVAILABLE,
                        "No device is connected.", "Start an emulator or connect a device with USB debugging, then run again.",
                    )
                    is DeviceChoice.Several -> return fail(
                        StepName.RESOLVE, resolving, ExitCode.NOT_AVAILABLE,
                        "${choice.serials.size} devices are connected: ${choice.serials.joinToString(", ")}.", "Choose one with -d <serial>.",
                        choices(choice.serials),
                    )
                    is DeviceChoice.NotFound -> return fail(
                        StepName.RESOLVE, resolving, ExitCode.NOT_AVAILABLE,
                        "Device ${choice.serial} is not connected.",
                        if (choice.online.isEmpty()) "No device is online; start one and run again." else "Connected: ${choice.online.joinToString(", ")}. Choose one with -d <serial>.",
                        choices(choice.online),
                    )
                }
            }
            // Read before the build: installing an update ends a running app before the launch. The
            // application id is known only after the build; the last run of this project names it.
            val lastAppId = files.report(request.project).takeIf { it.isFile }
                ?.let { runCatching { InjectionReport.parse(it.readText()).run?.applicationId }.getOrNull() }
            val runningBefore = chosen?.let { c -> lastAppId?.let { device.pidOf(c.serial, it) } }
            val resolveMs = resolving.elapsedNow().inWholeMilliseconds

            // Build (and install): one Gradle invocation; the init script selects and reports.
            val building = TimeSource.Monotonic.markNow()
            started(StepName.BUILD, if (request.mode == RunMode.BUILD_ONLY) "Building" else "Building and installing")
            val report = files.report(request.project).apply { delete() }
            val logFile = files.newLog().also { log = it }
            val config = InjectionConfig.forRelease(sidekickVersion)
            files.script(request.project).writeText(InitScript.render(config))
            files.request(request.project).writeText(requestJson().toString())
            val args = buildList {
                add("--console=plain")
                add("--init-script"); add(files.script(request.project).path)
                add("-P${config.reportProperty}=${report.path}")
                add("-P${config.runProperty}=${files.request(request.project).path}")
                if (!request.tooling || !request.inject) add("-P${config.toolingProperty}=false")
                add("dtaRun")
                addAll(request.gradleArgs)
            }
            val env = chosen?.let { mapOf("ANDROID_SERIAL" to it.serial) }.orEmpty()
            val lines = ArrayList<String>()
            val tracker = BuildOutput.PhaseTracker()
            val exit = gradle.run(request.project, args, env, logFile) { line ->
                lines += line
                tracker.line(line)?.let { progress(RunProgress.PhaseStarted(it)) }
                progress(RunProgress.GradleLine(line))
            }
            val phases = tracker.finish()
            val parsed = if (report.isFile) runCatching { InjectionReport.parse(report.readText()) }.getOrNull() else null
            val run = parsed?.run
            selection = run

            // The selection failed: no module or variant to build; Gradle built nothing.
            if (run != null && run.outcome != Selection.SELECTED) {
                steps += Step(StepName.RESOLVE, StepOutcome.FAILED, resolveMs + building.elapsedNow().inWholeMilliseconds)
                progress(RunProgress.StepDone(steps.last()))
                return result(RunOutcome.FAILED, ExitCode.PROJECT, selectionFailure(run))
            }
            if (exit != 0) return buildFailure(building, lines, phases, logFile)
            if (run == null) {
                return fail(
                    StepName.BUILD, building, ExitCode.PROJECT,
                    "Gradle finished, but the DTA init script did not report what it built.",
                    "See the build log ${logFile.path}; the project may not run init scripts, or its Gradle is not supported.",
                    phases = phases,
                )
            }
            // The resolve step ends with the selection, known only now; it is reported first.
            Step(StepName.RESOLVE, StepOutcome.OK, resolveMs).let { steps.add(0, it); progress(RunProgress.StepDone(it)) }
            injection = parsed.projects.firstOrNull { it.path == run.module }
            keep(parsed)
            done(Step(StepName.BUILD, StepOutcome.OK, building.elapsedNow().inWholeMilliseconds, phases))

            val withoutDta = injection?.outcome.let { it != InjectionOutcome.INJECTED && it != InjectionOutcome.PROJECT_PROVIDED }
            val noDta = if (withoutDta) noDtaStatement() else null
            when (request.mode) {
                RunMode.BUILD_ONLY -> return if (noDta != null) result(RunOutcome.RAN_WITHOUT_DTA, ExitCode.WITHOUT_DTA, noDta) else result(RunOutcome.BUILT, ExitCode.OK)
                RunMode.NO_LAUNCH -> return if (noDta != null) result(RunOutcome.RAN_WITHOUT_DTA, ExitCode.WITHOUT_DTA, noDta) else result(RunOutcome.INSTALLED, ExitCode.OK)
                RunMode.RUN -> Unit
            }

            // Launch, with the connection watched first so its event cannot be missed.
            val serial = chosen!!.serial
            val appId = run.applicationId!!
            val launching = TimeSource.Monotonic.markNow()
            started(StepName.LAUNCH, "Launching")
            val component = request.activity?.let { component(appId, it) } ?: device.resolveLauncher(serial, appId)
                ?: return fail(StepName.LAUNCH, launching, ExitCode.LAUNCH, "$appId has no launcher activity.", "Name the activity to start with --activity.")
            activity = component
            val previous = device.pidOf(serial, appId)
            val watch = if (noDta == null) daemon.watch(serial, appId) else null
            watch.use {
                when (val launch = device.start(serial, component)) {
                    is LaunchOutcome.Failed -> return fail(
                        StepName.LAUNCH, launching, ExitCode.LAUNCH,
                        "$component did not start: ${launch.error}", "Check the activity name, or start the app by hand to see what it does.",
                        buildJsonObject { put("error", launch.error) },
                    )
                    is LaunchOutcome.Started -> done(
                        Step(StepName.LAUNCH, StepOutcome.OK, launching.elapsedNow().inWholeMilliseconds, restarted = launch.restarted || previous != null || (runningBefore != null && lastAppId == appId)),
                    )
                }
                if (watch == null) return result(RunOutcome.RAN_WITHOUT_DTA, ExitCode.WITHOUT_DTA, noDta)

                // Connect: only the daemon says the app is connected (constitution II).
                val connecting = TimeSource.Monotonic.markNow()
                started(StepName.CONNECT, "Waiting for DTA in $appId")
                while (true) {
                    val left = request.timeout - connecting.elapsedNow()
                    val outcome = if (left.isPositive()) watch.next(left) else ConnectionOutcome.TimedOut
                    when (outcome) {
                        ConnectionOutcome.Connected -> {
                            // A connection of the process that was just stopped does not count.
                            if (previous != null && device.pidOf(serial, appId) == previous) continue
                            done(Step(StepName.CONNECT, StepOutcome.OK, connecting.elapsedNow().inWholeMilliseconds))
                            return result(RunOutcome.CONNECTED, ExitCode.OK)
                        }
                        is ConnectionOutcome.Refused -> return fail(
                            StepName.CONNECT, connecting, ExitCode.VERSION_CONFLICT,
                            "$appId refused DTA: ${outcome.reason}",
                            "Build the app with DTA $sidekickVersion, the release of this CLI: a project that declares dta-sidekick itself must use that version.",
                        )
                        ConnectionOutcome.TimedOut -> return timeout(connecting, serial, appId)
                    }
                }
            }
        }

        private fun requestJson() = buildJsonObject {
            put("module", request.module)
            put("variant", request.variant)
            put("buildOnly", request.mode == RunMode.BUILD_ONLY)
            put("inject", request.inject)
            put("reinstall", request.reinstall)
        }

        private fun choices(list: List<String>) = buildJsonObject { put("choices", buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }) }

        private fun selectionFailure(run: RunSelection): Failure {
            val choices = run.choices.orEmpty().joinToString(", ")
            val (cause, next) = when (run.outcome) {
                Selection.NO_APP_MODULE -> "There is no Android app module in ${request.project}." to "Run dta run in the root of the project that contains the app, or pass --project <folder>."
                Selection.MODULE_REQUIRED -> "The project has several app modules: $choices." to "Choose one with --module <path>."
                Selection.MODULE_NOT_FOUND -> "There is no app module ${request.module}; the app modules are: $choices." to "Choose one with --module <path>."
                Selection.VARIANT_REQUIRED -> "${run.module} has several debuggable variants: $choices." to "Choose one with --variant <name>."
                Selection.VARIANT_NOT_FOUND -> "${run.module} has no variant ${request.variant}; its variants are: $choices." to "Choose a debuggable one with --variant <name>."
                Selection.NOT_DEBUGGABLE -> "${run.reason ?: "The variant is not debuggable"}: DTA never goes into a build that is not debuggable." to
                    "Choose a debuggable variant with --variant, such as debug."
                Selection.SELECTED -> error("not a failure")
            }
            return Failure(StepName.RESOLVE, cause, next, run.choices?.let { choices(it) })
        }

        private fun buildFailure(building: TimeSource.Monotonic.ValueTimeMark, lines: List<String>, phases: Map<Phase, Long>, logFile: File): RunResult {
            if (BuildOutput.noDevice(lines)) {
                return fail(StepName.BUILD, building, ExitCode.NOT_AVAILABLE, "Gradle found no connected device to install on.", "Connect the device again and run again; see ${logFile.path}.", phases = phases)
            }
            BuildOutput.installFailure(lines)?.let { install ->
                return fail(
                    StepName.BUILD, building, ExitCode.INSTALL, "The install failed: ${install.cause}", install.next,
                    buildJsonObject { put("code", install.code) }, phases,
                )
            }
            val failure = BuildOutput.failure(lines)
            val cause = "The build failed: " + (failure.message?.let { "$it (${failure.location})" } ?: failure.summary.lineSequence().firstOrNull().orEmpty())
            return fail(
                StepName.BUILD, building, ExitCode.BUILD, cause, "Fix the error and run again; the full output is in ${logFile.path}.",
                buildJsonObject {
                    put("summary", failure.summary)
                    put("location", failure.location)
                    put("message", failure.message)
                },
                phases,
            )
        }

        private fun noDtaStatement(): Failure {
            val outcome = injection?.outcome
            val cause = when (outcome) {
                InjectionOutcome.OFF -> "The app was built without DTA: injection was off (--no-inject)."
                InjectionOutcome.UNRESOLVED -> "The app was built without DTA: the sidekick could not be resolved: ${injection?.reason}."
                InjectionOutcome.UNSUPPORTED -> "The app was built without DTA: the init script could not handle this project: ${injection?.reason}."
                else -> "The app was built without DTA: the build did not report injection for ${selection?.module}."
            }
            val next = when (outcome) {
                InjectionOutcome.OFF -> "Run without --no-inject, or add io.github.yamsergey:dta-sidekick:$sidekickVersion to debugImplementation."
                InjectionOutcome.UNRESOLVED -> "Check the network or the repositories, or add io.github.yamsergey:dta-sidekick:$sidekickVersion to debugImplementation."
                else -> "Add io.github.yamsergey:dta-sidekick:$sidekickVersion to debugImplementation."
            }
            return Failure(StepName.BUILD, cause, next)
        }

        private suspend fun timeout(connecting: TimeSource.Monotonic.ValueTimeMark, serial: String, appId: String): RunResult {
            val alive = device.pidOf(serial, appId) != null
            val socket = alive && daemon.sidekickPresent(serial, appId)
            val seconds = request.timeout.inWholeSeconds
            val (cause, next) = when {
                !alive -> "$appId ended before DTA connected; it may have crashed at start." to "Look at `adb logcat` for the crash, then run again."
                !socket -> "$appId is running, but its DTA sidekick is not listening (injection: ${injection?.outcome?.name?.lowercase() ?: "unknown"})." to
                    "Check that the installed app is the build dta run made: run again with --reinstall if another build replaced it."
                else -> "$appId's sidekick is listening, but the daemon did not connect within $seconds s." to
                    "Run `dta apps` to see the connection state, or run again with a longer --timeout."
            }
            return fail(
                StepName.CONNECT, connecting, ExitCode.CONNECT, cause, next,
                buildJsonObject {
                    put("processAlive", alive)
                    put("socketPresent", socket)
                    put("injection", injection?.outcome?.name?.lowercase())
                },
            )
        }

        /** Kept per application id, so the daemon can say what injection added (research R6). */
        private fun keep(report: InjectionReport) {
            report.projects.flatMap { p -> p.variants.mapNotNull { it.applicationId } }.distinct().forEach { id ->
                runCatching { files.injection(id).apply { parentFile.mkdirs() }.writeText(report.toJson()) }
            }
        }
    }

    companion object {
        /** `.Main` and `com.x.Main` are activities of [applicationId]; `pkg/cls` is taken as it is. */
        fun component(applicationId: String, activity: String): String = when {
            '/' in activity -> activity
            else -> "$applicationId/$activity"
        }
    }
}
