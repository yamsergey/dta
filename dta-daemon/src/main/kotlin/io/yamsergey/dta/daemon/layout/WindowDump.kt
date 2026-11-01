package io.yamsergey.dta.daemon.layout

import io.yamsergey.dta.protocol.layout.Rect
import io.yamsergey.dta.protocol.layout.ScreenWindow
import io.yamsergey.dta.protocol.layout.ScreenWindowKind

/**
 * Feature 014 (research R2): the windows on screen and the keyguard state, read from
 * `dumpsys window windows` and `dumpsys window policy` on the host. Pure, so every
 * recorded device state is a deviceless test.
 */
object WindowDump {
    /** One `Window #N` block; [index] is the system's z-order, 0 on top. */
    data class Dumped(
        val index: Int,
        val name: String,
        val owner: String?,
        val type: String,
        val bounds: Rect?,
        val secure: Boolean,
        val visible: Boolean,
        val displayId: Int,
        /** Rounded-corner and cutout overlays: transparent, drawn over everything. */
        val decoration: Boolean,
    )

    data class Policy(val keyguardShowing: Boolean?, val screenOn: Boolean?)

    private val header = Regex("""^ {2}Window #(\d+) Window\{\S+ u\d+ (.+)\}:\s*$""")
    private val owner = Regex("""\bpackage=(\S+)""")
    private val type = Regex("""\bty=([A-Z_0-9]+)""")
    private val frame = Regex("""\bframe=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")
    private val display = Regex("""\bmDisplayId=(\d+)""")

    fun parse(text: String): List<Dumped> {
        val blocks = mutableListOf<Pair<MatchResult, MutableList<String>>>()
        for (line in text.lineSequence()) {
            val start = header.matchEntire(line)
            if (start != null) blocks += start to mutableListOf()
            else blocks.lastOrNull()?.second?.add(line)
        }
        return blocks.map { (head, lines) ->
            val body = lines.joinToString("\n")
            val flags = lines.filter { it.trimStart().startsWith("fl=") }.flatMap { it.trim().removePrefix("fl=").split(' ') }
            val privateFlags = lines.filter { it.trimStart().startsWith("pfl=") }.flatMap { it.trim().removePrefix("pfl=").split(' ') }
            Dumped(
                index = head.groupValues[1].toInt(),
                name = head.groupValues[2],
                owner = owner.find(body)?.groupValues?.get(1),
                type = type.find(body)?.groupValues?.get(1) ?: "UNKNOWN",
                bounds = frame.find(body)?.destructured?.let { (l, t, r, b) -> Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt()) },
                secure = "SECURE" in flags,
                visible = lines.any { it.trim() == "isVisible=true" },
                displayId = display.find(body)?.groupValues?.get(1)?.toInt() ?: 0,
                decoration = "IS_ROUNDED_CORNERS_OVERLAY" in privateFlags || "IS_SCREEN_DECOR" in privateFlags,
            )
        }
    }

    /**
     * The visible windows of the app's display, topmost first, as the capture reports them.
     * Decorations are left out: they are transparent and cover the whole screen's edges.
     */
    fun screenWindows(dumped: List<Dumped>, pkg: String?): List<ScreenWindow> {
        val shown = dumped.filter { it.visible && !it.decoration && it.bounds != null }
        val displayId = shown.firstOrNull { it.owner == pkg }?.displayId ?: 0
        return shown.filter { it.displayId == displayId }.sortedBy { it.index }.mapIndexed { z, w ->
            val app = pkg != null && w.owner == pkg
            ScreenWindow(
                name = w.name,
                owner = w.owner,
                app = app,
                kind = kindOf(w.type, w.owner, app),
                type = w.type,
                bounds = w.bounds!!,
                secure = w.secure,
                zOrder = z,
            )
        }
    }

    fun kindOf(type: String, owner: String?, app: Boolean): ScreenWindowKind = when {
        type == "TOAST" -> ScreenWindowKind.TOAST
        type == "INPUT_METHOD" -> ScreenWindowKind.KEYBOARD
        app && type == "BASE_APPLICATION" -> ScreenWindowKind.ACTIVITY
        app && type.endsWith("PANEL") -> ScreenWindowKind.POPUP
        app -> ScreenWindowKind.DIALOG
        type == "BASE_APPLICATION" || (type.startsWith("APPLICATION") && owner != null) -> ScreenWindowKind.OTHER_APP
        else -> ScreenWindowKind.SYSTEM
    }

    /** From `dumpsys window policy`: the keyguard delegate's `showing=` and `screenState=`. */
    fun policy(text: String): Policy {
        val delegate = text.substringAfter("KeyguardServiceDelegate", missingDelimiterValue = "")
        val showing = Regex("""\bshowing=(true|false)""").find(delegate)?.groupValues?.get(1)?.toBooleanStrict()
        val screen = Regex("""\bscreenState=(\w+)""").find(delegate)?.groupValues?.get(1)
        return Policy(
            keyguardShowing = showing,
            screenOn = when (screen) {
                "SCREEN_STATE_ON" -> true
                "SCREEN_STATE_OFF" -> false
                else -> null
            },
        )
    }
}
