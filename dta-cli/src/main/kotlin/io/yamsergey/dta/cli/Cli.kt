package io.yamsergey.dta.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import io.yamsergey.dta.cli.format.Formatters
import io.yamsergey.dta.cli.format.Style
import io.yamsergey.dta.client.VersionInfo
import io.yamsergey.dta.protocol.ops.Catalog
import io.yamsergey.dta.protocol.ops.OperationSpec
import io.yamsergey.dta.protocol.ops.ParamType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.PrintStream
import kotlin.properties.ReadOnlyProperty

/** Options every command shares (`dta -d … -a … --json network list`). */
class Globals {
    var device: String? = null
    var app: String? = null
    var json: Boolean = false
    var noColor: Boolean = false
}

/**
 * The `dta` command tree, generated from the operation [Catalog] (feature 008, FR-011/012):
 * one leaf per operation, one option or argument per parameter. [run] returns the exit code.
 */
class Cli(
    private val runner: (OperationSpec, JsonObject, Globals) -> Int,
    private val mcp: () -> Int,
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
    private val cwd: File = File(System.getProperty("user.dir")),
) {
    fun run(args: Array<String>): Int {
        val root = buildTree()
        return try {
            root.parse(args)
            root.exit
        } catch (e: PrintHelpMessage) {
            out.println(e.context?.command?.getFormattedHelp(null) ?: "")
            0
        } catch (e: PrintMessage) {
            out.println(e.message)
            e.statusCode
        } catch (e: UsageError) {
            err.println((e.context?.command ?: root).getFormattedHelp(e))
            ExitCodes.USAGE
        } catch (e: CliktError) {
            err.println(e.message)
            ExitCodes.USAGE
        }
    }

    /** All leaf command paths (for the parity test). */
    fun leafPaths(): List<List<String>> {
        fun walk(cmd: CoreCliktCommand, path: List<String>): List<List<String>> {
            val subs = cmd.registeredSubcommands()
            return if (subs.isEmpty()) listOf(path) else subs.flatMap { walk(it as CoreCliktCommand, path + it.commandName) }
        }
        return walk(buildTree(), emptyList())
    }

    /** Option and argument names of the leaf at [path]. */
    fun parameterNames(path: List<String>): List<String> {
        var cmd: CoreCliktCommand = buildTree()
        path.forEach { name -> cmd = cmd.registeredSubcommands().first { it.commandName == name } as CoreCliktCommand }
        return cmd.registeredOptions().flatMap { it.names } + cmd.registeredArguments().map { it.name }
    }

    private fun buildTree(): Root {
        val root = Root()
        val groups = mutableMapOf<List<String>, CoreCliktCommand>(emptyList<String>() to root)
        Catalog.all.forEach { spec ->
            val path = spec.cliPath
            for (i in 1 until path.size) {
                val prefix = path.take(i)
                if (prefix !in groups) {
                    val group = Group(prefix.last())
                    groups.getValue(prefix.dropLast(1)).subcommands(group)
                    groups[prefix] = group
                }
            }
            groups.getValue(path.dropLast(1)).subcommands(Leaf(spec))
        }
        root.subcommands(McpCommand())
        return root
    }

    private inner class Root : CoreCliktCommand(name = "dta") {
        var exit = 0
        private val globals by findOrSetObject { Globals() }
        private val device by option("-d", "--device", help = "Device serial; omit when one device is connected")
        private val app by option("-a", "--app", help = "Package name; omit to use the foreground app")
        private val json by option("--json", help = "Print the full structured result (one JSON document)").flag()
        private val noColor by option("--no-color", help = "No colour or Unicode glyphs").flag()
        private val version by option("--version", help = "Print the version and exit").flag()

        override val invokeWithoutSubcommand = true
        override fun help(context: Context) =
            "DTA: inspect a running Android app — its screen and its network traffic. Every command talks to the shared DTA daemon, started on demand."

        override fun run() {
            if (version) {
                val v = VersionInfo.current()
                throw PrintMessage("dta ${v.display}" + (v.missingReason?.let { " — $it" } ?: ""), 0)
            }
            if (currentContext.invokedSubcommand == null) throw PrintHelpMessage(currentContext)
            globals.device = device
            globals.app = app
            globals.json = json
            globals.noColor = noColor
        }
    }

    private inner class Group(name: String) : CoreCliktCommand(name = name) {
        override fun help(context: Context) = when (commandName) {
            "network" -> "Captured network traffic: HTTP and WebSocket in one list"
            "script" -> "The app's network script: delay, rewrite, answer or drop traffic"
            "schema" -> "Protobuf schema sets used to decode binary bodies"
            "mapping" -> "Which protobuf message type decodes which bodies"
            "daemon" -> "The shared DTA daemon process"
            else -> ""
        }
        override fun run() = Unit
    }

    private inner class McpCommand : CoreCliktCommand(name = "mcp") {
        override fun help(context: Context) = "Serve DTA to an agent over MCP (stdio)"
        override fun run() {
            (currentContext.findRoot().command as Root).exit = mcp()
        }
    }

    private inner class Leaf(private val spec: OperationSpec) : CoreCliktCommand(name = spec.cliPath.last()) {
        private val globals by requireObject<Globals>()
        private val dummy: Any? = null
        private val readers = mutableListOf<Pair<String, () -> JsonElement?>>()

        init {
            spec.params.forEach { p -> readers += p.name to register(p) }
        }

        override fun help(context: Context) = spec.description

        private fun <T> holder(delegate: ReadOnlyProperty<CoreCliktCommand, T>): () -> T = { delegate.getValue(this, ::dummy) }

        private fun register(p: io.yamsergey.dta.protocol.ops.ParamSpec): () -> JsonElement? {
            val longName = "--" + p.name.replace(Regex("([A-Z])")) { "-" + it.value.lowercase() }
            val names = listOfNotNull(p.cliShort, longName).toTypedArray()
            val absolute: (String) -> String = { if (p.path) File(it).let { f -> if (f.isAbsolute) f else File(cwd, it) }.normalize().path else it }
            if (p.positional) {
                return if (p.type == ParamType.STRING_LIST) {
                    val v = holder(argument(p.name.uppercase(), help = p.description).multiple().provideDelegate(this, ::dummy))
                    ({ v().takeIf { it.isNotEmpty() }?.let { list -> JsonArray(list.map { JsonPrimitive(absolute(it)) }) } })
                } else if (p.required) {
                    val v = holder(argument(p.name.uppercase(), help = p.description).provideDelegate(this, ::dummy))
                    ({ JsonPrimitive(absolute(v())) })
                } else {
                    val v = holder(argument(p.name.uppercase(), help = p.description).optional().provideDelegate(this, ::dummy))
                    ({ v()?.let { JsonPrimitive(absolute(it)) } })
                }
            }
            return when (p.type) {
                ParamType.BOOLEAN -> {
                    val v = holder(option(*names, help = p.description).flag().provideDelegate(this, ::dummy))
                    ({ if (v()) JsonPrimitive(true) else null })
                }
                ParamType.INT -> holder(option(*names, help = p.description).int().provideDelegate(this, ::dummy)).let { v -> { v()?.let(::JsonPrimitive) } }
                ParamType.LONG -> holder(option(*names, help = p.description).long().provideDelegate(this, ::dummy)).let { v -> { v()?.let(::JsonPrimitive) } }
                ParamType.ENUM -> holder(option(*names, help = p.description).choice(*p.enumValues.toTypedArray()).provideDelegate(this, ::dummy)).let { v -> { v()?.let(::JsonPrimitive) } }
                ParamType.ENUM_LIST -> holder(
                    option(*names, help = p.description + " Repeat or comma-separate.").choice(*p.enumValues.toTypedArray()).split(",").multiple().provideDelegate(this, ::dummy),
                ).let { v -> { v().flatten().takeIf { it.isNotEmpty() }?.let { list -> JsonArray(list.map(::JsonPrimitive)) } } }
                ParamType.STRING_LIST -> holder(option(*names, help = p.description).multiple().provideDelegate(this, ::dummy))
                    .let { v -> { v().takeIf { it.isNotEmpty() }?.let { list -> JsonArray(list.map { JsonPrimitive(absolute(it)) }) } } }
                ParamType.STRING_MAP -> holder(option(*names, help = p.description + " As path=text; repeatable.").multiple().provideDelegate(this, ::dummy))
                    .let { v -> { v().takeIf { it.isNotEmpty() }?.let { list -> JsonObject(list.associate { it.substringBefore('=') to JsonPrimitive(it.substringAfter('=')) }) } } }
                ParamType.STRING -> holder(option(*names, help = p.description).provideDelegate(this, ::dummy)).let { v -> { v()?.let { JsonPrimitive(absolute(it)) } } }
            }
        }

        override fun run() {
            val params = JsonObject(readers.mapNotNull { (name, read) -> read()?.let { name to it } }.toMap())
            (currentContext.findRoot().command as Root).exit = runner(spec, params, globals)
        }
    }

    companion object {
        fun formatters(globals: Globals) = Formatters(Style.detect(globals.noColor))
    }
}
