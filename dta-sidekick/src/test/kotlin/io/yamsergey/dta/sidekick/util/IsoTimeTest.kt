package io.yamsergey.dta.sidekick.util

import kotlin.test.Test
import kotlin.test.assertEquals

class IsoTimeTest {
    @Test
    fun `utc with milliseconds`() = assertEquals("2023-11-14T22:13:20.123Z", IsoTime.format(1_700_000_000_123))
}
