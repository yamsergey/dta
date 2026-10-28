package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.protocol.ops.AppList
import io.yamsergey.dta.protocol.ops.AppRule
import io.yamsergey.dta.protocol.ops.ClientErrorCodes
import io.yamsergey.dta.protocol.ops.DeviceInfo
import io.yamsergey.dta.protocol.ops.DeviceRule
import io.yamsergey.dta.protocol.ops.Scope
import io.yamsergey.dta.protocol.ops.SelectionStatement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Chooses the device and app of an operation — the ONE place it happens for every
 * client (FR-010). Every automatic choice is stated in the result.
 */
class Selector(
    private val devices: () -> List<DeviceInfo>,
    private val apps: (serial: String) -> AppList,
) {
    data class Resolution(val serial: String?, val pkg: String?, val statement: SelectionStatement?)

    fun resolve(scope: Scope, device: String?, app: String?): Resolution {
        if (scope == Scope.NONE) return Resolution(null, null, null)
        val (serial, deviceRule) = device(device)
        if (scope == Scope.DEVICE) return Resolution(serial, null, SelectionStatement(serial, deviceRule))

        if (app != null) return Resolution(serial, app, SelectionStatement(serial, deviceRule, app, AppRule.NAMED))

        val list = apps(serial)
        val foreground = list.foreground
        if (foreground != null && list.apps.any { it.packageName == foreground }) {
            return Resolution(serial, foreground, SelectionStatement(serial, deviceRule, foreground, AppRule.FOREGROUND))
        }
        if (scope == Scope.APP_OR_FALLBACK) {
            val note = if (foreground == null) {
                "no foreground app detected; the system fallback captures the screen"
            } else {
                "foreground app $foreground has no DTA inside; the system fallback captures it"
            }
            return Resolution(serial, null, SelectionStatement(serial, deviceRule, note = note))
        }
        val withDta = list.apps.map { it.packageName }
        throw ClientException(
            ClientErrorCodes.NO_APP,
            buildString {
                append(if (foreground == null) "No foreground app detected" else "The foreground app $foreground has no DTA inside")
                if (withDta.isEmpty()) {
                    append("; no running app on $serial has DTA. Build the app with DTA and start it.")
                } else {
                    append("; name an app with DTA: ${withDta.joinToString(", ")}.")
                }
            },
            buildJsonObject {
                foreground?.let { put("foreground", it) }
                putJsonArray("appsWithDta") { withDta.forEach { add(JsonPrimitive(it)) } }
            },
        )
    }

    private fun device(named: String?): Pair<String, DeviceRule> {
        val all = devices()
        if (named != null) {
            val match = all.firstOrNull { it.serial == named }
                ?: throw ClientException(
                    ClientErrorCodes.NOT_FOUND,
                    "Device $named is not connected; connected: ${all.joinToString(", ") { it.serial }.ifEmpty { "none" }}.",
                    buildJsonObject { put("what", "device"); put("id", named) },
                )
            if (match.state != "online") {
                throw ClientException(
                    ClientErrorCodes.NOT_AVAILABLE,
                    "Device $named is ${match.state}" +
                        if (match.state == "unauthorized") "; accept the USB debugging prompt on the device." else ".",
                    buildJsonObject { put("capability", "device"); put("reason", match.state) },
                )
            }
            return named to DeviceRule.NAMED
        }
        val online = all.filter { it.state == "online" }
        return when (online.size) {
            0 -> throw ClientException(
                ClientErrorCodes.NO_DEVICE,
                if (all.isEmpty()) {
                    "No device connected. Start an emulator or connect a device with USB debugging on."
                } else {
                    "No device online: ${all.joinToString(", ") { "${it.serial} (${it.state})" }}."
                },
            )
            1 -> online.single().serial to DeviceRule.ONLY_DEVICE
            else -> throw ClientException(
                ClientErrorCodes.SEVERAL_DEVICES,
                "Several devices are connected; choose one with the device option: ${online.joinToString(", ") { it.serial }}.",
                buildJsonObject { putJsonArray("devices") { online.forEach { add(JsonPrimitive(it.serial)) } } },
            )
        }
    }
}
