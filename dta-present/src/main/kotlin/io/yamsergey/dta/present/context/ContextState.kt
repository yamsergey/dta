package io.yamsergey.dta.present.context

import io.yamsergey.dta.present.StatementKind
import io.yamsergey.dta.protocol.ops.AppConnectionEvent
import io.yamsergey.dta.protocol.ops.AppList
import io.yamsergey.dta.protocol.ops.AppRule
import io.yamsergey.dta.protocol.ops.DeviceInfo
import io.yamsergey.dta.protocol.ops.DeviceRule
import io.yamsergey.dta.protocol.ops.SelectionStatement

/** The app's link to the daemon, as the daemon last reported it (`app.connection`, `apps.list`). */
enum class Connection { UNKNOWN, CONNECTED, LOST, REFUSED, ENDED, NO_DTA }

enum class DaemonPhase { STARTING, CONNECTED, REPLACED, CONFLICT, FAILED }

/** The connection as the context line shows it: a dot and a word in [token]'s colour, the reason as its tooltip. */
data class ConnectionLine(val text: String, val token: String, val reason: String?)

data class DaemonInfo(val phase: DaemonPhase = DaemonPhase.STARTING, val version: String? = null, val message: String? = null)

/**
 * What injection did for the app's module in the last IDE build (contracts/injection.md); OFF
 * when turned off, NOT_RUN when a build ran without the init script's report.
 */
enum class InjectionOutcome { OFF, INJECTED, PROJECT_PROVIDED, UNRESOLVED, UNSUPPORTED, NOT_RUN }

/** A whole-view statement: cause and next step, in the statement's colours, with an optional action. */
data class Banner(val kind: StatementKind, val text: String, val action: String? = null)

/** What changes the context (data-model ContextState). */
sealed interface ContextEvent {
    data class DaemonConnected(val version: String) : ContextEvent
    data class DaemonReplaced(val by: String) : ContextEvent
    data class DaemonConflict(val message: String) : ContextEvent
    data class DaemonFailed(val message: String) : ContextEvent
    data class Devices(val devices: List<DeviceInfo>) : ContextEvent
    data class Apps(val apps: AppList) : ContextEvent
    data class DeviceChosen(val serial: String) : ContextEvent
    data class AppChosen(val pkg: String) : ContextEvent
    /** The selection an operation result reported. */
    data class Selected(val selection: SelectionStatement) : ContextEvent
    data class AppConnection(val event: AppConnectionEvent) : ContextEvent
    /** [outcome] null: injection is on and no build has reported yet. */
    data class Injection(val outcome: InjectionOutcome?, val reason: String?) : ContextEvent
}

/**
 * The context line every view shares: device · app · connection (what DTA chose automatically
 * is not marked: the pickers show it), and the banner for whatever applies to the whole view. Liveness comes only from
 * what the daemon reports (constitution II); [generation] grows when device or app changes, so
 * views drop their results and query again.
 */
