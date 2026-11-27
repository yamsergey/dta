package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.daemon.server.ClientException
import io.yamsergey.dta.protocol.ops.ClientErrorCodes
import io.yamsergey.dta.protocol.timeline.TimelineSwitch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** D-154: with the app's timeline off a flow start is refused with the timeline-off reason; on, it goes through. */
class TimelineOffTest {
    @Test
    fun `off is refused with the reason, on and recording or switched on-not-yet-recording pass`() {
        val e = assertFailsWith<ClientException> { TimelineOff.require(TimelineSwitch(on = false, recording = false)) }
        assertEquals(ClientErrorCodes.NOT_AVAILABLE, e.code)
        assertTrue(TimelineSwitch.OFF_REASON in e.message!!, e.message)
        TimelineOff.require(TimelineSwitch(on = true, recording = true))
        TimelineOff.require(TimelineSwitch(on = true, recording = false, note = "records from the next start"))
    }
}
