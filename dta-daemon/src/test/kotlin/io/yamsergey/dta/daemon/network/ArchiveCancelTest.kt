package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.daemon.store.DeviceIdentity
import io.yamsergey.dta.daemon.store.HostStore
import io.yamsergey.dta.protocol.network.ArchiveScope
import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.NetworkFilter
import io.yamsergey.dta.protocol.network.NetworkQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** D-114 (071 FR-008, SC-003): a cancel reaches into a runaway regex over an archived body, for a list and a search. */
class ArchiveCancelTest {
    @TempDir
    lateinit var dir: File

    private val stores = mutableListOf<HostStore>()

    @AfterEach
    fun close() = stores.forEach { it.close() }

    private suspend fun archivedHost(): HostIndex {
        val app = FakeJournalApp()
        val store = HostStore(dir).also { stores += it }
        val host = HostIndex(store, DeviceIdentity("dev1"), "io.example") { app }
        app.runId = "r1-1"
        val big = "x".repeat(1 shl 20)
        val e = HttpEntry("r1-1:1", "r1-1", 1L, "okhttp3", method = "GET", url = "https://api/big", state = HttpState.COMPLETE, status = 200,
            responseBody = BodyInfo(BodyPart.RESPONSE, big.length.toLong(), completeness = Completeness.COMPLETE, contentType = "text/plain"))
        app.record(e)
        app.bodies[e.id to BodyPart.RESPONSE] = big.toByteArray()
        host.list(NetworkQuery(filter = NetworkFilter(bodyContains = "zzz"))) // copies the body to this computer
        app.reset("j2", "cleared")
        app.runId = "r2-1"
        host.list(NetworkQuery())
        return host
    }

    private fun inRegex() = Thread.getAllStackTraces().values.any { st -> st.any { it.className.startsWith("java.util.regex.") } }

    private fun cancelled(call: suspend (HostIndex) -> Unit) = runBlocking<Unit> {
        val host = archivedHost()
        val running = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async { runCatching { call(host) } }
        // The walk is inside the match (a thread in java.util.regex) before the cancel is sent.
        val until = System.nanoTime() + 10_000_000_000L
        while (!inRegex() && System.nanoTime() < until) delay(5)
        assertTrue(inRegex(), "no walk started")
        running.cancel()
        val ended = withTimeoutOrNull(2_000) { running.join(); true }
        assertNotNull(ended, "the walk is still inside the match 2 s after the cancel")
    }

    private val runaway = NetworkQuery(q = "body:/(x+x+)+y/", archiveScope = ArchiveScope(all = true))

    @Test
    fun `a cancelled search of the archive ends inside a runaway match`() = cancelled { it.search(runaway) }

    @Test
    fun `a cancelled list of the archive ends inside a runaway match`() = cancelled { it.list(runaway) }
}
