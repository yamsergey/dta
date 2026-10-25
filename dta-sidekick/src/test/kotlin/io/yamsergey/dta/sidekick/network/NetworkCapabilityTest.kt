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

    @Test
    fun `a present library capture does not cover degrades the capability, saying why`() {
        val status = NetworkCapability.status(
            attachFailure = null,
            libraries = listOf(
                LibraryState("okhttp3", present = true, installFailure = null),
                LibraryState("grpc", present = true, installFailure = null, unsupported = NetworkCapability.GRPC_UNSUPPORTED),
            ),
            bounds = bounds,
        )
        assertEquals(Status.DEGRADED, status.status)
        assertTrue("grpc not captured" in status.reason!!, status.reason)
        assertEquals(LibraryStatus("grpc", Status.UNAVAILABLE, NetworkCapability.GRPC_UNSUPPORTED), status.libraries!![1])
        assertTrue("present in app" in NetworkCapability.GRPC_UNSUPPORTED)
    }

    @Test
    fun `an absent gRPC is listed but does not degrade`() {
        val status = NetworkCapability.status(
            attachFailure = null,
            libraries = listOf(
                LibraryState("okhttp3", present = true, installFailure = null),
                NetworkCapability.probeGrpc(javaClass.classLoader),
            ),
            bounds = bounds,
        )
        assertEquals(Status.AVAILABLE, status.status)
        assertEquals(LibraryStatus("grpc", Status.UNAVAILABLE, NetworkCapability.ABSENT), status.libraries!![1])
    }

    @Test
    fun `the gRPC probe finds the channel class on the app loader`() {
        val loader = object : ClassLoader(null) {
            override fun findClass(name: String): Class<*> =
                if (name == "io.grpc.ManagedChannel") Any::class.java else throw ClassNotFoundException(name)
        }
        val state = NetworkCapability.probeGrpc(loader)
        assertTrue(state.present)
        assertEquals(NetworkCapability.GRPC_UNSUPPORTED, state.unsupported)
    }
}
