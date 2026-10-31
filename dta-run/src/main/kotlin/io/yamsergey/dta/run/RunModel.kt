package io.yamsergey.dta.run

import io.yamsergey.dta.inject.ProjectInjection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How far a run goes (contracts/run.md). */
enum class RunMode { RUN, NO_LAUNCH, BUILD_ONLY }

/** One `dta run` / `app_run` invocation (data-model RunRequest). */
data class RunRequest(
    val project: File,
    val module: String? = null,
    val variant: String? = null,
    val activity: String? = null,
    /** Serial; null applies the daemon's device rule. Ignored for [RunMode.BUILD_ONLY]. */
    val device: String? = null,
    val mode: RunMode = RunMode.RUN,
    val inject: Boolean = true,
    val tooling: Boolean = true,
    /** Uninstall first, which deletes the app's data. */
    val reinstall: Boolean = false,
    val timeout: Duration = 30.seconds,
    val gradleArgs: List<String> = emptyList(),
) {
    init {
        require(!(reinstall && mode == RunMode.BUILD_ONLY)) { "--reinstall installs; it cannot be combined with --build-only" }
        require(timeout.isPositive()) { "the timeout must be positive" }
    }
}

@Serializable
enum class StepName {
    @SerialName("resolve") RESOLVE,
    @SerialName("build") BUILD,
    @SerialName("launch") LAUNCH,
    @SerialName("connect") CONNECT,
}

@Serializable
enum class StepOutcome {
    @SerialName("ok") OK,
    @SerialName("skipped") SKIPPED,
    @SerialName("failed") FAILED,
}

/** Gradle's work as the run reports it; durations are measured between task lines. */
@Serializable
enum class Phase {
    @SerialName("configure") CONFIGURE,
    @SerialName("compile") COMPILE,
    @SerialName("package") PACKAGE,
    @SerialName("install") INSTALL,
}

@Serializable
data class Step(
    val step: StepName,
    val outcome: StepOutcome,
    val ms: Long,
    val phases: Map<Phase, Long>? = null,
    /** Launch only: the app was running and was restarted. */
    val restarted: Boolean? = null,
)

/** What went wrong, and what to do (FR-007). [details] is step-specific (contracts/run.md). */
@Serializable
data class Failure(val step: StepName, val cause: String, val next: String, val details: JsonObject? = null)

@Serializable
enum class RunOutcome {
    @SerialName("connected") CONNECTED,
    @SerialName("built") BUILT,
    @SerialName("installed") INSTALLED,
    @SerialName("ran-without-dta") RAN_WITHOUT_DTA,
    @SerialName("failed") FAILED,
}

/** The exit codes of `dta run` (research R9). 0–4 are shared with the other commands. */
enum class ExitCode(val code: Int) {
    OK(0),
    INTERNAL(1),
    USAGE(2),
    NOT_AVAILABLE(3),
    VERSION_CONFLICT(4),
    PROJECT(10),
    BUILD(11),
    INSTALL(12),
    LAUNCH(13),
    CONNECT(14),
    WITHOUT_DTA(15),
}

@Serializable
data class DeviceSummary(val serial: String, val model: String? = null)

/** The whole run (contracts/run.md JSON); also the `app_run` result. */
@Serializable
data class RunResult(
    val outcome: RunOutcome,
    val exitCode: Int,
    val device: DeviceSummary? = null,
    val project: String,
    val module: String? = null,
    val variant: String? = null,
    val applicationId: String? = null,
    val activity: String? = null,
    val injection: ProjectInjection? = null,
    val steps: List<Step>,
    val log: String? = null,
    val failure: Failure? = null,
)

/** Progress while the run goes (CLI lines, MCP progress notifications). */
sealed interface RunProgress {
    data class StepStarted(val step: StepName, val message: String) : RunProgress
    data class PhaseStarted(val phase: Phase) : RunProgress
    data class StepDone(val step: Step) : RunProgress
    /** A line of Gradle's output (shown with `--verbose`). */
    data class GradleLine(val line: String) : RunProgress
}

private val RESULT_JSON = kotlinx.serialization.json.Json { encodeDefaults = true; explicitNulls = true }

/** The result document of contracts/run.md: `dta run --json` and `app_run` print exactly this. */
fun RunResult.toJson(): String = RESULT_JSON.encodeToString(RunResult.serializer(), this)
