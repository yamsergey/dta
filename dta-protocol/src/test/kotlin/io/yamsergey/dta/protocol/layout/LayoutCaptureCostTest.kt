package io.yamsergey.dta.protocol.layout

import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Feature 057: a capture says how long it ran on the app's main thread. */
class LayoutCaptureCostTest {
    private val screen = Screen(widthPx = 10, heightPx = 10, density = 1f)

    @Test
    fun `the main-thread time round-trips`() {
        val capture = LayoutCapture(screen = screen, source = CaptureSource.SIDEKICK, windows = emptyList(), mainThreadMs = 38)
        val text = DtaJson.encodeToString(LayoutCapture.serializer(), capture)
        assertTrue("\"mainThreadMs\":38" in text, text)
        assertEquals(38, DtaJson.decodeFromString(LayoutCapture.serializer(), text).mainThreadMs)
    }

    @Test
    fun `absent when not measured`() {
        val text = DtaJson.encodeToString(LayoutCapture.serializer(), LayoutCapture(screen = screen, source = CaptureSource.UIAUTOMATOR, windows = emptyList()))
        assertFalse("mainThreadMs" in text, text)
    }
}
