package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.network.Initiator
import io.yamsergey.dta.protocol.network.InitiatorSite
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.sidekick.network.store.NetworkStore
import io.yamsergey.dta.sidekick.network.store.StorageDir
import io.yamsergey.dta.sidekick.network.store.StoreBounds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SC-008 "no silent degradation" for network: each of the ten network codes (eight from
 * feature 004, two from 005) is produced by real logic, and none appears on a healthy page. `network-redaction-withheld`
 * is produced in the daemon (NetworkServiceTest, binary body under redaction).
 */
class NetworkDegradationAuditTest {

    @TempDir
    lateinit var dir: File

    private var now = 1_000L
    private val withStack = Initiator("main", InitiatorSite.CALL, frames = emptyList())
    private val available = CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE)

    private fun codes(page: NetworkPage) = page.degraded.orEmpty().map { it.reason }

    private fun <T> withStore(
        root: File? = File(dir, "s${now++}"),
        bounds: StoreBounds = StoreBounds(),
        queueCapacity: Int = 10_000,
        startWriter: Boolean = true,
        block: (NetworkStore, NetworkRecorder) -> T,
    ): T {
        val store = NetworkStore(root?.let { StorageDir(it) }, pid = 1, clock = { now++ }, queueCapacity = queueCapacity, startWriter = startWriter, bounds = bounds)
        return try {
            block(store, NetworkRecorder(store, clock = { now }) { _, _ -> })
        } finally {
            store.close()
        }
    }

    private fun completeGet(recorder: NetworkRecorder, initiator: Initiator = withStack, bodyBytes: Int = 4): String {
        val id = recorder.httpStarted("okhttp3", "GET", "http://h/${now++}", emptyList(), initiator)
        recorder.httpResponse(id, 200, emptyList(), null)
        recorder.httpResponseBody(id, "text/plain", null, null).apply { accept(ByteArray(bodyBytes) { 'a'.code.toByte() }, 0, bodyBytes); finish(endOfStream = true) }
        return id
    }

    @Test
    fun `a healthy page carries no network code`() = withStore { store, recorder ->
        completeGet(recorder)
        assertEquals(emptyList(), codes(NetworkMethods(store) { available }.list(null)))
    }

    @Test
    fun `hooks-unavailable and library-unavailable come from the capability`() = withStore { store, _ ->
        val unavailable = NetworkCapability.status("requires API 28+ (device is API 26)", emptyList(), store.bounds)
        assertTrue(DegradationCodes.NETWORK_HOOKS_UNAVAILABLE in codes(NetworkMethods(store) { unavailable }.list(null)))
        val degraded = NetworkCapability.status(null, listOf(LibraryState("okhttp3", present = true, installFailure = "boom")), store.bounds)
        assertTrue(DegradationCodes.NETWORK_LIBRARY_UNAVAILABLE in codes(NetworkMethods(store) { degraded }.list(null)))
    }

    @Test
    fun `history-evicted comes from eviction`() = withStore(bounds = StoreBounds(historyBytes = 6_000, segmentBytes = 500)) { store, recorder ->
        repeat(6) { completeGet(recorder, bodyBytes = 1_000); store.flush() }
        store.evictIfNeeded()
        val oldest = NetworkMethods(store) { available }.list(Json.parseToJsonElement("""{"order":"oldest-first","limit":1}""").jsonObject)
        assertTrue(DegradationCodes.NETWORK_HISTORY_EVICTED in codes(oldest))
    }

    @Test
    fun `history-not-persisted comes from a store without storage`() = withStore(root = null) { store, recorder ->
        completeGet(recorder)
        assertTrue(DegradationCodes.NETWORK_HISTORY_NOT_PERSISTED in codes(NetworkMethods(store) { available }.list(null)))
    }

    @Test
    fun `events-dropped comes from writer overflow`() = withStore(queueCapacity = 1, startWriter = false) { store, recorder ->
        repeat(3) { completeGet(recorder) }
        assertTrue(DegradationCodes.NETWORK_EVENTS_DROPPED in codes(NetworkMethods(store) { available }.list(null)))
    }

    @Test
    fun `initiator-missing comes from a failed stack capture`() = withStore { store, recorder ->
        val missing = Initiators(stackOf = { error("no stack") }).capture(InitiatorSite.CALL)
        completeGet(recorder, initiator = missing)
        assertTrue(DegradationCodes.NETWORK_INITIATOR_MISSING in codes(NetworkMethods(store) { available }.list(null)))
    }

    @Test
    fun `body-incomplete comes from a body the app stopped reading`() = withStore { store, recorder ->
        val id = recorder.httpStarted("okhttp3", "GET", "http://h/partial", emptyList(), withStack)
        recorder.httpResponse(id, 200, emptyList(), null)
        recorder.httpResponseBody(id, "text/plain", null, 100).apply { accept(ByteArray(10), 0, 10); finish(endOfStream = false) }
        val methods = NetworkMethods(store) { available }
        assertTrue(DegradationCodes.NETWORK_BODY_INCOMPLETE in codes(methods.list(null)))
        assertTrue(DegradationCodes.NETWORK_BODY_INCOMPLETE in methods.entry(Json.parseToJsonElement("""{"id":"$id"}""").jsonObject).degraded!!.map { it.reason })
    }

    // ---- feature 005 ----

    private fun withScripts(runtime: io.yamsergey.dta.sidekick.network.script.ScriptRuntime) = withStore { store, recorder ->
        completeGet(recorder)
        codes(NetworkMethods(store, { runtime.degradations() }) { available }.list(null))
    }

    @Test
    fun `a persisted active script raises no script code`() {
        val runtime = io.yamsergey.dta.sidekick.network.script.ScriptRuntime()
        runtime.install("function onRequest(r) {}")
        runtime.markPersisted(true)
        assertEquals(emptyList(), withScripts(runtime))
    }

    @Test
    fun `script-not-persisted comes from a script storage could not keep`() {
        val runtime = io.yamsergey.dta.sidekick.network.script.ScriptRuntime()
        runtime.install("function onRequest(r) {}")
        runtime.markPersisted(false)
        assertTrue(DegradationCodes.NETWORK_SCRIPT_NOT_PERSISTED in withScripts(runtime))
    }

    @Test
    fun `script-unavailable comes from a persisted script that cannot apply`() {
        val runtime = io.yamsergey.dta.sidekick.network.script.ScriptRuntime()
        runtime.restore(io.yamsergey.dta.sidekick.network.script.StoredScript("function (", 1), hooksUnavailable = null)
        assertTrue(DegradationCodes.NETWORK_SCRIPT_UNAVAILABLE in withScripts(runtime))
    }
}
