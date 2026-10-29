package io.yamsergey.dta.studio

import io.yamsergey.dta.client.DaemonClient
import io.yamsergey.dta.client.DaemonStartException
import io.yamsergey.dta.client.VersionConflictException
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.ops.DaemonMethods
import io.yamsergey.dta.protocol.ops.VersionIdentity
import io.yamsergey.dta.testing.TestDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DaemonSessionTest {
    private val daemon = TestDaemon()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        daemon.close()
    }

    private suspend fun <T> DaemonSession.awaitState(match: (DaemonSession.State) -> T?): T =
        withTimeout(5_000) { state.first { match(it) != null }.let(match)!! }

    @Test
    fun `connects as the IDE and says which daemon`() = runBlocking<Unit> {
        val session = DaemonSession(connect = { daemon.connect(ClientKind.IDE) }, scope = scope).also { it.start() }
        val connected = session.awaitState { it as? DaemonSession.State.Connected }
        assertEquals("0.1.0 (build 2026-10-28T10:00:00Z)", connected.version)
    }

    @Test
    fun `a newer daemon is a conflict with its message, a failed start a failure that can be retried`() = runBlocking<Unit> {
        val attempts = AtomicInteger()
        val session = DaemonSession(connect = {
            when (attempts.incrementAndGet()) {
                1 -> throw VersionConflictException(VersionIdentity("0.11.0", "b", 1), VersionIdentity("0.10.0", "a", 1))
                2 -> throw DaemonStartException("The DTA daemon did not start; see /home/.dta/daemon/daemon.log")
                else -> daemon.connect(ClientKind.IDE)
            }
        }, scope = scope)
        session.start()
        val conflict = session.awaitState { it as? DaemonSession.State.Conflict }
        assertTrue("0.11.0" in conflict.message, conflict.message)
        session.retry()
        val failed = session.awaitState { it as? DaemonSession.State.Failed }
        assertTrue("daemon.log" in failed.message)
        session.retry()
        session.awaitState<DaemonSession.State.Connected> { it as? DaemonSession.State.Connected }
    }

    @Test
    fun `replaced by a newer daemon, it says so and connects again`() = runBlocking<Unit> {
        val connects = AtomicInteger()
        val session = DaemonSession(connect = { connects.incrementAndGet(); daemon.connect(ClientKind.IDE) }, scope = scope).also { it.start() }
        session.awaitState<DaemonSession.State.Connected> { it as? DaemonSession.State.Connected }
        val other: DaemonClient = daemon.connect(ClientKind.CLI).client
        other.request(DaemonMethods.REPLACE, DaemonClient.replaceParams(VersionIdentity("0.1.0", "2026-12-31T00:00:00Z", 1)))
        val again = session.awaitState { (it as? DaemonSession.State.Connected)?.takeIf { connects.get() == 2 } }
        assertTrue(again.statements.any { "replaced by 0.1.0 (build 2026-12-31T00:00:00Z)" in it }, "${again.statements}")
    }

    @Test
    fun `a stopped daemon is stated and not restarted behind the person's back`() = runBlocking<Unit> {
        val connects = AtomicInteger()
        val session = DaemonSession(connect = { connects.incrementAndGet(); daemon.connect(ClientKind.IDE) }, scope = scope).also { it.start() }
        session.awaitState<DaemonSession.State.Connected> { it as? DaemonSession.State.Connected }
        daemon.server.stop("stopped by a client (cli)")
        val stopped = session.awaitState { it as? DaemonSession.State.Failed }
        assertTrue("stopped by a client" in stopped.message, stopped.message)
        assertEquals("Start", stopped.action)
        assertEquals(1, connects.get())
    }

    @Test
    fun `dispose closes the connection and leaves the daemon running`() = runBlocking<Unit> {
        val session = DaemonSession(connect = { daemon.connect(ClientKind.IDE) }, scope = scope).also { it.start() }
        val connected = session.awaitState { it as? DaemonSession.State.Connected }
        session.dispose()
        withTimeout(5_000) { while (connected.client.goneReason == null) kotlinx.coroutines.delay(20) }
        val probe = daemon.connect(ClientKind.CLI).client
        assertIs<kotlinx.serialization.json.JsonObject>(probe.call("daemon.status").result)
    }
}
