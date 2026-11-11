package io.yamsergey.dta.cli.format

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/** Feature 057: the header says how long the capture ran on the app's main thread. */
class LayoutCostFormatTest {
    private val f = Formatters(Style.PLAIN)

    private fun capture(extra: String) =
        """{"screen":{"widthPx":100,"heightPx":100},"source":"sidekick","detail":{"requested":"composition","delivered":"composition"},"windows":[]$extra}"""

    @Test
    fun `the main-thread time is in the header`() {
        val text = f.format("layout.tree", Json.parseToJsonElement(capture(""","mainThreadMs":38""")))
        assertTrue(text.lineSequence().first().endsWith("38 ms on the app's main thread"), text)
    }

    @Test
    fun `not reported when the capture has none`() {
        val text = f.format("layout.tree", Json.parseToJsonElement(capture("")))
        assertTrue(text.lineSequence().first().endsWith("main-thread time not reported"), text)
    }
}
