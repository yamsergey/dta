package io.yamsergey.dta.protocol.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Feature 074: DTA's own classes are left out of the rows and totals and said in one line; the app's classes under io.yamsergey.dta.* are not. */
class HeapDtaTest {
    private fun row(name: String, origin: HeapOrigin, count: Long, bytes: Long) = HeapClassRow(name, null, origin, count, bytes)

    private val rows = listOf(
        row("io.yamsergey.dta.sidekick.network.store.NetworkStore", HeapOrigin.LIBRARY, 2, 400),
        row("io.yamsergey.dta.sidekick.runtime.Runtime[]", HeapOrigin.LIBRARY, 1, 50),
        row("io.yamsergey.dta.vendor.rhino.Context", HeapOrigin.LIBRARY, 3, 300),
        row("io.yamsergey.dta.protocol.network.HttpEntry", HeapOrigin.LIBRARY, 10, 1000),
        row("io.yamsergey.dta.fixture.channel.CartViewModel", HeapOrigin.APP, 1, 64),
        row("io.yamsergey.dta.apps.weather.Forecast", HeapOrigin.APP, 5, 500),
        row("kotlinx.serialization.json.JsonObject", HeapOrigin.LIBRARY, 7, 700),
        row("java.lang.String", HeapOrigin.PLATFORM, 100, 2000),
    )

    @Test
    fun `DTA's own classes are left out, counted, and the app's classes under the same prefix stay`() {
        val s = HeapDta.split(rows)
        assertEquals(
            listOf("io.yamsergey.dta.fixture.channel.CartViewModel", "io.yamsergey.dta.apps.weather.Forecast", "kotlinx.serialization.json.JsonObject", "java.lang.String"),
            s.kept.map { it.className },
        )
        assertEquals(HeapTotals(16, 1750), s.leftOut)
    }

    @Test
    fun `one line says what was left out, and none when nothing was`() {
        assertEquals("16 objects of DTA itself left out (1750 bytes)", HeapDta.line(HeapTotals(16, 1750)))
        assertNull(HeapDta.line(HeapTotals(0, 0)))
    }
}
