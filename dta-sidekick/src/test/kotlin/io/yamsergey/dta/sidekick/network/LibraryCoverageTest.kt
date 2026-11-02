package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.rpc.CapabilityStatus.Status
import io.yamsergey.dta.sidekick.hooks.Hooks.Install
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 019 (US2): a library's status is folded from its per-event hook results. */
class LibraryCoverageTest {

    private val socket = "okhttp3.internal.ws.RealWebSocket"

    private fun installed(event: String, method: String = "m", descriptor: String = "()V") =
        HookSignature(method, descriptor).let { EventResult(event, it, listOf(it to Install.Installed)) }

    private fun absent(event: String, vararg forms: Pair<String, String>) = EventResult(
        event, installed = null,
        tried = forms.map { (m, d) -> HookSignature(m, d) to Install.MethodAbsent(socket, m, d) },
    )

    @Test
    fun `every event captured is available`() {
        val status = LibraryCoverage.of(LibraryState("okhttp3-ws", present = true, installFailure = null, events = listOf(installed("connections"), installed("close"))))
        assertEquals(Status.AVAILABLE, status.status)
        assertNull(status.reason)
        assertNull(status.notCaptured)
    }

    @Test
    fun `some events captured is degraded, naming what is not captured, why, and what is`() {
        val status = LibraryCoverage.of(
            LibraryState(
                "okhttp3-ws", present = true, installFailure = null,
                events = listOf(
                    installed("connections"),
                    absent("handshake headers", "checkUpgradeSuccess\$okhttp" to "(Lokhttp3/Response;Lokhttp3/internal/connection/Exchange;)V"),
                    installed("received text messages"),
                    absent(
                        "failures",
                        "failWebSocket" to "(Ljava/lang/Exception;Lokhttp3/Response;Z)V",
                        "failWebSocket" to "(Ljava/lang/Exception;Lokhttp3/Response;)V",
                    ),
                ),
            ),
        )
        assertEquals(Status.DEGRADED, status.status)
        assertEquals(listOf("handshake headers", "failures"), status.notCaptured)
        assertEquals(listOf("connections", "received text messages"), status.captured)
        val reason = status.reason!!
        assertTrue(reason.startsWith("not captured: handshake headers ("), reason)
        assertTrue("RealWebSocket.checkUpgradeSuccess\$okhttp(Response, Exchange)" in reason, reason)
        assertTrue("RealWebSocket.failWebSocket(Exception, Response, boolean)" in reason && "failWebSocket(Exception, Response)" in reason, reason)
        assertTrue("Captured: connections and received text messages." in reason, reason)
        assertTrue("update DTA" in reason, "an absent method names the action: $reason")
        assertFalse("JVMTI" in reason, reason)
    }

    @Test
    fun `no event captured is unavailable`() {
        val status = LibraryCoverage.of(
            LibraryState("okhttp3-ws", present = true, installFailure = null, events = listOf(absent("connections", "connect" to "(Lokhttp3/OkHttpClient;)V"))),
        )
        assertEquals(Status.UNAVAILABLE, status.status)
        assertEquals(listOf("connections"), status.notCaptured)
        assertNull(status.captured)
        assertTrue(status.reason!!.startsWith("nothing is captured; missing: connections ("), status.reason)
    }

    @Test
    fun `an absent library is unavailable, saying so`() {
        val status = LibraryCoverage.of(LibraryState("nv-websocket", present = false, installFailure = null))
        assertEquals(Status.UNAVAILABLE, status.status)
        assertEquals(LibraryCoverage.ABSENT, status.reason)
    }

    @Test
    fun `a runtime rejection keeps the raw cause as a detail only`() {
        val sig = HookSignature("send", "(Ljava/lang/String;)Z")
        val status = LibraryCoverage.of(
            LibraryState(
                "okhttp3-ws", present = true, installFailure = null,
                events = listOf(
                    installed("connections"),
                    EventResult("sent text messages", null, listOf(sig to Install.Failed("Android refused to reload the class with the hook", "RetransformClasses: JVMTI_ERROR_FAILS_VERIFICATION"))),
                ),
            ),
        )
        assertEquals(Status.DEGRADED, status.status)
        assertTrue("Android refused to reload the class with the hook" in status.reason!!, status.reason)
        assertFalse("JVMTI" in status.reason!!, status.reason)
        assertEquals("RetransformClasses: JVMTI_ERROR_FAILS_VERIFICATION", status.detail)
    }

    @Test
    fun `a whole-library failure is unavailable with a plain reason and the raw detail`() {
        val status = LibraryCoverage.of(LibraryState("okhttp3", present = true, installFailure = "DTA could not set up its capture of this library", installFailureDetail = "IllegalStateException: boom"))
        assertEquals(Status.UNAVAILABLE, status.status)
        assertTrue(status.reason!!.startsWith("nothing is captured: DTA could not set up"), status.reason)
        assertEquals("IllegalStateException: boom", status.detail)
    }
}
