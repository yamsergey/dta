package io.yamsergey.dta.protocol.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Feature 074: every answer says the walk counts boot-image and zygote objects (device run, 2026-11-26). */
class HeapLimitsTest {
    @Test fun answersSayPlatformRowsIncludeObjectsTheAppDidNotAllocate() {
        assertTrue(HeapLimits.NOT_KNOWN.any { it.contains("boot image") && it.contains("zygote") && it.contains("did not allocate") })
        assertFalse(HeapLimits.NOT_KNOWN.any { it.contains("not counted") })
        assertTrue(HeapSnapshotAnswer(HeapSnapshotSummary("s1", null, "r", 0, 1, false, HeapTotals(0, 0), 0, emptyList()), 1).notKnown == HeapLimits.NOT_KNOWN)
    }
}
