package io.yamsergey.dta.protocol.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Feature 074 (C1): the pause bound as a duration (`--max-pause 3s`). */
class HeapDurationTest {
    @Test fun parsesUnitsAndPlainMilliseconds() {
        assertEquals(3000L, HeapLimits.parseDurationMs("3s"))
        assertEquals(500L, HeapLimits.parseDurationMs("500ms"))
        assertEquals(1500L, HeapLimits.parseDurationMs("1.5s"))
        assertEquals(60_000L, HeapLimits.parseDurationMs("1m"))
        assertEquals(250L, HeapLimits.parseDurationMs("250"))
    }

    @Test fun refusesNonsenseAndZero() {
        listOf("", "abc", "-1s", "0", "3x", "s").forEach { assertFailsWith<IllegalArgumentException>(it) { HeapLimits.parseDurationMs(it) } }
    }
}
