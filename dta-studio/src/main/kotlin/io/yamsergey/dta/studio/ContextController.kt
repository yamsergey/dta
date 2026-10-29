package io.yamsergey.dta.studio

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.present.context.ContextEvent
import io.yamsergey.dta.present.context.ContextState
import io.yamsergey.dta.protocol.ops.AppConnectionEvent
import io.yamsergey.dta.protocol.ops.AppList
import io.yamsergey.dta.protocol.ops.DeviceInfo
import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * One project's context (FR-001, FR-002): the daemon session's state, the device and app, and
 * the app's connection as `apps.watch` reports it. Devices and apps are asked for when the
 * daemon connects, when the person opens a picker, and when a watched app connects; nothing is
 * polled (constitution II).
 */
class ContextController(
    private val session: StateFlow<DaemonSession.State>,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(ContextState())
    val state: StateFlow<ContextState> = mutable.asStateFlow()

    /** The connected client, for the views' operations. */
    @Volatile var client: DaemonClient? = null
        private set

    private val watchingFlow = MutableStateFlow(false)

    /** True while the chosen device's app connections are being followed. */
    val watching: StateFlow<Boolean> = watchingFlow.asStateFlow()

    private var watch: Job? = null

    init {
        scope.launch {
            session.collect { s ->
                when (s) {
                    is DaemonSession.State.Connected -> {
                        client = s.client
                        apply(ContextEvent.DaemonConnected(s.version))
                        refreshDevices()
                    }
                    is DaemonSession.State.Replaced -> { client = null; apply(ContextEvent.DaemonReplaced(s.by)) }
                    is DaemonSession.State.Conflict -> { client = null; apply(ContextEvent.DaemonConflict(s.message)) }
                    is DaemonSession.State.Failed -> { client = null; apply(ContextEvent.DaemonFailed(s.message)) }
                    DaemonSession.State.Starting -> Unit
                }
            }
        }
    }

    fun apply(event: ContextEvent) {
        val before = mutable.value
        mutable.update { it.reduce(event) }
        if (mutable.value.device != before.device) followDevice()
    }

    /** Devices as the daemon sees them now (also when the person opens the device picker). */
    fun refreshDevices() {
        val c = client ?: return
        scope.launch {
            runCatching { c.call("devices.list") }.onSuccess { selected ->
                apply(ContextEvent.Devices(DtaJson.decodeFromJsonElement(ListSerializer(DeviceInfo.serializer()), selected.result)))
                refreshApps()
            }
        }
    }

    fun refreshApps() {
        val c = client ?: return
        val device = mutable.value.device ?: return
        scope.launch {
            runCatching { c.call("apps.list", buildJsonObject { put("device", device) }) }
                .onSuccess { apply(ContextEvent.Apps(DtaJson.decodeFromJsonElement(AppList.serializer(), it.result))) }
        }
    }

    fun chooseDevice(serial: String) = apply(ContextEvent.DeviceChosen(serial)).also { refreshApps() }

    fun chooseApp(pkg: String) {
        apply(ContextEvent.AppChosen(pkg))
        refreshApps()
    }

    private fun followDevice() {
        watch?.cancel()
        watchingFlow.value = false
        val c = client ?: return
        val device = mutable.value.device ?: return
        watch = scope.launch {
            runCatching {
                c.appEvents(buildJsonObject { put("device", device) }).collect { data -> onAppEvent(data) }
            }
            watchingFlow.value = false
        }
    }

    private fun onAppEvent(data: JsonObject) {
        val selection = data["selection"]
        if (selection != null) {
            val selected = DtaJson.decodeFromJsonElement(Selected.serializer(), selection)
            selected.result.jsonObject["apps"]?.jsonArray?.forEach { apply(ContextEvent.Apps(DtaJson.decodeFromJsonElement(AppList.serializer(), it))) }
            watchingFlow.value = true
            return
        }
        val event = DtaJson.decodeFromJsonElement(AppConnectionEvent.serializer(), JsonObject(data - "subscription"))
        apply(ContextEvent.AppConnection(event))
        // An app that just connected may be new to the app list (a fresh install, a second app).
        if (event.state == "connected") refreshApps()
    }
}
