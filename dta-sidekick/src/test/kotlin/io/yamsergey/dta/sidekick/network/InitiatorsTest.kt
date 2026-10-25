package io.yamsergey.dta.sidekick.network

import com.example.app.AppCode
import io.yamsergey.dta.protocol.network.InitiatorSite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InitiatorsTest {

    private val initiators = Initiators()

    private fun scheduleFromHere(call: Any) = AppCode.scheduleFromHere { initiators.remember(call, InitiatorSite.ENQUEUE) }

    @Test
    fun `a remembered call yields the stack taken where it was scheduled`() {
        val call = Any()
        scheduleFromHere(call)
        var fromWorker: io.yamsergey.dta.protocol.network.Initiator? = null
        Thread({ fromWorker = initiators.forCall(call, InitiatorSite.CALL) }, "OkHttp Dispatcher").apply { start(); join() }
        val initiator = fromWorker!!
        assertEquals(InitiatorSite.ENQUEUE, initiator.site)
        assertEquals(Thread.currentThread().name, initiator.thread)
        val first = initiator.frames!![initiator.firstAppFrame!!]
        assertEquals(AppCode::class.java.name to "scheduleFromHere", first.className to first.method)
    }

    @Test
    fun `an unknown call falls back to the current stack with the given site`() {
        val initiator = AppCode.callFromHere { initiators.forCall(Any(), InitiatorSite.CALL) }
        assertEquals(InitiatorSite.CALL, initiator.site)
        assertEquals("callFromHere", initiator.frames!![initiator.firstAppFrame!!].method)
    }

    @Test
    fun `capture states why when the stack cannot be taken`() {
        val broken = Initiators(stackOf = { error("no stack") })
        val initiator = broken.capture(InitiatorSite.SEND)
        assertNull(initiator.frames)
        assertTrue("no stack" in initiator.missing!!)
    }

    @Test
    fun `remembered calls are held weakly`() {
        var call: Any? = Any()
        initiators.remember(call!!, InitiatorSite.ENQUEUE)
        val probe = java.lang.ref.WeakReference(call)
        @Suppress("UNUSED_VALUE")
        call = null
        repeat(20) {
            System.gc()
            if (probe.get() == null) return
            Thread.sleep(20)
        }
        assertNull(probe.get(), "call must be collectable")
    }

    @Test
    fun `a null call captures the current stack`() {
        assertNotNull(initiators.forCall(null, InitiatorSite.CONNECT).frames)
    }
}
