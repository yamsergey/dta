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
                LibraryStatus("nv-websocket", Status.UNAVAILABLE, NetworkCapability.ABSENT),
            ),
            status.libraries,
        )
        assertEquals(NetworkBounds(historyBytes = 100, bodyBytes = 10), status.bounds)
    }

    @Test
    fun `a present library with an event not captured degrades the capability, naming the event`() {
        val connect = HookSignature("connect", "()V")
        val fail = HookSignature("failWebSocket", "(Ljava/lang/Exception;)V")
        val status = NetworkCapability.status(
            attachFailure = null,
            libraries = listOf(
                LibraryState(
                    "okhttp3-ws", present = true, installFailure = null,
                    events = listOf(
                        EventResult.of("connections", connect, io.yamsergey.dta.sidekick.hooks.Hooks.Install.Installed),
                        EventResult.of("failures", fail, io.yamsergey.dta.sidekick.hooks.Hooks.Install.MethodAbsent("okhttp3.internal.ws.RealWebSocket", fail.method, fail.descriptor)),
                    ),
                ),
                LibraryState("urlconnection", present = true, installFailure = null),
            ),
            bounds = bounds,
        )
        assertEquals(Status.DEGRADED, status.status)
        assertTrue(status.reason!!.startsWith("okhttp3-ws: not captured: failures ("), status.reason)
        val ws = status.libraries!!.first()
        assertEquals(Status.DEGRADED, ws.status)
        assertEquals(listOf("failures"), ws.notCaptured)
        assertEquals(Status.AVAILABLE, status.libraries!![1].status)
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
    fun `a present library capture does not cover is listed with the reason, without degrading`() {
        val status = NetworkCapability.status(
            attachFailure = null,
            libraries = listOf(
                LibraryState("okhttp3", present = true, installFailure = null),
                LibraryState("grpc", present = true, installFailure = null, unsupported = NetworkCapability.GRPC_UNSUPPORTED),
            ),
            bounds = bounds,
        )
        assertEquals(Status.AVAILABLE, status.status)
        assertEquals(null, status.reason)
        assertEquals(LibraryStatus("grpc", Status.UNAVAILABLE, NetworkCapability.GRPC_UNSUPPORTED), status.libraries!![1])
        assertTrue("includes gRPC" in NetworkCapability.GRPC_UNSUPPORTED)
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

    @Test
    fun `the Ktor client on an engine capture does not cover degrades the capability, naming the engine (030)`() {
        val cio = NetworkCapability.ktor(setOf("cio"))
        val status = NetworkCapability.status(null, listOf(LibraryState("okhttp3", present = false, installFailure = null), cio), bounds)
        assertEquals(Status.DEGRADED, status.status)
        val ktor = status.libraries!!.single { it.name == "ktor-client" }
        assertEquals(Status.UNAVAILABLE, ktor.status)
        assertTrue("CIO engine" in ktor.reason!! && "not captured" in ktor.reason!!, ktor.reason)
        assertTrue("ktor-client:" in status.reason!!, status.reason)
    }

    @Test
    fun `the Ktor client on OkHttp or Android engines is captured through them, and absent Ktor is just listed`() {
        val viaOkHttp = NetworkCapability.status(null, listOf(NetworkCapability.ktor(setOf("okhttp", "android"))), bounds)
        assertEquals(Status.AVAILABLE, viaOkHttp.status)
        assertEquals(Status.AVAILABLE, viaOkHttp.libraries!!.single().status)
        val absent = NetworkCapability.status(null, listOf(NetworkCapability.ktor(emptySet())), bounds)
        assertEquals(Status.AVAILABLE, absent.status)
        assertEquals(NetworkCapability.ABSENT, absent.libraries!!.single().reason)
    }
}
