package io.yamsergey.dta.sidekick.network.urlconnection

import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.Initiator
import io.yamsergey.dta.protocol.network.InitiatorSite
import io.yamsergey.dta.sidekick.network.NetworkRecorder
import io.yamsergey.dta.sidekick.network.script.ScriptRuntime
import io.yamsergey.dta.sidekick.network.store.NetworkStore
import io.yamsergey.dta.sidekick.network.store.StorageDir
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D-001 (NET-HTTP-003/004, NET-BODY-012): an HttpURLConnection exchange carries the heads the
 * platform put on the wire, not the app's request properties and the platform's sorted response
 * fields. [Platform] stands in for Android's HttpURLConnection: it adds its own request headers when
 * it sends, reports the heads through [UrlConnectionWire] as the stream hooks do on a device, and
 * answers `getHeaderFields()` sorted with `X-Android-*` pseudo headers.
 */
class UrlConnectionWireTest {

    @TempDir
    lateinit var dir: File

    private val store by lazy { NetworkStore(StorageDir(dir), pid = 7) }
    private val recorder by lazy { NetworkRecorder(store) { _, _ -> } }
    private val initiator = Initiator(thread = "main", site = InitiatorSite.CONNECT, missing = "not captured in this test")

    @AfterEach
    fun close() = store.close()

    /** OkHttp 2's Headers as `toString()` gives them. */
    private class PlatformHeaders(private val headers: List<Header>) {
        override fun toString() = headers.joinToString("") { "${it.name}: ${it.value}\n" }
    }

    private class Platform(url: URL, private val wire: Boolean, private val response: List<Header>, private val body: ByteArray) : HttpURLConnection(url) {
        private val sentBody = ByteArrayOutputStream()
        private var sent = false

        private fun send() {
            if (sent) return
            sent = true
            val app = requestProperties.flatMap { (name, values) -> values.map { Header(name, it) } }
            val added = listOf(Header("User-Agent", "Dalvik/2.1.0"), Header("Host", "${url.host}:${url.port}"), Header("Connection", "Keep-Alive"), Header("Accept-Encoding", "gzip"))
            val length = if (doOutput) listOf(Header("Content-Length", sentBody.size().toString())) else emptyList()
            if (wire) {
                UrlConnectionWire.requestWritten(PlatformHeaders(app + added + length))
                UrlConnectionWire.headersRead(PlatformHeaders(response))
            }
            connected = true
        }

        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream = sentBody
        override fun getInputStream(): InputStream = send().let { ByteArrayInputStream(body) }
        override fun getResponseCode(): Int = send().let { 200 }
        override fun getHeaderFields(): MutableMap<String, MutableList<String>> {
            send()
            val fields = sortedMapOf<String, MutableList<String>>(String.CASE_INSENSITIVE_ORDER)
            (response + listOf(Header("X-Android-Sent-Millis", "1"), Header("X-Android-Received-Millis", "2"), Header("X-Android-Response-Source", "NETWORK 200"), Header("X-Android-Selected-Protocol", "http/1.1")))
                .forEach { fields.getOrPut(it.name) { mutableListOf() } += it.value }
            return fields
        }
        override fun getContentType(): String? = response.firstOrNull { it.name == "Content-Type" }?.value
        override fun getContentLengthLong(): Long = body.size.toLong()
    }

    private val response = listOf(Header("Server", "test"), Header("Date", "today"), Header("Content-Type", "text/plain"), Header("Content-Length", "2"))

    private fun tracked(platform: Platform) = TrackedHttpURLConnection(platform, recorder, initiator, ScriptRuntime()) { error("not reopened") }

    private fun only(): HttpEntry = store.list(limit = 10).entries.filterIsInstance<HttpEntry>().single()

    @Test
    fun `a GET carries the request head the platform wrote and the response head it read, in wire order`() {
        val connection = tracked(Platform(URL("http://127.0.0.1:8099/text"), wire = true, response, "ok".toByteArray()))
        connection.inputStream.use { it.readBytes() }
        connection.disconnect()
        val e = only()
        assertEquals(HttpState.COMPLETE, e.state)
        assertEquals(
            listOf(Header("User-Agent", "Dalvik/2.1.0"), Header("Host", "127.0.0.1:8099"), Header("Connection", "Keep-Alive"), Header("Accept-Encoding", "gzip")),
            e.requestHeaders,
        )
        assertEquals(response, e.responseHeaders)
    }

    @Test
    fun `a POST's request body is declared by the Content-Length the platform sent`() {
        val connection = tracked(Platform(URL("http://127.0.0.1:8099/echo"), wire = true, response, "ok".toByteArray()))
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/plain")
        connection.outputStream.use { it.write("url-body".toByteArray()) }
        assertEquals(200, connection.responseCode)
        connection.inputStream.use { it.readBytes() }
        connection.disconnect()
        val e = only()
        assertEquals("Content-Type", e.requestHeaders!!.first().name, "the app's header first, as written")
        assertEquals(Header("Content-Length", "8"), e.requestHeaders!!.last())
        assertEquals(8L, e.requestBody!!.declaredBytes)
        assertEquals(8L, e.requestBody!!.storedBytes)
    }

    /** The twin: without the platform's stream hooks the fields are what is known, minus what was never on the wire. */
    @Test
    fun `without the stream hooks the response fields lose the pseudo headers`() {
        val connection = tracked(Platform(URL("http://127.0.0.1:8099/text"), wire = false, response, "ok".toByteArray()))
        connection.inputStream.use { it.readBytes() }
        val e = only()
        assertEquals(response.sortedBy { it.name.lowercase() }, e.responseHeaders)
    }

    @Test
    fun `the platform's head text is read line by line, a value keeps its colons`() {
        assertEquals(
            listOf(Header("Host", "h:1"), Header("X-Time", "12:30:00"), Header("Empty", "")),
            UrlConnectionWire.parse("Host: h:1\nX-Time: 12:30:00\nEmpty: \n"),
        )
    }

    /** A head written outside a tracked call (a connection DTA did not wrap) goes nowhere. */
    @Test
    fun `a head written outside a tracked call is ignored`() {
        UrlConnectionWire.requestWritten(PlatformHeaders(listOf(Header("Host", "x"))))
        assertEquals(0, store.list(limit = 10).entries.size)
    }
}
