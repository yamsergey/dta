package io.yamsergey.dta.daemon.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForegroundDetectorTest {

    @Test
    fun `extracts foreground package from recorded dumpsys focus`() {
        val text = javaClass.getResource("/dumpsys/window_focus.txt")!!.readText()
        assertEquals("io.yamsergey.dta.fixture.channel", ForegroundDetector.parseForegroundPackage(text))
    }

    @Test
    fun `parses mCurrentFocus with slash-separated activity`() {
        val text = "  mCurrentFocus=Window{abc u0 com.example.app/com.example.app.MainActivity}"
        assertEquals("com.example.app", ForegroundDetector.parseForegroundPackage(text))
    }

    @Test
    fun `malformed or empty output yields null, not a crash`() {
        assertNull(ForegroundDetector.parseForegroundPackage(""))
        assertNull(ForegroundDetector.parseForegroundPackage("mCurrentFocus=null"))
        assertNull(ForegroundDetector.parseForegroundPackage("garbage without focus line"))
    }
}
