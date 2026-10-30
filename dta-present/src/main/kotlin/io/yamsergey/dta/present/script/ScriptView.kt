package io.yamsergey.dta.present.script

import io.yamsergey.dta.present.Statement
import io.yamsergey.dta.present.StatementKind
import io.yamsergey.dta.present.network.arr
import io.yamsergey.dta.present.network.bool
import io.yamsergey.dta.present.network.long
import io.yamsergey.dta.present.network.obj
import io.yamsergey.dta.present.network.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Why a script was not installed; [line] when the error names one, to open the source there. */
data class InstallError(val message: String, val line: Int?)

data class LogRow(val text: String, val error: Boolean)

/** A page of the script log; [next] continues it, [lost] states lines the ring buffer dropped. */
data class ScriptLog(val lines: List<LogRow>, val next: Long, val lost: Statement?)

/**
 * The Script view (FR-013): the active network script, what it handles, what it did in this
 * process run, and what keeps it from applying — from `network.script.status`.
 */
data class ScriptView(
    val headline: String,
    val handlers: String?,
    val counters: String?,
    val statements: List<Statement>,
    val canClear: Boolean,
) {
    companion object {
        private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val STAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        private val LINE = Regex("""at line (\d+)""")
        private const val NOT_COMPILED = "script does not compile: "

        fun from(status: JsonObject, zone: ZoneId = ZoneId.systemDefault()): ScriptView {
            val active = status.bool("active")
            val statements = buildList {
                if (active && (status["persisted"] as? JsonPrimitive)?.content == "false") {
                    add(Statement(StatementKind.DEGRADED, detail = "not saved on the device: lost when the app restarts"))
                }
                status.str("unavailable")?.let { add(Statement(StatementKind.DEGRADED, detail = it)) }
            }
            if (!active) return ScriptView("No network script active", null, null, statements, canClear = false)
            val since = status.long("installedAtMs")?.let { " since " + CLOCK.format(Instant.ofEpochMilli(it).atZone(zone)) }.orEmpty()
            val handlers = status.arr("handlers").map { (it as JsonPrimitive).content }.takeIf { it.isNotEmpty() }?.joinToString(", ")
            val errors = status.long("errors") ?: 0
            val counters = "${status.long("invocations") ?: 0} calls · ${status.long("changes") ?: 0} changed · $errors " + if (errors == 1L) "error" else "errors"
            return ScriptView("Script ${status.str("id")} active$since", handlers, counters, statements, canClear = true)
        }

        /** From the error `network.script.set` answered with. */
        fun installError(message: String): InstallError =
            InstallError(message.removePrefix(NOT_COMPILED), LINE.find(message)?.groupValues?.get(1)?.toIntOrNull())

        fun log(page: JsonObject, zone: ZoneId = ZoneId.systemDefault()): ScriptLog {
            val rows = page.arr("lines").map { element ->
                val line = element as JsonObject
                val time = STAMP.format(Instant.ofEpochMilli(line.long("timeMs") ?: 0).atZone(zone))
                val text = listOfNotNull(time, line.str("handler") ?: "-", line.str("entryId"), line.str("message")).joinToString("  ")
                LogRow(text, error = line.str("level") == "error")
            }
            val lost = page.long("skipped")?.takeIf { it > 0 }?.let { Statement(StatementKind.EVICTED, detail = "$it lines lost to the log buffer") }
            return ScriptLog(rows, page.long("next") ?: 0, lost)
        }
    }
}
