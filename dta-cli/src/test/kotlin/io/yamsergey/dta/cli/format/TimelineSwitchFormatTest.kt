package io.yamsergey.dta.cli.format

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** Feature 056: the timeline switch in the terminal. */
class TimelineSwitchFormatTest {
    private val f = Formatters(Style.PLAIN)

    @Test
    fun `on, off and what it means for the running app`() {
        assertEquals("Timeline off for this app; not recording", f.format("timeline.enabled", Json.parseToJsonElement("""{"on":false,"recording":false}""")))
        assertEquals("Timeline off for this app; recording now (recording stops at the app's next start)",
            f.format("timeline.enabled", Json.parseToJsonElement("""{"on":false,"recording":true,"note":"recording stops at the app's next start"}""")))
    }
}
