package io.yamsergey.dta.studio.agents

import io.yamsergey.dta.protocol.ops.ClientInfo
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.studio.DaemonSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Feature 073: the daemon's connected clients for the Agents tab, from its `daemon.clients` subscription over the IDE's
 * connection. Null when no daemon is connected (not running, starting, lost); the subscription follows each new connection.
 */
class AgentClientsFeed(session: StateFlow<DaemonSession.State>, scope: CoroutineScope) {
    private val mutable = MutableStateFlow<List<ClientInfo>?>(null)
    val clients: StateFlow<List<ClientInfo>?> = mutable

    init {
        scope.launch {
            session.collectLatest { state ->
                val client = (state as? DaemonSession.State.Connected)?.client
                if (client == null) { mutable.value = null; return@collectLatest }
                try {
                    client.clientEvents().collect { element ->
                        val rows = element["clients"] ?: DtaJson.decodeFromJsonElement(Selected.serializer(), element["selection"]!!).result.jsonObject["clients"]
                        if (rows != null) mutable.value = DtaJson.decodeFromJsonElement(ListSerializer(ClientInfo.serializer()), rows)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.value = null
                }
            }
        }
    }
}
