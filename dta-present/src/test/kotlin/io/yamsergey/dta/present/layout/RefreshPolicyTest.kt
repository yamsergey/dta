package io.yamsergey.dta.present.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 057 (US2): when Auto captures, and what the line says. */
class RefreshPolicyTest {
    private fun auto() = RefreshPolicy().apply { mode = RefreshPolicy.Mode.AUTO; shown = true; signal = RefreshPolicy.Signal.WATCHING; captureStarted(0); captureDone(100, 30) }

    @Test
    fun `one capture after half a second of quiet`() {
        val p = auto()
        assertNull(p.due(1_500), "nothing changed")
        p.changed(2_000, listOf("layout"))
        p.changed(2_200, listOf("compose"))
        assertEquals(2_700, p.due(2_300))
    }

    @Test
    fun `at least a second between captures`() {
        val p = auto()
        p.captureStarted(5_000); p.captureDone(5_040, 30)
        p.changed(5_100, listOf("layout"))
        assertEquals(6_000, p.due(5_200))
    }

    @Test
    fun `never two at a time, and not while hidden, in front or manual`() {
        val p = auto()
        p.changed(2_000, listOf("layout"))
        p.captureStarted(2_500)
        assertNull(p.due(2_600), "one in flight")
        p.captureFailed()
        p.shown = false
        assertNull(p.due(2_600))
        p.shown = true
        p.inFront = "com.android.permissioncontroller"
        assertNull(p.due(2_600))
        assertTrue(p.line(2_600).first.startsWith("Paused while com.android.permissioncontroller is in front"))
        p.inFront = null
        p.mode = RefreshPolicy.Mode.MANUAL
        assertNull(p.due(2_600))
    }

    @Test
    fun `slow captures double the gap up to eight seconds, a fast one resets it`() {
        val p = auto()
        repeat(5) { p.captureStarted(0); p.captureDone(0, 250) }
        assertEquals(8_000, p.gapMs)
        assertTrue(p.line(0).first.startsWith("Captures are slow (250 ms"), p.line(0).first)
        p.captureStarted(0); p.captureDone(0, 20)
        assertEquals(1_000, p.gapMs)
    }

    @Test
    fun `a screen that never settles is captured every three seconds and said`() {
        val p = auto()
        var t = 1_000L
        while (t < 4_200) { p.changed(t, listOf("compose")); t += 100 }
        assertTrue(p.keepsChanging(4_200))
        assertEquals(4_000, p.due(4_200), "not held back for ever by the debounce")
        assertTrue(p.line(4_200).first.startsWith("The screen keeps changing"), p.line(4_200).first)
    }

    @Test
    fun `manual says the screen changed after the capture, without capturing`() {
        val p = RefreshPolicy().apply { shown = true; captureStarted(0); captureDone(10_000, 30) }
        assertEquals("Refresh by hand. Each capture briefly runs on the app's main thread." to false, p.line(10_000))
        p.changed(14_000, listOf("windows"))
        val (text, caveat) = p.line(14_100)
        assertTrue(text.startsWith("The screen changed 4 s after this capture (windows). Refresh to see it."), text)
        assertTrue(caveat)
        assertNull(p.due(20_000))
    }

    @Test
    fun `an app that cannot report changes is said`() {
        val p = auto().apply { signal = RefreshPolicy.Signal.UNSUPPORTED }
        assertTrue(p.line(0).first.startsWith("Auto cannot follow this app: its DTA is older"))
        p.signal = RefreshPolicy.Signal.NO_DTA
        assertTrue(p.line(0).first.contains("no DTA inside"))
        p.signal = RefreshPolicy.Signal.WATCHING
        assertFalse(p.line(0).second)
    }
}
