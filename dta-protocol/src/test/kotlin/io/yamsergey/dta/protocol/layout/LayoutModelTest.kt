package io.yamsergey.dta.protocol.layout

import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LayoutModelTest {

    private inline fun <reified T> roundTrip(value: T): T =
        DtaJson.decodeFromString(DtaJson.encodeToString(value))

    @Test
    fun `capture round-trips with screen source and windows`() {
        val capture = LayoutCapture(
            screen = Screen(1080, 2400, 2.75f, activity = "com.x.MainActivity"),
            source = CaptureSource.SIDEKICK,
            windows = listOf(
                Window(
                    title = "MainActivity",
                    type = WindowType.APPLICATION,
                    zOrder = 0,
                    root = ViewNode(
                        id = 42,
                        bounds = Rect(0, 0, 1080, 2400),
                        className = "android.widget.FrameLayout",
                        children = emptyList(),
                    ),
                ),
            ),
        )
        assertEquals(capture, roundTrip(capture))
    }

    @Test
    fun `node kinds are discriminated by kind field`() {
        val view = DtaJson.encodeToString<Node>(
            ViewNode(id = 1, bounds = Rect(0, 0, 10, 10), className = "V"),
        )
        val compose = DtaJson.encodeToString<Node>(
            ComposeNode(id = 2, bounds = Rect(0, 0, 10, 10), name = "Text"),
        )
        val fallback = DtaJson.encodeToString<Node>(
            FallbackNode(bounds = Rect(0, 0, 10, 10), className = "TextView"),
        )
        assertTrue(""""kind":"view"""" in view, view)
        assertTrue(""""kind":"compose"""" in compose, compose)
        assertTrue(""""kind":"fallback"""" in fallback, fallback)

        assertIs<ViewNode>(DtaJson.decodeFromString<Node>(view))
        assertIs<ComposeNode>(DtaJson.decodeFromString<Node>(compose))
        assertIs<FallbackNode>(DtaJson.decodeFromString<Node>(fallback))
    }

    @Test
    fun `defaults and nulls are omitted to keep payloads small`() {
        val json = DtaJson.encodeToString<Node>(
            ViewNode(id = 1, bounds = Rect(0, 0, 10, 10), className = "V"),
        )
        assertTrue("enabled" !in json, json)          // default true omitted
        assertTrue("renderOutline" !in json, json)    // null omitted
        assertTrue("resourceId" !in json, json)
        assertTrue("degraded" !in json, json)
    }

    @Test
    fun `disabled state is serialized when false`() {
        val json = DtaJson.encodeToString<Node>(
            ViewNode(id = 1, bounds = Rect(0, 0, 10, 10), className = "V", enabled = false),
        )
        assertTrue(""""enabled":false""" in json, json)
    }

    @Test
    fun `fallback capture carries source and reason`() {
        val capture = LayoutCapture(
            screen = Screen(1080, 2400, 2.75f),
            source = CaptureSource.UIAUTOMATOR,
            fallbackReason = "sidekick capture timed out after 5s",
            foregroundMismatch = "requested com.x but com.launcher is foreground",
            windows = listOf(
                Window(null, WindowType.APPLICATION, 0, FallbackNode(bounds = Rect(0, 0, 1, 1), className = "T")),
            ),
        )
        val decoded = roundTrip(capture)
        assertEquals(CaptureSource.UIAUTOMATOR, decoded.source)
        assertEquals("sidekick capture timed out after 5s", decoded.fallbackReason)
        assertEquals("requested com.x but com.launcher is foreground", decoded.foregroundMismatch)
    }

    @Test
    fun `compose node enrichment fields round-trip and are omitted when absent`() {
        val enriched = ComposeNode(
            id = 3,
            bounds = Rect(0, 0, 100, 40),
            name = "SubmitButton",
            role = "Button",
            testTag = "submit",
            sourceFile = "LoginScreen.kt",
            lineNumber = 42,
            library = false,
            parameters = listOf(Parameter("enabled", "true", "Boolean")),
        )
        assertEquals(enriched, roundTrip<Node>(enriched))

        val semanticsOnly = ComposeNode(id = 4, bounds = Rect(0, 0, 10, 10), role = "Text")
        val json = DtaJson.encodeToString<Node>(semanticsOnly)
        assertTrue("name" !in json && "sourceFile" !in json && "library" !in json, json)
    }

    @Test
    fun `degradation codes are stable constants`() {
        assertEquals("compose-tooling-absent", DegradationCodes.COMPOSE_TOOLING_ABSENT)
        assertEquals("compose-tooling-version-skew", DegradationCodes.COMPOSE_TOOLING_VERSION_SKEW)
        assertEquals("compose-enrichment-failed", DegradationCodes.COMPOSE_ENRICHMENT_FAILED)
        assertEquals("compose-first-frame-pending", DegradationCodes.COMPOSE_FIRST_FRAME_PENDING)
        assertEquals("depth-cap-reached", DegradationCodes.DEPTH_CAP_REACHED)
        assertEquals("window-lost-mid-capture", DegradationCodes.WINDOW_LOST_MID_CAPTURE)
        assertEquals("legacy-root-discovery", DegradationCodes.LEGACY_ROOT_DISCOVERY)
    }

    @Test
    fun `renderOutline quad round-trips for transformed nodes`() {
        val rotated = ViewNode(
            id = 5,
            bounds = Rect(10, 10, 110, 60),
            className = "V",
            renderOutline = Quad(10f, 12f, 110f, 8f, 112f, 58f, 8f, 62f),
        )
        assertEquals(rotated, roundTrip<Node>(rotated))
    }

    @Test
    fun `filtered capture carries matches with window index and ancestors`() {
        val capture = LayoutCapture(
            screen = Screen(1080, 2400, 2.75f),
            source = CaptureSource.SIDEKICK,
            windows = emptyList(),
            filtersApplied = true,
            matches = listOf(
                Match(
                    window = 0,
                    node = ViewNode(id = 7, bounds = Rect(0, 0, 100, 40), className = "Button"),
                    ancestors = listOf(AncestorRef("view", "FrameLayout", 1, Rect(0, 0, 1080, 2400))),
                ),
            ),
        )
        assertEquals(capture, roundTrip(capture))
    }
}
