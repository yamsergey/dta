package io.yamsergey.dta.protocol.layout

import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Feature 014 (T001): the screen image and the windows on screen travel with the capture. */
class LayoutScreenModelTest {
    private val tree = LayoutCapture(
        screen = Screen(1080, 2400, 2.625f),
        source = CaptureSource.SIDEKICK,
        windows = listOf(Window(type = WindowType.APPLICATION, zOrder = 0, root = ViewNode(id = 1, bounds = Rect(0, 0, 1080, 2400), className = "F"))),
    )

    @Test
    fun `a capture without the screen serializes as before`() {
        val json = DtaJson.encodeToString(LayoutCapture.serializer(), tree)
        assertFalse("screenImage" in json, json)
        assertFalse("screenWindows" in json, json)
        assertEquals(tree, DtaJson.decodeFromString(LayoutCapture.serializer(), json))
    }

    @Test
    fun `a capture with the screen round-trips`() {
        val capture = tree.copy(
            screenImage = ScreenImage(
                state = ScreenImageState.PRESENT,
                widthPx = 1080,
                heightPx = 2400,
                displayId = 0,
                rotation = 1,
                takenAtMs = 1_793_000_000_000,
                alignment = Alignment(AlignmentKind.OFFSET, dx = 121, dy = 0),
                file = FileRef("/tmp/s.png", 36_167, "ab12"),
                scale = 0.5f,
            ),
            screenWindows = listOf(
                ScreenWindow(
                    name = "Toast", owner = "com.android.systemui", app = false, kind = ScreenWindowKind.TOAST,
                    type = "TOAST", bounds = Rect(300, 2000, 780, 2150), secure = false, zOrder = 0,
                ),
                ScreenWindow(
                    name = "pkg/pkg.MainActivity", owner = "pkg", app = true, kind = ScreenWindowKind.ACTIVITY,
                    type = "BASE_APPLICATION", bounds = Rect(0, 0, 1080, 2400), secure = true, zOrder = 1, treeWindow = 0,
                ),
            ),
        )
        val json = DtaJson.encodeToString(LayoutCapture.serializer(), capture)
        assertEquals(capture, DtaJson.decodeFromString(LayoutCapture.serializer(), json))
        assertEquals(true, "\"state\":\"present\"" in json && "\"kind\":\"offset\"" in json && "\"kind\":\"toast\"" in json, json)
    }

    @Test
    fun `image states, alignment kinds and window kinds use their wire names`() {
        val blocked = ScreenImage(state = ScreenImageState.BLOCKED, reason = "a secure window is on screen")
        assertEquals("""{"state":"blocked","reason":"a secure window is on screen"}""", DtaJson.encodeToString(ScreenImage.serializer(), blocked))
        assertEquals("\"not-taken\"", DtaJson.encodeToString(ScreenImageState.serializer(), ScreenImageState.NOT_TAKEN))
        assertEquals("\"not-aligned\"", DtaJson.encodeToString(AlignmentKind.serializer(), AlignmentKind.NOT_ALIGNED))
        assertEquals("\"other-app\"", DtaJson.encodeToString(ScreenWindowKind.serializer(), ScreenWindowKind.OTHER_APP))
    }

    @Test
    fun `screen statement codes`() {
        assertEquals(
            listOf(
                "screen-blocked", "screen-not-taken", "screen-locked", "screen-off",
                "screen-changed-during-capture", "screen-scaled", "screen-not-aligned", "screen-display-unknown",
            ),
            listOf(
                DegradationCodes.SCREEN_BLOCKED, DegradationCodes.SCREEN_NOT_TAKEN, DegradationCodes.SCREEN_LOCKED,
                DegradationCodes.SCREEN_OFF, DegradationCodes.SCREEN_CHANGED_DURING_CAPTURE, DegradationCodes.SCREEN_SCALED,
                DegradationCodes.SCREEN_NOT_ALIGNED, DegradationCodes.SCREEN_DISPLAY_UNKNOWN,
            ),
        )
    }
}
