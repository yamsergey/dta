package io.yamsergey.dta.cli

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val runner = Runner()
    val code = Cli(runner = runner::run, mcp = { io.yamsergey.dta.mcp.McpStdioServer.run() }).run(args)
    exitProcess(code)
}
