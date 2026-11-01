package io.yamsergey.dta.daemon.layout

import io.yamsergey.dta.protocol.layout.Rect
import io.yamsergey.dta.protocol.layout.ScreenWindowKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 014 (T003, research R2): `dumpsys window` recorded on the emulator (API 37). */
class WindowDumpTest {
    private val pkg = "io.yamsergey.dta.fixture.channel"
    private fun dump(name: String) = javaClass.getResource("/windowdump/$name.txt")!!.readText()
    private fun windows(name: String) = WindowDump.screenWindows(WindowDump.parse(dump(name)), pkg)

    @Test
    fun `portrait - visible windows top first, system bars named, the app's activity last`() {
        val list = windows("portrait")
        assertEquals(listOf("Taskbar", "StatusBar", "$pkg/$pkg.MainActivity"), list.map { it.name })
        assertEquals(listOf(0, 1, 2), list.map { it.zOrder })
        val app = list.last()
        assertTrue(app.app)
        assertEquals(ScreenWindowKind.ACTIVITY, app.kind)
        assertEquals("BASE_APPLICATION", app.type)
        assertEquals(pkg, app.owner)
        assertEquals(Rect(0, 0, 1080, 2400), app.bounds)
        assertFalse(app.secure)
        assertEquals(ScreenWindowKind.SYSTEM, list[1].kind)
        assertEquals(Rect(0, 0, 1080, 121), list[1].bounds)
        assertFalse(list[1].app)
    }

    @Test
    fun `rounded-corner overlays are decoration, not windows`() {
        assertTrue(WindowDump.parse(dump("portrait")).any { it.decoration && it.visible })
        assertTrue(windows("portrait").none { it.name.startsWith("ScreenDecorOverlay") })
    }

    @Test
    fun `landscape frames are in rotated pixels with the cutout offset`() {
        val app = windows("landscape").single { it.app }
        assertEquals(Rect(121, 0, 2400, 1080), app.bounds)
    }

    @Test
    fun `a toast is a window of system UI, not of the app`() {
        val toast = windows("toast").single { it.kind == ScreenWindowKind.TOAST }
        assertEquals("Toast", toast.name)
        assertEquals("com.android.systemui", toast.owner)
        assertFalse(toast.app)
        assertEquals(Rect(351, 2159, 729, 2274), toast.bounds)
    }

    @Test
    fun `a dialog is an app window above the activity`() {
        val app = windows("dialog").filter { it.app }
        assertEquals(listOf(ScreenWindowKind.DIALOG, ScreenWindowKind.ACTIVITY), app.map { it.kind })
        assertEquals(Rect(28, 972, 1052, 1485), app.first().bounds)
        assertTrue(app.first().zOrder < app.last().zOrder)
    }

    @Test
    fun `a secure window is marked`() {
        assertTrue(windows("secure").single { it.app }.secure)
    }

    @Test
    fun `invisible windows are left out - the keyboard when hidden, other activities behind`() {
        val names = windows("dialog").map { it.name }
        assertFalse("InputMethod" in names)
        assertTrue(names.none { "nexuslauncher" in it || "fixture.plain" in it }, "$names")
    }

    @Test
    fun `window kinds by type and owner`() {
        assertEquals(ScreenWindowKind.KEYBOARD, WindowDump.kindOf("INPUT_METHOD", owner = "com.google.android.inputmethod.latin", app = false))
        assertEquals(ScreenWindowKind.OTHER_APP, WindowDump.kindOf("BASE_APPLICATION", owner = "com.other", app = false))
        assertEquals(ScreenWindowKind.POPUP, WindowDump.kindOf("APPLICATION_SUB_PANEL", owner = pkg, app = true))
        assertEquals(ScreenWindowKind.POPUP, WindowDump.kindOf("APPLICATION_PANEL", owner = pkg, app = true))
        assertEquals(ScreenWindowKind.TOAST, WindowDump.kindOf("TOAST", owner = pkg, app = true))
        assertEquals(ScreenWindowKind.SYSTEM, WindowDump.kindOf("STATUS_BAR", owner = "com.android.systemui", app = false))
    }

    @Test
    fun `policy - screen on, off, locked`() {
        assertEquals(WindowDump.Policy(keyguardShowing = false, screenOn = true), WindowDump.policy(dump("policy-on")))
        assertEquals(WindowDump.Policy(keyguardShowing = true, screenOn = false), WindowDump.policy(dump("policy-off")))
        assertEquals(WindowDump.Policy(keyguardShowing = true, screenOn = true), WindowDump.policy(dump("policy-locked")))
    }

    @Test
    fun `unparsable output gives no windows and an unknown policy`() {
        assertEquals(emptyList(), WindowDump.parse("Can't find service: window"))
        val p = WindowDump.policy("")
        assertNull(p.keyguardShowing)
        assertNull(p.screenOn)
    }
}
