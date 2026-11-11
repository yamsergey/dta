package io.yamsergey.dta.present

import kotlin.test.Test
import kotlin.test.assertEquals

/** Feature 057: a statement's code is not said twice. */
class StatementLineTest {
    @Test
    fun `the code in brackets only when the sentence lacks it`() {
        val known = Statement(StatementKind.DEGRADED, detail = "x", code = "fallback", explanation = "The app has no DTA inside.")
        assertEquals("The app has no DTA inside. (fallback)", known.line)
        val raw = Statement(StatementKind.DEGRADED, detail = "Layout: screen-changed-during-capture: a window appeared", code = "screen-changed-during-capture: a window appeared")
        assertEquals("Layout: screen-changed-during-capture: a window appeared.", raw.line)
    }
}
