package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.daemon.server.ClientException
import io.yamsergey.dta.protocol.ops.ClientErrorCodes
import io.yamsergey.dta.protocol.timeline.TimelineSwitch

/**
 * D-154 (049 FR-009, 056 US2-AS4): what needs the timeline's data is refused while the app's timeline is off, with the reason the
 * timeline operations give (the flow start is the first).
 */
object TimelineOff {
    fun require(switch: TimelineSwitch) {
        // not refused yet
    }
}
