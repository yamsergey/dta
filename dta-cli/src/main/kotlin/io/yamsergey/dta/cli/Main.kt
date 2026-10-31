package io.yamsergey.dta.cli

import io.yamsergey.dta.cli.format.RunFormat
import io.yamsergey.dta.cli.format.Style
import io.yamsergey.dta.protocol.ops.Catalog
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.run.RunEnvironment
import io.yamsergey.dta.run.RunOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val runner = Runner()
    val code = Cli(runner = runner::run, mcp = { io.yamsergey.dta.mcp.McpStdioServer.run() }, run = { runApp(it, runner) }).run(args)
    exitProcess(code)
}

/** `dta run` (feature 010): progress on stderr, the summary (or the JSON document) on stdout. */
private fun runApp(invocation: RunInvocation, runner: Runner): Int {
    val environment = RunEnvironment(ClientKind.CLI)
    val format = RunFormat(Style.detect(invocation.globals.noColor), environment.sidekickVersion)
    val result = runBlocking {
        try {
            environment.session.run(invocation.request) { event ->
                if (!invocation.globals.json) format.progress(event, invocation.verbose)?.let(System.err::println)
            }
        } finally {
            environment.disconnect()
        }
    }
    println(if (invocation.globals.json) RunFormat.json(result) else format.summary(result))
    if (invocation.watch && result.outcome == RunOutcome.CONNECTED) {
        // Hand over to `dta network watch` for the same device and app.
        val watch = Catalog.all.first { it.name == "network.watch" }
        val globals = Globals().apply {
            device = result.device?.serial
            app = result.applicationId
            json = invocation.globals.json
            noColor = invocation.globals.noColor
        }
        return runner.run(watch, JsonObject(emptyMap()), globals)
    }
    return result.exitCode
}
