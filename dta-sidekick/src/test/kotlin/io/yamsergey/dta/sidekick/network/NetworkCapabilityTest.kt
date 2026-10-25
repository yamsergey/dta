package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.LibraryStatus
import io.yamsergey.dta.protocol.network.NetworkBounds
import io.yamsergey.dta.protocol.rpc.CapabilityStatus.Status
import io.yamsergey.dta.sidekick.network.store.StoreBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkCapabilityTest {

    private val bounds = StoreBounds(historyBytes = 100, bodyBytes = 10)

    @Test
    fun `hooks unavailable makes the capability unavailable with the reason`() {
        val status = NetworkCapability.status(
            attachFailure = "requires API 28+ (device is API 26)",
            libraries = listOf(LibraryState("okhttp3", present = true, installFailure = null)),
            bounds = bounds,
        )
        assertEquals(Status.UNAVAILABLE, status.status)
        assertTrue("API 28" in status.reason!!)
        assertEquals(Status.UNAVAILABLE, status.libraries!!.single().status)
    }

    @Test
    fun `absent libraries are unavailable without degrading, present ones available`() {
        val status = NetworkCapability.status(
            attachFailure = null,
            libraries = listOf(
                LibraryState("okhttp3", present = true, installFailure = null),
                LibraryState("nv-websocket", present = false, installFailure = null),
            ),
            bounds = bounds,
        )
        assertEquals(Status.AVAILABLE, status.status)
        assertEquals(
            listOf(
                LibraryStatus("okhttp3", Status.AVAILABLE),
                LibraryStatus("nv-websocket", Status.UNAVAILABLE, "library not present in app"),
            ),
            status.libraries,
        )
        assertEquals(NetworkBounds(historyBytes = 100, bodyBytes = 10), status.bounds)
    }

    @Test
    fun `a present library whose hook failed degrades the capability`() {
        val status = NetworkCapability.status(
            attachFailure = null,
            libraries = listOf(
                LibraryState("okhttp3", present = true, installFailure = "RetransformClasses: JVMTI_ERROR_INTERNAL"),
                LibraryState("urlconnection", present = true, installFailure = null),
            ),
            bounds = bounds,
        )
        assertEquals(Status.DEGRADED, status.status)
        assertTrue("okhttp3" in status.reason!!)
        assertEquals(
            LibraryStatus("okhttp3", Status.UNAVAILABLE, "RetransformClasses: JVMTI_ERROR_INTERNAL"),
            status.libraries!!.first(),
        )
    }

    @Test
    fun `an active script is part of the capability`() {
        val status = NetworkCapability.status(null, emptyList(), bounds, script = io.yamsergey.dta.protocol.network.ScriptSummary("abc", 5))
        assertEquals("abc", status.script!!.id)
    }

    @Test
    fun `a script that cannot apply degrades the capability`() {
        val status = NetworkCapability.status(null, emptyList(), bounds, script = io.yamsergey.dta.protocol.network.ScriptSummary("abc", 5, unavailable = "no longer compiles"))
        assertEquals(Status.DEGRADED, status.status)
        assertTrue("no longer compiles" in status.reason!!)
    }
}
