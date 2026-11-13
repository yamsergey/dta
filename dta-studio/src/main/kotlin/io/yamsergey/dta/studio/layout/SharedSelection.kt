package io.yamsergey.dta.studio.layout

import io.yamsergey.dta.present.context.Connection
import io.yamsergey.dta.protocol.layout.LayoutSelection
import io.yamsergey.dta.protocol.layout.SelectedElement
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.studio.ContextController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Feature 055: the app's layout selection shared through the daemon. The Layout tree's selection
 * is published ([publish], off the UI thread); a change made by someone else (an agent, the CLI,
 * the app ending) arrives in [fromOthers] for the tree to select and reveal.
 */
class SharedSelection(private val context: ContextController, private val scope: CoroutineScope) {
    private val current = MutableStateFlow<LayoutSelection?>(null)
    /** The selection as the daemon has it now; null when no app with DTA is connected. */
    val state: StateFlow<LayoutSelection?> = current.asStateFlow()
    private val others = MutableSharedFlow<LayoutSelection>(extraBufferCapacity = 8)
    val fromOthers: SharedFlow<LayoutSelection> = others.asSharedFlow()
    @Volatile private var lastSent: List<SelectedElement>? = null

    init {
        scope.launch {
            context.state
                .map { s -> Triple(s.device, s.app.takeIf { s.connection == Connection.CONNECTED }, s.generation) }
                .distinctUntilChanged()
                .collectLatest { (device, app, _) ->
                    current.value = null
                    lastSent = null
                    val client = context.client
                    if (device == null || app == null || client == null) return@collectLatest
                    runCatching {
                        client.selectionChanges(params(device, app)).collect { data ->
                            val sel = (data["selection"] as? JsonObject)?.let { DtaJson.decodeFromJsonElement(LayoutSelection.serializer(), it) } ?: return@collect
                            current.value = sel
                            if (sel.changedBy != PLUGIN && sel.revision > 0) others.emit(sel)
                        }
                    }
                }
        }
    }

    /** The tree's selection as the shared one: [elements] empty clears it. Repeats are not sent. */
    fun publish(elements: List<SelectedElement>) {
        if (elements == lastSent) return
        lastSent = elements
        val st = context.state.value
        val client = context.client ?: return
        val device = st.device ?: return
        val app = st.app ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val p = params(device, app).toMutableMap()
                if (elements.isEmpty()) p["mode"] = JsonPrimitive("clear")
                else p["elements"] = JsonPrimitive(DtaJson.encodeToString(ListSerializer(SelectedElement.serializer()), elements.map { it.copy(now = null) }))
                client.call("layout.select", JsonObject(p))
            }
        }
    }

    /** What the tree now shows came from the daemon: not to be sent back. */
    fun applied(elements: List<SelectedElement>) { lastSent = elements }

    private fun params(device: String, app: String) = JsonObject(mapOf("device" to JsonPrimitive(device), "app" to JsonPrimitive(app)))

    private companion object { const val PLUGIN = "plugin" }
}
