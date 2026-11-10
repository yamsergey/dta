package io.yamsergey.dta.protocol.timeline

import io.yamsergey.dta.protocol.ops.Catalog
import io.yamsergey.dta.protocol.ops.ParamType
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 056: Timeline is a switch, off by default, set per app. */
class TimelineSwitchTest {
    @Test
    fun `the switch state travels as on, recording and a note`() {
        val s = TimelineSwitch(on = true, recording = true, note = "recording since now; from the app's start on its next launch")
        assertEquals(s, DtaJson.decodeFromString(TimelineSwitch.serializer(), DtaJson.encodeToString(TimelineSwitch.serializer(), s)))
        assertTrue("Settings › Tools › DTA" in TimelineSwitch.OFF_REASON)
    }

    @Test
    fun `an operation reads and sets it`() {
        val spec = Catalog.all.single { it.name == "timeline.enabled" }
        assertEquals(ParamType.BOOLEAN, spec.params.single { it.name == "on" }.type)
    }
}
