package io.yamsergey.dta.cli.format

import io.yamsergey.dta.inject.InjectionOutcome
import io.yamsergey.dta.inject.ToolingOutcome
import io.yamsergey.dta.present.StatementKind
import io.yamsergey.dta.run.Phase
import io.yamsergey.dta.run.RunOutcome
import io.yamsergey.dta.run.RunProgress
import io.yamsergey.dta.run.RunResult
import io.yamsergey.dta.run.Step
import io.yamsergey.dta.run.StepName
import io.yamsergey.dta.run.StepOutcome
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * `dta run`'s output (contracts/run.md): progress lines while it runs, a summary of every step
 * at the end, and on failure the cause, the next step and the log. JSON is the result document.
 */
class RunFormat(private val style: Style, private val sidekickVersion: String) {

    private val ok: String get() = style.paint(if (style.unicode) "✓" else "ok", null)
    private fun failed() = style.mark(StatementKind.FAILED, "failed")
    private fun caveat(word: String) = style.mark(StatementKind.DEGRADED, word)

    /** A progress line for the terminal, or null for what is not shown. */
    fun progress(event: RunProgress, verbose: Boolean): String? = when (event) {
        is RunProgress.StepStarted -> style.paint("${event.message}…", Style.Token.INK_MUTED)
        is RunProgress.PhaseStarted -> style.paint("  ${event.phase.name.lowercase()}…", Style.Token.INK_MUTED)
        is RunProgress.GradleLine -> if (verbose) style.paint(event.line, Style.Token.INK_MUTED) else null
        is RunProgress.StepDone -> null
    }

    fun summary(result: RunResult): String = buildList {
        result.device?.let { add(row("Device", it.serial + (it.model?.let { m -> " ($m)" } ?: ""))) }
        if (result.module != null) add(row("Project", "${result.project}  module ${result.module}  variant ${result.variant}"))
        result.steps.forEach { step ->
            when (step.step) {
                StepName.RESOLVE -> if (step.outcome == StepOutcome.FAILED) add(row("Resolve", failed()))
                StepName.BUILD -> {
                    add(row("Build", "${mark(step)} ${seconds(step.ms)}" + (step.phases?.let { "  (" + phases(it) + ")" } ?: "")))
                    if (step.outcome == StepOutcome.OK) add(row("DTA", injection(result)))
                }
                StepName.LAUNCH -> add(row("Launch", "${mark(step)} " + (result.activity ?: "") + if (step.restarted == true) " (restarted)" else ""))
                StepName.CONNECT -> add(row("DTA", if (step.outcome == StepOutcome.OK) "$ok connected in ${seconds(step.ms)}" else "${failed()} not connected after ${seconds(step.ms)}"))
            }
        }
        result.failure?.let { f ->
            add("")
            add(if (result.outcome == RunOutcome.RAN_WITHOUT_DTA) caveat("without DTA") + " " + f.cause else f.cause)
            add("Next: " + f.next)
        }
        result.log?.let { add(row("Log", it)) }
    }.joinToString("\n")

    private fun row(label: String, value: String) = label.padEnd(9) + value

    private fun mark(step: Step) = if (step.outcome == StepOutcome.OK) ok else failed()

    private fun injection(result: RunResult): String {
        val injection = result.injection ?: return caveat("not reported")
        val sidekick = when (injection.outcome) {
            InjectionOutcome.INJECTED -> "$ok injected dta-sidekick $sidekickVersion"
            InjectionOutcome.PROJECT_PROVIDED -> "$ok dta-sidekick declared by the project"
            InjectionOutcome.OFF -> caveat("not added") + " (--no-inject)"
            InjectionOutcome.UNRESOLVED -> caveat("not added") + " (unresolved)"
            InjectionOutcome.UNSUPPORTED -> caveat("not added") + " (unsupported project)"
        }
        val tooling = injection.tooling?.let { t ->
            when (t.outcome) {
                ToolingOutcome.INJECTED -> " · Compose tooling data ${t.version ?: ""} added".replace("  ", " ")
                ToolingOutcome.UNRESOLVED -> " · Compose tooling data not added (${t.reason})"
                else -> ""
            }
        }.orEmpty()
        return sidekick + tooling
    }

    private fun phases(phases: Map<Phase, Long>) = phases.entries.joinToString(" · ") { "${it.key.name.lowercase()} ${seconds(it.value)}" }

    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

    companion object {
        private val JSON = Json { encodeDefaults = true; explicitNulls = true; prettyPrint = false }

        fun json(result: RunResult): String = JSON.encodeToString(RunResult.serializer(), result)
    }
}
