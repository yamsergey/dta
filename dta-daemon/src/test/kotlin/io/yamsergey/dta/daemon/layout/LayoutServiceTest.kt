package io.yamsergey.dta.daemon.layout

import io.yamsergey.dta.protocol.layout.CaptureSource
import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.layout.FallbackNode
import io.yamsergey.dta.protocol.layout.LayoutCapture
import io.yamsergey.dta.protocol.layout.Rect
import io.yamsergey.dta.protocol.layout.Screen
import io.yamsergey.dta.protocol.layout.Window
import io.yamsergey.dta.protocol.layout.WindowType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutServiceTest {

    private val sidekickCapture = LayoutCapture(
        screen = Screen(1080, 2400, 2.75f),
        source = CaptureSource.SIDEKICK,
        windows = listOf(Window("Main", WindowType.APPLICATION, 0, FallbackNode(bounds = Rect(0, 0, 1, 1), className = "X"))),
    )

    private fun service(
        foreground: String? = "com.x",
        sidekick: (suspend (Map<String, Any?>) -> LayoutCapture)? = { sidekickCapture },
    ) = LayoutService(
        foregroundPackage = { foreground },
        sidekickCapture = sidekick,
        uiautomatorCapture = {
            FallbackNode(bounds = Rect(0, 0, 1080, 2400), className = "FrameLayout", text = "fallback")
        },
        screenOf = { Screen(1080, 2400, 2.75f) },
    )

    @Test
    fun `healthy foreground app passes the sidekick capture through untouched`() = runBlocking {
        val out = service().capture(serial = "emu", pkg = "com.x", filters = null)
        assertEquals(CaptureSource.SIDEKICK, out.source)
        assertNull(out.fallbackReason)
        assertNull(out.foregroundMismatch)
    }

    @Test
    fun `sidekick failure falls back with the failure reason`() = runBlocking {
        val out = service(sidekick = { throw RuntimeException("capture timed out after 5s") })
            .capture(serial = "emu", pkg = "com.x", filters = null)
        assertEquals(CaptureSource.UIAUTOMATOR, out.source)
        assertTrue("timed out" in out.fallbackReason!!, out.fallbackReason!!)
    }

    @Test
    fun `not-foreground app returns fallback of the actual screen with a mismatch note`() = runBlocking {
        val out = service(foreground = "com.launcher").capture(serial = "emu", pkg = "com.x", filters = null)
        assertEquals(CaptureSource.UIAUTOMATOR, out.source)
        assertTrue("com.x" in out.foregroundMismatch!! && "com.launcher" in out.foregroundMismatch!!)
    }

    @Test
    fun `no sidekick available yields fallback with no fallbackReason`() = runBlocking {
        val out = service(sidekick = null).capture(serial = "emu", pkg = null, filters = null)
        assertEquals(CaptureSource.UIAUTOMATOR, out.source)
        assertNull(out.fallbackReason, "nothing better was expected, so no failure reason")
    }

    @Test
    fun `filters on the fallback path are refused explicitly, never silently unfiltered`() = runBlocking {
        val out = service(foreground = "com.launcher")
            .capture(serial = "emu", pkg = "com.x", filters = mapOf("text" to "Submit"))
        assertEquals(false, out.filtersApplied)
        assertTrue(
            out.degraded!!.any { it.reason.contains("filter") },
            "fallback filter refusal must be a visible degradation: ${out.degraded}",
        )
    }
}
