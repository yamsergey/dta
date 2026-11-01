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
import com.github.ajalt.clikt.parameters.options.default
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
import io.yamsergey.dta.run.RunMode
import io.yamsergey.dta.run.RunRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.PrintStream
import kotlin.properties.ReadOnlyProperty
import kotlin.time.Duration.Companion.seconds

/** `dta run` as parsed: the request plus how to show it (feature 010, contracts/run.md). */
data class RunInvocation(val request: RunRequest, val globals: Globals, val watch: Boolean, val verbose: Boolean)

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
    /** `dta run`, a host-local command beside the catalog (010 research R7). */
    private val run: (RunInvocation) -> Int = { ExitCodes.USAGE },
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
            val own = if (subs.isEmpty() || cmd is Leaf) listOf(path) else emptyList()
            return own + subs.flatMap { walk(it as CoreCliktCommand, path + it.commandName) }
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
            // A leaf may also hold further commands (`dta apps` and `dta apps watch`).
            val leaf = Leaf(spec)
            groups.getValue(path.dropLast(1)).subcommands(leaf)
            groups[path] = leaf
        }
        root.subcommands(McpCommand(), RunCommand())
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

    private inner class RunCommand : CoreCliktCommand(name = "run") {
        private val globals by requireObject<Globals>()
        private val project by option("--project", help = "The Android project's root (the folder with gradlew); default the working folder")
        private val module by option("--module", help = "The app module's Gradle path, e.g. :app; needed when there are several")
        private val variant by option("--variant", help = "A debuggable variant, e.g. freeDebug; default debug")
        private val activity by option("--activity", help = "The activity to start; a leading . is relative to the application id; default the launcher activity")
        private val buildOnly by option("--build-only", help = "Only build, with DTA injected: no device, no install, no launch").flag()
        private val noLaunch by option("--no-launch", help = "Install, but do not launch").flag()
        private val noInject by option("--no-inject", help = "Build the project as it is, without adding DTA").flag()
        private val noTooling by option("--no-tooling", help = "Do not add Compose's tooling data library").flag()
        private val reinstall by option("--reinstall", help = "Uninstall the app first; this deletes its data").flag()
        private val timeout by option("--timeout", help = "Seconds to wait for DTA to connect (default 30)").int().default(30)
        private val watch by option("--watch", help = "After DTA connects, show the network traffic live until interrupted").flag()
        private val verbose by option("--verbose", help = "Show Gradle's output").flag()
        private val gradleArgs by argument("GRADLE_ARGS", help = "Arguments for Gradle, after --").multiple()

        override fun help(context: Context) =
            "Build, install and launch the app with DTA, then wait until DTA is connected. Run it in the Android project's root; nothing in the project is changed."

        override fun run() {
            if (buildOnly && (noLaunch || reinstall || watch)) throw UsageError("--build-only cannot be combined with --no-launch, --reinstall or --watch")
            if (noLaunch && watch) throw UsageError("--watch needs the app launched; drop --no-launch")
            if (timeout <= 0) throw UsageError("--timeout must be a positive number of seconds")
            val request = RunRequest(
                project = project?.let { File(it).let { f -> if (f.isAbsolute) f else File(cwd, it) }.normalize() } ?: cwd,
                module = module,
                variant = variant,
                activity = activity,
                device = globals.device,
                mode = when {
                    buildOnly -> RunMode.BUILD_ONLY
                    noLaunch -> RunMode.NO_LAUNCH
                    else -> RunMode.RUN
                },
                inject = !noInject,
                tooling = !noTooling,
                reinstall = reinstall,
                timeout = timeout.seconds,
                gradleArgs = gradleArgs,
            )
            (currentContext.findRoot().command as Root).exit = this@Cli.run(RunInvocation(request, globals, watch, verbose))
        }
    }

    private inner class Leaf(private val spec: OperationSpec) : CoreCliktCommand(name = spec.cliPath.last()) {
        private val globals by requireObject<Globals>()
        private val dummy: Any? = null
        private val readers = mutableListOf<Pair<String, () -> JsonElement?>>()

        init {
            spec.params.filterNot { it.cliHidden }.forEach { p -> readers += p.name to register(p) }
        }

        override fun help(context: Context) = spec.description
        override val invokeWithoutSubcommand = true

        private fun <T> holder(delegate: ReadOnlyProperty<CoreCliktCommand, T>): () -> T = { delegate.getValue(this, ::dummy) }

        private fun register(p: io.yamsergey.dta.protocol.ops.ParamSpec): () -> JsonElement? {
            val longName = "--" + (p.cliName ?: p.name.replace(Regex("([A-Z])")) { "-" + it.value.lowercase() })
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
            if (currentContext.invokedSubcommand != null) return
            val params = JsonObject(readers.mapNotNull { (name, read) -> read()?.let { name to it } }.toMap())
            (currentContext.findRoot().command as Root).exit = runner(spec, params, globals)
        }
    }

    companion object {
        fun formatters(globals: Globals) = Formatters(Style.detect(globals.noColor))
    }
}
