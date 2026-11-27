package io.yamsergey.dta.studio.timeline

import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.studio.ContextController
import io.yamsergey.dta.studio.DaemonSession
import io.yamsergey.dta.testing.TestDaemon
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** D-115 for the Timeline tab: a replaced subscription's late cleanup must not clear the new one's watching (see StaleReloadTest). */
class StaleTimelineReloadTest {
    /** While [armed], holds every block of a job that is already cancelled until [release]. */
    private class HoldCancelled(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
        @Volatile var armed = false
        private val held = java.util.concurrent.CopyOnWriteArrayList<Pair<CoroutineContext, Runnable>>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (armed && context[Job]?.isCancelled == true) held += context to block else inner.dispatch(context, block)
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
    private val timeline = TimelineController(context, scope)

    @AfterTest
    fun tearDown() { scope.cancel(); daemon.close() }

    @Test
    fun `the cleanup of a replaced subscription never clears the new one's watching (D-115)`() = runBlocking<Unit> {
        session.start()
        timeline.setActive(true)
        withTimeout(8_000) { timeline.state.first { it.watching } }
        // The chosen app changes and changes back: the subscription is replaced; the old job's cleanup is held back.
        hold.armed = true
        context.chooseApp("com.example.other")
        withTimeout(5_000) { while (hold.heldCount == 0) delay(5) }
        context.chooseApp("com.example.shop")
        withTimeout(8_000) { timeline.state.first { it.app == "com.example.shop" && it.watching } }
        assertTrue(hold.release().await(5, TimeUnit.SECONDS), "the old job's cleanup ran")
        assertTrue(timeline.state.value.watching, "the new subscription still watches after the old one's cleanup")
    }
}
