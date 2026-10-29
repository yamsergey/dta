package io.yamsergey.dta.cli

import io.yamsergey.dta.cli.format.Formatters
import io.yamsergey.dta.client.DaemonCallException
import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.client.DaemonGoneException
import io.yamsergey.dta.client.DaemonLauncher
import io.yamsergey.dta.client.DaemonStartException
import io.yamsergey.dta.client.VersionConflictException
import io.yamsergey.dta.protocol.ops.AppRule
import io.yamsergey.dta.protocol.ops.ClientErrorCodes
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.ops.DeviceRule
import io.yamsergey.dta.protocol.ops.OperationSpec
import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.protocol.ops.SelectionStatement
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.PrintStream

/** Process exit statuses (contract cli.md). */
object ExitCodes {
    const val OK = 0
    const val INTERNAL = 1
    const val USAGE = 2
    const val NOT_AVAILABLE = 3
    const val VERSION_CONFLICT = 4

    fun of(code: Int): Int = when (code) {
        ClientErrorCodes.NO_DEVICE, ClientErrorCodes.SEVERAL_DEVICES, ClientErrorCodes.NO_APP,
        ClientErrorCodes.NOT_FOUND, ClientErrorCodes.NOT_AVAILABLE -> NOT_AVAILABLE
        io.yamsergey.dta.protocol.rpc.ErrorCodes.INVALID_PARAMS -> USAGE
        io.yamsergey.dta.protocol.rpc.ErrorCodes.VERSION_INCOMPATIBLE -> VERSION_CONFLICT
        else -> INTERNAL
    }
}

/**
 * Runs one CLI operation against the daemon: connect (start/replace as needed), call, print.
 * Human mode prints the selection and lifecycle statements on stderr and the formatted
 * result on stdout; `--json` prints exactly the `Selected` document on stdout.
 */
class Runner(
    private val connect: suspend () -> DaemonLauncher.Connected = { DaemonLauncher().ensure(ClientKind.CLI) },
    /** The running daemon, any version, never started (for `daemon stop`). */
    private val existing: suspend () -> DaemonClient? = { DaemonLauncher().existing(ClientKind.CLI) },
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
    private val formatters: (Globals) -> Formatters = Cli::formatters,
) {
    fun run(spec: OperationSpec, params: JsonObject, globals: Globals): Int = runBlocking {
        val full = JsonObject(params + listOfNotNull(globals.device?.let { "device" to JsonPrimitive(it) }, globals.app?.let { "app" to JsonPrimitive(it) }))
        var client: DaemonClient? = null
        try {
            if (spec.name == "daemon.stop") {
                val running = existing() ?: run {
                    out.println("No DTA daemon is running.")
                    return@runBlocking ExitCodes.OK
                }
                client = running
                running.call(spec.name, full)
                out.println(if (globals.json) """{"result":{"stopping":true}}""" else "The DTA daemon ${running.daemon.identity.display} is stopping.")
                return@runBlocking ExitCodes.OK
            }
            val connected = connect()
            client = connected.client
            connected.statements.forEach { err.println(it) }
            if (spec.streaming) return@runBlocking watch(spec, connected.client, full, globals)
            val selected = connected.client.call(spec.name, full)
            if (globals.json) {
                out.println(DtaJson.encodeToString(Selected.serializer(), selected))
            } else {
                selection(selected.selection)?.let(err::println)
                out.println(formatters(globals).format(spec.name, selected.result))
            }
            ExitCodes.OK
        } catch (e: DaemonCallException) {
            err.println(e.message)
            ExitCodes.of(e.code)
        } catch (e: VersionConflictException) {
            err.println(e.message)
            ExitCodes.VERSION_CONFLICT
        } catch (e: DaemonStartException) {
            err.println(e.message)
            ExitCodes.INTERNAL
        } catch (e: DaemonGoneException) {
            err.println(e.message)
            ExitCodes.INTERNAL
        } finally {
            client?.let { runCatching { it.close() } }
        }
    }

    private suspend fun watch(spec: OperationSpec, client: DaemonClient, params: JsonObject, globals: Globals): Int {
        val formatters = formatters(globals)
        val apps = spec.name == "apps.watch"
        val events = if (apps) client.appEvents(params) else client.networkEvents(params)
        return try {
            events.collect { data ->
                val selection = data["selection"]
                when {
                    // The apps running at the start are data: JSON mode prints them as the first line.
                    selection != null && globals.json && apps -> out.println(LINE.encodeToString(JsonElement.serializer(), selection))
                    selection != null -> if (!globals.json) {
                        val selected = DtaJson.decodeFromJsonElement(Selected.serializer(), selection)
                        selection(selected.selection)?.let(err::println)
                        if (apps) {
                            err.println("Watching app connections; Ctrl-C to stop.")
                            formatters.appsSnapshot(selected.result).takeIf { it.isNotEmpty() }?.let(out::println)
                        } else {
                            err.println("Watching network traffic; Ctrl-C to stop.")
                        }
                    }
                    globals.json -> out.println(LINE.encodeToString(JsonElement.serializer(), data))
                    apps -> out.println(formatters.appEventLine(data))
                    else -> out.println(formatters.watchLine(data))
                }
                out.flush()
            }
            ExitCodes.OK
        } catch (e: DaemonGoneException) {
            err.println("Watch ended: ${e.message}")
            ExitCodes.NOT_AVAILABLE
        } catch (e: DaemonCallException) {
            err.println(e.message)
            ExitCodes.of(e.code)
        }
    }

    companion object {
        private val LINE = Json { prettyPrint = false }

        /** "device emulator-5554 (only device) · app com.example (foreground)" when anything was chosen automatically. */
        fun selection(s: SelectionStatement?): String? {
            s ?: return null
            if (s.deviceRule == DeviceRule.NAMED && s.appRule != AppRule.FOREGROUND && s.note == null) return null
            val device = "device ${s.device}" + if (s.deviceRule == DeviceRule.ONLY_DEVICE) " (only device)" else ""
            val app = s.app?.let { " · app $it" + if (s.appRule == AppRule.FOREGROUND) " (foreground)" else "" } ?: ""
            return device + app + (s.note?.let { " · $it" } ?: "")
        }
    }
}
