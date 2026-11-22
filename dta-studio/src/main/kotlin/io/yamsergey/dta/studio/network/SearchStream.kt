package io.yamsergey.dta.studio.network

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.protocol.network.FoundEvent
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger

/**
 * Feature 071 (FR-006, FR-010, research R11): the Network tab's streamed search. Rows come as the daemon
 * finds them; credit goes back as they are taken, until the tab holds [maxHeld] rows — then the search
 * waits for the list to be scrolled to its oldest row ([more]). [stop] ends it on the daemon too.
 */
internal class SearchStream(
    private val client: DaemonClient,
    private val scope: CoroutineScope,
    private val params: JsonObject,
    private val onEvent: (FoundEvent) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val window: Int = WINDOW,
    private val maxHeld: Int = MAX_HELD,
) {
    private val held = AtomicInteger()
    @Volatile private var stream: String? = null
    @Volatile private var owed = 0
    private var job: Job? = null

    /** True while credit is held back because the tab holds [maxHeld] rows. */
    val waitingForViewer: Boolean get() = owed > 0

    fun start() {
        job = scope.launch {
            try {
                client.find(params, window, grant = { e ->
                    val n = (e["items"] as? JsonArray)?.size ?: 0
                    // Past what the tab holds, the credit waits for the person to scroll to the end.
                    if (held.addAndGet(n) < maxHeld) n else { owed += n; 0 }
                }).collect { e ->
                    val started = e["selection"] as? JsonObject
                    if (started != null) {
                        stream = ((started["result"] as? JsonObject)?.get("stream") as? JsonPrimitive)?.content
                        return@collect
                    }
                    onEvent(DtaJson.decodeFromJsonElement(FoundEvent.serializer(), e))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    /** The list was scrolled to its oldest row: the search goes on for another [window] rows. */
    fun more() {
        val id = stream ?: return
        val n = maxOf(owed, window)
        owed = 0
        held.addAndGet(-n)
        client.findMore(id, n)
    }

    /** Stops the search (the daemon's walk too); nothing it finds afterwards is shown. */
    fun stop() {
        job?.cancel()
    }

    companion object {
        const val WINDOW = 500
        /** Rows the tab holds before the search waits for it (memory; research R11). */
        const val MAX_HELD = 20_000
    }
}
