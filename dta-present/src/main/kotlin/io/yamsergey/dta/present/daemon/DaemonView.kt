package io.yamsergey.dta.present.daemon

import io.yamsergey.dta.present.Degradations
import io.yamsergey.dta.present.Statement
import io.yamsergey.dta.present.StatementKind
import io.yamsergey.dta.present.Statements
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One capability or library of the app (depth 1 = a library under its capability). [advice] is
 * what the gap means and what to do, when its [reason] names a known degradation code.
 */
data class CapabilityRow(val name: String, val status: String, val reason: String?, val depth: Int, val statement: Statement?, val advice: String? = null)

/**
 * The Daemon view (data-model DaemonView, FR-016): the shared daemon's identity and process,
 * the adb it uses, its clients, devices and app connections; and the app's capabilities, each
 * gap with its statement.
 */
data class DaemonView(
    val version: String,
    val process: String,
    val adb: String?,
    val adbProblem: Statement?,
    val clients: List<String>,
    val devices: List<String>,
    val connections: List<String>,
    val sidekick: String?,
    val capabilities: List<CapabilityRow>,
) {
    companion object {
        /** From a `daemon.status` result and, when an app is chosen, its `capability.report`. */
        fun from(status: JsonObject, capability: JsonObject?): DaemonView {
            val identity = status.obj("identity")
            val adb = status.obj("adb")
            val gaps = capability?.let { Statements.forCapability(it).associateBy { s -> s.subject } }.orEmpty()
            val rows = capability?.arr("capabilities").orEmpty().flatMap { c ->
                val cap = c as JsonObject
                listOf(row(cap, 0, gaps)) + cap.arr("libraries").map { row(it as JsonObject, 1, gaps) }
            }
            return DaemonView(
                version = "${identity?.str("version")} (build ${identity?.str("build")})",
                process = "pid ${status.long("pid")} · up ${uptime(status.long("uptimeMs") ?: 0)} · ${status.str("home")}",
                adb = adb?.str("path")?.takeIf { adb.bool("available") },
                adbProblem = if (adb?.bool("available") == true) null else
                    Statement(StatementKind.DEGRADED, word = "adb unavailable", detail = "adb unavailable: ${adb?.str("reason") ?: "no reason given"}"),
                clients = status.arr("clients").map { (it as JsonObject).let { c -> "${c.str("kind")} ${c.str("version")}" } },
                devices = status.arr("devices").map { (it as JsonObject).let { d -> listOfNotNull(d.str("serial"), d.str("state"), d.str("model")).joinToString(" ") } },
                connections = status.arr("connections").map { (it as JsonObject).let { c -> "${c.str("device")} ${c.str("app")}" } },
                sidekick = capability?.let { "sidekick ${it.str("sidekickVersion")} in ${it.str("packageName")}" },
                capabilities = rows,
            )
        }

        private fun row(o: JsonObject, depth: Int, gaps: Map<String?, Statement>): CapabilityRow {
            val name = o.str("name") ?: "?"
            val reason = o.str("reason")
            return CapabilityRow(name, o.str("status") ?: "?", reason, depth, gaps[name], Degradations.explain(Degradations.codeIn(reason)))
        }

        fun uptime(ms: Long): String {
            val s = ms / 1000
            return when {
                s >= 3600 -> "${s / 3600}h ${s % 3600 / 60}m"
                s >= 60 -> "${s / 60}m ${s % 60}s"
                else -> "${s}s"
            }
        }
    }
}

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
private fun JsonObject.arr(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
private fun JsonObject.bool(name: String): Boolean = (this[name] as? JsonPrimitive)?.content == "true"