data class ContextState(
    val device: String? = null,
    val deviceModel: String? = null,
    val deviceRule: DeviceRule? = null,
    val devices: List<DeviceInfo>? = null,
    val app: String? = null,
    val appRule: AppRule? = null,
    val connection: Connection = Connection.UNKNOWN,
    val connectionReason: String? = null,
    val daemon: DaemonInfo = DaemonInfo(),
    val injection: InjectionOutcome? = null,
    val injectionReason: String? = null,
    val generation: Int = 0,
) {
    fun reduce(event: ContextEvent): ContextState = when (event) {
        is ContextEvent.DaemonConnected -> copy(daemon = DaemonInfo(DaemonPhase.CONNECTED, event.version))
        is ContextEvent.DaemonReplaced -> copy(daemon = daemon.copy(phase = DaemonPhase.REPLACED, message = event.by))
        is ContextEvent.DaemonConflict -> copy(daemon = daemon.copy(phase = DaemonPhase.CONFLICT, message = event.message))
        is ContextEvent.DaemonFailed -> copy(daemon = daemon.copy(phase = DaemonPhase.FAILED, message = event.message))
        is ContextEvent.Devices -> devicesChanged(event.devices)
        is ContextEvent.Apps -> appsChanged(event.apps)
        is ContextEvent.DeviceChosen -> if (event.serial == device) copy(deviceRule = DeviceRule.NAMED) else
            switched(device = event.serial, deviceRule = DeviceRule.NAMED, app = null, appRule = null)
                .copy(deviceModel = devices?.firstOrNull { it.serial == event.serial }?.model)
        is ContextEvent.AppChosen -> if (event.pkg == app) copy(appRule = AppRule.NAMED) else
            switched(device, deviceRule, event.pkg, AppRule.NAMED)
        is ContextEvent.Selected -> {
            val s = event.selection
            val next = if (s.device != device || s.app != app) switched(s.device, s.deviceRule, s.app, s.appRule) else this
            next.copy(deviceRule = s.deviceRule, appRule = s.appRule)
        }
        is ContextEvent.AppConnection -> {
            val e = event.event
            if (e.device != device || e.app != app) this else copy(connection = connectionOf(e.state), connectionReason = e.reason)
        }
        is ContextEvent.Injection -> copy(injection = event.outcome, injectionReason = event.reason)
    }

    private fun devicesChanged(list: List<DeviceInfo>): ContextState {
        val online = list.filter { it.state == "online" }
        val kept = online.firstOrNull { it.serial == device }
        val next = when {
            kept != null -> this
            online.size == 1 -> switched(online.single().serial, DeviceRule.ONLY_DEVICE, null, null)
            else -> switched(null, null, null, null)
        }
        val chosen = online.firstOrNull { it.serial == next.device }
        return next.copy(devices = list, deviceModel = chosen?.model)
    }

    private fun appsChanged(apps: AppList): ContextState {
        if (apps.device != device) return this
        val withDta = apps.apps.map { it.packageName }.toSet()
        val next = if (appRule == AppRule.NAMED && app != null) this else {
            val foreground = apps.foreground
            if (foreground == app) copy(appRule = foreground?.let { AppRule.FOREGROUND }) else switched(device, deviceRule, foreground, foreground?.let { AppRule.FOREGROUND })
        }
        val hasDta = next.app != null && next.app in withDta
        return when {
            next.app != null && !hasDta -> next.copy(connection = Connection.NO_DTA, connectionReason = null)
            next.connection == Connection.NO_DTA -> next.copy(connection = Connection.UNKNOWN)
            else -> next
        }
    }

    /** A different device or app: everything that described the old one goes. */
    private fun switched(device: String?, deviceRule: DeviceRule?, app: String?, appRule: AppRule?) = copy(
        device = device, deviceRule = deviceRule, app = app, appRule = appRule,
        connection = Connection.UNKNOWN, connectionReason = null, generation = generation + 1,
    )

    /** Injection in one line (Daemon tab): on or off, and what the last build did. */
    val injectionLine: String
        get() = when (injection) {
            InjectionOutcome.OFF -> "off"
            null -> "on — the last build has not been reported yet"
            InjectionOutcome.NOT_RUN -> "on — the last build did not run the DTA init script"
            else -> "on — " + injection.name.lowercase().replace('_', '-') + (injectionReason?.let { ": $it" } ?: "")
        }

    /** The app's connection at a glance; null until the daemon reports one. */
    val connectionLine: ConnectionLine?
        get() {
            val (word, token) = when (connection) {
                Connection.UNKNOWN -> return null
                Connection.CONNECTED -> "connected" to "live"
                Connection.LOST -> "lost" to "status-server"
                Connection.REFUSED -> "refused" to "status-server"
                Connection.ENDED -> "ended" to "ink-muted"
                Connection.NO_DTA -> "no DTA" to "caveat"
            }
            return ConnectionLine("● $word", token, connectionReason)
        }

    val banner: Banner?
        get() = when (daemon.phase) {
            DaemonPhase.FAILED -> Banner(StatementKind.FAILED, daemon.message ?: "The DTA daemon did not start.", action = "Retry")
            DaemonPhase.CONFLICT -> Banner(StatementKind.FAILED, daemon.message ?: "The running DTA daemon is a different version.", action = "Retry")
            DaemonPhase.REPLACED -> Banner(StatementKind.DEGRADED, "The DTA daemon was replaced by ${daemon.message ?: "a newer version"}; reconnecting.")
            DaemonPhase.STARTING -> Banner(StatementKind.PENDING, "Starting the DTA daemon…")
            DaemonPhase.CONNECTED -> contextBanner()
        }

    private fun contextBanner(): Banner? {
        val online = devices?.filter { it.state == "online" }.orEmpty()
        return when {
            devices != null && online.isEmpty() ->
                Banner(StatementKind.DEGRADED, "No device connected. Start an emulator or connect a device with USB debugging.")
            device == null && online.size > 1 ->
                Banner(StatementKind.DEGRADED, "${online.size} devices are connected. Choose a device in the context bar.")
            device == null || app == null -> null
            connection == Connection.NO_DTA -> Banner(StatementKind.DEGRADED, noDta(app))
            connection == Connection.REFUSED -> Banner(
                StatementKind.FAILED,
                "$app refused the connection: ${connectionReason ?: "no reason given"}. Build the app with the same DTA release as this plugin.",
            )
            connection == Connection.LOST -> Banner(
                StatementKind.DEGRADED,
                "The connection to $app was lost: ${connectionReason ?: "no reason given"}. DTA reconnects when the app answers again.",
            )
            connection == Connection.ENDED -> Banner(StatementKind.PROCESS_ENDED, "$app ended. Run it again; traffic captured so far stays here.")
            else -> null
        }
    }

    private fun noDta(app: String): String = "$app has no DTA inside. " + when (injection) {
        InjectionOutcome.OFF -> "Injection is off: turn it on in Settings › Tools › DTA and run the debug build again."
        InjectionOutcome.UNRESOLVED -> "Injection could not add DTA: ${injectionReason ?: "the library did not resolve"}. The build ran without it."
        InjectionOutcome.UNSUPPORTED -> "Injection could not handle this project: ${injectionReason ?: "no reason given"}. Add io.github.yamsergey:dta-sidekick to debugImplementation."
        InjectionOutcome.NOT_RUN -> "The last build did not run the DTA init script, so it could not add DTA. Run the debug build from Android Studio, or add io.github.yamsergey:dta-sidekick to debugImplementation."
        InjectionOutcome.PROJECT_PROVIDED, InjectionOutcome.INJECTED -> "The last debug build had DTA; run that build on this device."
        null -> "Run the app's debug build from Android Studio to add DTA, or add io.github.yamsergey:dta-sidekick to debugImplementation."
    }

    private fun connectionOf(state: String) = when (state) {
        "connected" -> Connection.CONNECTED
        "lost" -> Connection.LOST
        "refused" -> Connection.REFUSED
        "ended" -> Connection.ENDED
        else -> Connection.UNKNOWN
    }
}
