package io.yamsergey.dta.sidekick.network.okhttp

import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.sidekick.network.Initiators
import io.yamsergey.dta.sidekick.network.NetworkRecorder
import io.yamsergey.dta.sidekick.network.script.ScriptRuntime
import io.yamsergey.dta.sidekick.network.store.NetworkStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import io.yamsergey.dta.sidekick.network.store.StorageDir
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Failures as the app sees them, with real OkHttp against a local server (004 FR-006). */
class OkHttpFailuresTest {

    @TempDir
    lateinit var dir: File

    private val server = MockWebServer().apply { start() }
    private val store by lazy { NetworkStore(StorageDir(dir), pid = 7) }
    private val recorder by lazy { NetworkRecorder(store) { _, _ -> } }
    private val contexts = CallContexts()
    private val initiators = Initiators()
    private val client by lazy {
        OkHttpClient.Builder()
        .addInterceptor(ScriptInterceptor(recorder, contexts, initiators, ScriptRuntime(), null))
        .addNetworkInterceptor(DtaInterceptor(recorder, contexts, initiators))
        .build()
    }

    @AfterEach
    fun close() {
        server.shutdown()
        store.close()
    }

    private fun only(): HttpEntry = store.list(limit = 100).entries.filterIsInstance<HttpEntry>().single()

    /** D-007 (NET-HTTP-022): the library raised an error while the app read the body. */
    @Test
    fun `a response cut mid-body is failed with the library's error and keeps what arrived`() {
        val body = Buffer().write(ByteArray(65_536) { (it % 251).toByte() })
        server.enqueue(MockResponse().setBody(body).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val error = try {
            client.newCall(Request.Builder().url(server.url("/cut")).build()).execute().use { it.body!!.bytes() }
            fail("the cut body must fail the app's read")
        } catch (e: IOException) {
            e
        }
        val e = only()
        assertEquals(HttpState.FAILED, e.state, "the library raised an error: the exchange failed")
        assertTrue(e.failure.orEmpty().contains(error.javaClass.name), "failure '${e.failure}' names the app's ${error.javaClass.name}")
        assertEquals(200, e.status)
        val info = assertNotNull(e.responseBody)
        assertEquals(Completeness.PARTIALLY_OBSERVED, info.completeness)
        assertEquals(65_536L, info.declaredBytes)
        assertTrue(info.storedBytes < 65_536L, "what arrived: ${info.storedBytes}")
        assertTrue(info.completenessReason.orEmpty().contains(error.javaClass.name), "the reason says the read failed: ${info.completenessReason}")
    }

    /** The twin: a body read to its end is complete. */
    @Test
    fun `a body read to its end is complete`() {
        server.enqueue(MockResponse().setBody("hello"))
        client.newCall(Request.Builder().url(server.url("/ok")).build()).execute().use { it.body!!.string() }
        val e = only()
        assertEquals(HttpState.COMPLETE, e.state)
        assertNull(e.failure)
        assertEquals(Completeness.COMPLETE, e.responseBody!!.completeness)
    }
}
