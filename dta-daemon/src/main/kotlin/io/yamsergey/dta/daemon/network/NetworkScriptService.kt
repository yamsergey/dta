package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.connection.AppConnection
import io.yamsergey.dta.protocol.network.ScriptClearResult
import io.yamsergey.dta.protocol.network.ScriptLogPage
import io.yamsergey.dta.protocol.network.ScriptStatus
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Daemon side of the network script (feature 005, contract §7). */
class NetworkScriptService(private val connection: suspend () -> AppConnection) {

    suspend fun set(source: String): ScriptStatus =
        DtaJson.decodeFromJsonElement(
            ScriptStatus.serializer(),
            connection().request("network_script_set", buildJsonObject { put("source", source) }),
        )

    suspend fun clear(): Boolean =
        DtaJson.decodeFromJsonElement(ScriptClearResult.serializer(), connection().request("network_script_clear")).cleared

    suspend fun status(): ScriptStatus =
        DtaJson.decodeFromJsonElement(ScriptStatus.serializer(), connection().request("network_script_status"))

    suspend fun logs(since: Long = 0): ScriptLogPage =
        DtaJson.decodeFromJsonElement(
            ScriptLogPage.serializer(),
            connection().request("network_script_logs", buildJsonObject { put("since", since) }),
        )
}
