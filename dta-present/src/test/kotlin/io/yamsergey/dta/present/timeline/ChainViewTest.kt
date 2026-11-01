package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.present.timeline.TimelineFixture.click
import io.yamsergey.dta.present.timeline.TimelineFixture.dialog
import io.yamsergey.dta.present.timeline.TimelineFixture.removed
import io.yamsergey.dta.present.timeline.TimelineFixture.request
import io.yamsergey.dta.present.timeline.TimelineFixture.response
import io.yamsergey.dta.present.timeline.TimelineFixture.toast
import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseChain
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.CauseRules
import io.yamsergey.dta.protocol.timeline.ChainDirection
import io.yamsergey.dta.protocol.timeline.ChainLink
import io.yamsergey.dta.protocol.timeline.Cut
import io.yamsergey.dta.protocol.timeline.CutReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 017 (T008): what the chain of a selected event looks like — lines and sections. */
class ChainViewTest {
    private fun link(from: TimelineFixture.() -> io.yamsergey.dta.protocol.timeline.TimelineEvent, to: io.yamsergey.dta.protocol.timeline.TimelineEvent) =
        ChainLink(TimelineFixture.from().id, to.id, to.causes.first { it.from == TimelineFixture.from().id })

    private val responseCauses = CauseChain(
        response, ChainDirection.CAUSES, listOf(response, request, click),
        listOf(link({ request }, response), link({ click }, request)),
    )

    @Test
    fun `causes become exact lines and indented rows to the root`() {
        val v = ChainView.of(responseCauses, null)
        assertEquals(setOf(request.id to response.id, click.id to request.id), v.lines.map { it.from to it.to }.toSet())
        assertTrue(v.lines.all { it.style == ChainView.Style.EXACT })
        assertEquals(listOf(1 to request.id, 2 to click.id), v.causedBy.map { it.depth to it.eventId })
        assertEquals("exact · same-exchange", v.causedBy.first().via)
        assertNull(v.noKnownCause)
    }

    @Test
    fun `an event without a known cause says so, with its inferred candidates, and draws nothing`() {
        val toastAlone = CauseChain(toast, ChainDirection.CAUSES, listOf(toast), emptyList(), noKnownCause = listOf(toast.id), inferredCandidates = mapOf(toast.id to 1))
        val v = ChainView.of(toastAlone, null)
        assertEquals(emptyList(), v.lines)
        assertEquals("no known cause (1 inferred candidate; turn on inferred links to see it)", v.noKnownCause)
    }

    @Test
    fun `inferred links are dashed and say their rule and detail`() {
        val inferred = CauseLink(response.id, Basis.INFERRED, CauseRules.AFTER_RESPONSE, "shown 34 ms after the response ended")
        val chain = CauseChain(
            toast, ChainDirection.CAUSES, listOf(toast, response), listOf(ChainLink(response.id, toast.id, inferred)),
            noKnownCause = listOf(toast.id), inferredIncluded = true,
        )
        val v = ChainView.of(chain, null)
        assertEquals(ChainView.Style.INFERRED, v.lines.single().style)
        assertEquals("inferred · after-response · shown 34 ms after the response ended", v.lines.single().label)
        assertEquals("no known cause (exact or declared); inferred below", v.noKnownCause)
    }

    @Test
    fun `effects become the LED TO rows, declared links styled declared`() {
        val effects = CauseChain(
            response, ChainDirection.EFFECTS, listOf(response, removed),
            listOf(ChainLink(response.id, removed.id, removed.causes.single())),
        )
        val v = ChainView.of(null, effects)
        assertEquals(listOf(1 to removed.id), v.ledTo.map { it.depth to it.eventId })
        assertEquals(ChainView.Style.DECLARED, v.lines.single().style)
    }

    @Test
    fun `where a chain stops is said`() {
        val cut = responseCauses.copy(cuts = listOf(Cut(click.id, CutReason.EVICTED, "7:0")))
        assertEquals(listOf("stops at ${click.id}: the cause 7:0 is no longer kept"), ChainView.of(cut, null).cuts)
    }

    @Test
    fun `rows read like the timeline`() {
        val v = ChainView.of(CauseChain(dialog, ChainDirection.CAUSES, listOf(dialog, click), listOf(link({ click }, dialog))), null)
        assertEquals("input  click delete_button \"Delete\"", v.causedBy.single().text)
    }
}
