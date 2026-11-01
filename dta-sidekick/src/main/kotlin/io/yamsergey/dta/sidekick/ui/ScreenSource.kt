package io.yamsergey.dta.sidekick.ui

import android.os.SystemClock
import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.protocol.timeline.Facets
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.ui.ContentSummary
import io.yamsergey.dta.protocol.ui.WindowRef
import io.yamsergey.dta.sidekick.timeline.FacetState
import io.yamsergey.dta.sidekick.timeline.TimelineBuffer

/**
 * The `screen.fingerprint` facet (feature 016, research R7): from each settle of the top
 * activity window's summary, which the journal takes anyway. An event only when it changes.
 */
class ScreenSource(
    private val buffer: TimelineBuffer,
    private val facets: FacetState,
    private val scope: () -> io.yamsergey.dta.protocol.timeline.CauseLink? = { null },
) {
    fun settled(window: WindowRef, summary: ContentSummary) {
        val fingerprint = ScreenFingerprint.of(window.owner, summary)
        val change = facets.change(Facets.SCREEN_FINGERPRINT, fingerprint.value, null) ?: return
        val event = buffer.append { id, seq ->
            TimelineEvent(
                id, seq, SystemClock.uptimeMillis(), System.currentTimeMillis(), Sources.SCREEN, KIND,
                title = "screen ${fingerprint.value}",
                facets = listOf(change),
                causes = listOfNotNull(scope()),
                completeness = if (fingerprint.truncated) {
                    listOf(DegradationMarker("screen", "${DegradationCodes.UI_CONTENT_TRUNCATED}: the fingerprint covers only the summarized part of the screen"))
                } else {
                    emptyList()
                },
            )
        }
        facets.attribute(listOf(Facets.SCREEN_FINGERPRINT), event.id)
    }

    companion object {
        const val KIND = "screen/changed"
    }
}
