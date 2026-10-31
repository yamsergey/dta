package io.yamsergey.dta.mcp

import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.run.RunEnvironment
import io.yamsergey.dta.run.RunMode
import io.yamsergey.dta.run.RunProgress
import io.yamsergey.dta.run.RunRequest
import io.yamsergey.dta.run.RunResult
import io.yamsergey.dta.run.StepName
import io.yamsergey.dta.run.toJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import kotlin.time.Duration.Companion.seconds

/** Runs one `dta run` session and reports progress (the real one: [AppRunTool.real]). */
typealias AppRunner = suspend (RunRequest, (RunProgress) -> Unit) -> RunResult

/**
 * `app_run` (feature 010 US5, contracts/run.md): `dta run` for agents — the same session and the
 * same result document as `dta run --json`, with a progress notification per step and phase.
 * Without a `project` argument the server's [projects] name it: the stdio server's working
 * folder, the plugin's open project (several are listed, not guessed).
 */
class AppRunTool(private val runner: AppRunner, private val projects: () -> List<File>) {

    /** One progress report: [step] counts steps done so far (of [TOTAL]); [message] says what runs now. */
    data class Progress(val step: Int, val message: String)

    suspend fun invoke(arguments: JsonObject, progress: (Progress) -> Unit = {}): ToolOutcome {
        val project = arguments.string("project")?.let(::File) ?: projects().let { candidates ->
            when (candidates.size) {
                1 -> candidates.single()
                0 -> return ToolOutcome("No project to run: pass project, the path of the Android project's root (the folder with gradlew).", isError = true)
                else -> return ToolOutcome(
                    "Several projects are open: ${candidates.joinToString(", ") { it.path }}. Pass project with the one to run.", isError = true,
                )
            }
        }
        val request = try {
            RunRequest(
                project = project,
                module = arguments.string("module"),
                variant = arguments.string("variant"),
                activity = arguments.string("activity"),
                device = arguments.string("device"),
                mode = when {
                    arguments.flag("buildOnly") -> RunMode.BUILD_ONLY
                    arguments.flag("noLaunch") -> RunMode.NO_LAUNCH
                    else -> RunMode.RUN
                },
                inject = !arguments.flag("noInject"),
                tooling = !arguments.flag("noTooling"),
                reinstall = arguments.flag("reinstall"),
                timeout = ((arguments["timeoutSeconds"] as? JsonPrimitive)?.intOrNull ?: 30).seconds,
                gradleArgs = (arguments["gradleArgs"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty(),
            )
        } catch (e: IllegalArgumentException) {
            return ToolOutcome("Invalid arguments: ${e.message}", isError = true)
        }
        var done = 0
        val result = runner(request) { event ->
            when (event) {
                is RunProgress.StepStarted -> progress(Progress(done, event.message))
                is RunProgress.PhaseStarted -> progress(Progress(done, "Building: ${event.phase.name.lowercase()}"))
                is RunProgress.StepDone -> done++
                is RunProgress.GradleLine -> Unit
            }
        }
        return ToolOutcome(result.toJson(), isError = result.outcome == io.yamsergey.dta.run.RunOutcome.FAILED)
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.flag(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull == true

    companion object {
        const val NAME = "app_run"
        val TOTAL = StepName.entries.size

        const val DESCRIPTION =
            "Build, install and launch the Android app with DTA injected (no project changes), then wait until DTA is connected — `dta run`. " +
                "Use it after changing the app's code, then call network_list or layout_tree. Returns every step's outcome; a failure names the step, " +
                "the cause and the next step (exitCode 10 project, 11 build, 12 install, 13 launch, 14 connect, 15 ran without DTA). A build takes minutes: progress is reported."

        val INPUT_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                fun prop(name: String, type: String, description: String) = putJsonObject(name) { put("type", type); put("description", description) }
                prop("project", "string", "The Android project's root (the folder with gradlew). Default: the server's project.")
                prop("module", "string", "The app module's Gradle path, e.g. :app; needed when there are several.")
                prop("variant", "string", "A debuggable variant, e.g. freeDebug. Default debug.")
                prop("activity", "string", "The activity to start; a leading . is relative to the application id. Default the launcher activity.")
                prop("device", "string", "Device serial; omit when one device is connected.")
                prop("buildOnly", "boolean", "Only build, with DTA injected: no device, install or launch.")
                prop("noLaunch", "boolean", "Install, but do not launch.")
                prop("noInject", "boolean", "Build the project as it is, without adding DTA.")
                prop("noTooling", "boolean", "Do not add Compose's tooling data library.")
                prop("reinstall", "boolean", "Uninstall the app first; this deletes its data.")
                prop("timeoutSeconds", "integer", "Seconds to wait for DTA to connect. Default 30.")
                putJsonObject("gradleArgs") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Extra Gradle arguments, e.g. [\"--offline\"].")
                }
            }
        }

        /** A real run as a daemon client of [kind]. */
        fun real(kind: ClientKind): AppRunner = { request, progress ->
            val environment = RunEnvironment(kind)
            try {
                environment.session.run(request, progress)
            } finally {
                environment.disconnect()
            }
        }
    }
}
