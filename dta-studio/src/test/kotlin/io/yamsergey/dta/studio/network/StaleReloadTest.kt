package io.yamsergey.dta.studio.network

import io.yamsergey.dta.protocol.network.FindEnd
import io.yamsergey.dta.protocol.network.FindState
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.studio.ContextController
import io.yamsergey.dta.studio.DaemonSession
import io.yamsergey.dta.testing.TestDaemon
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D-115: a reload cancels the old subscription's job, and that job's cleanup ran `watching = false` whenever the pool got
 * to it: when it ran after the new subscription said it watches, the tab stayed "not watching" for good (the flake of
 * SearchStreamControllerTest: "the live list watches after the step"). Here the cleanup of the old job is held back by a
 * dispatcher until the new subscription watches, so the order that the pool left to chance is fixed.
 */
class StaleReloadTest {
    /** While [armed], holds every block of a job that is already cancelled (the replaced subscription's cleanup) until [release]. */
    private class HoldCancelled(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
        @Volatile var armed = false
        private val held = java.util.concurrent.CopyOnWriteArrayList<Pair<CoroutineContext, Runnable>>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (armed && context[kotlinx.coroutines.Job]?.isCancelled == true) held += context to block
            else inner.dispatch(context, block)
        }
        val heldCount get() = held.size
        fun release(): CountDownLatch {
            armed = false
            val all = held.toList(); held.clear()
            val done = CountDownLatch(all.size)
            all.forEach { (c, b) -> inner.dispatch(c, Runnable { try { b.run() } finally { done.countDown() } }) }
            return done
        }
    }

    private val hold = HoldCancelled(Dispatchers.IO)
    private val daemon = TestDaemon()
    private val scope = CoroutineScope(SupervisorJob() + hold)
    private val session = DaemonSession(connect = { daemon.connect(ClientKind.IDE) }, scope = scope)
    private val context = ContextController(session.state, scope)
    private val network = NetworkController(context, scope)

    @AfterTest
    fun tearDown() { scope.cancel(); daemon.close() }

    @Test
    fun `the cleanup of a replaced subscription never clears the new one's watching (D-115)`() = runBlocking<Unit> {
        daemon.ops.findWalk = { _, _ -> { _, _ -> FindEnd(null, null) } }
        session.start()
        withTimeout(5_000) { network.state.first { it.watching } }
        hold.armed = true
        network.setQuery("token")
        withTimeout(5_000) { while (hold.heldCount == 0) kotlinx.coroutines.delay(5) }
        withTimeout(5_000) { network.state.first { it.find == FindState.DONE && it.watching } }
        assertTrue(hold.release().await(5, TimeUnit.SECONDS), "the old job's cleanup ran")
        val s = network.state.value
        assertNull(s.error)
        assertTrue(s.watching, "the new subscription still watches after the old one's cleanup")
    }
}
