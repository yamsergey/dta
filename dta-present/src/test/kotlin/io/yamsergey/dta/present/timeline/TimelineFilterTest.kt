package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.present.timeline.TimelineFixture.click
import io.yamsergey.dta.present.timeline.TimelineFixture.ended
import io.yamsergey.dta.present.timeline.TimelineFixture.flow
import io.yamsergey.dta.present.timeline.TimelineFixture.gap
import io.yamsergey.dta.present.timeline.TimelineFixture.navigate
import io.yamsergey.dta.present.timeline.TimelineFixture.toast
import kotlin.test.Test
import kotlin.test.assertEquals

/** Feature 017 (T002): narrowing the timeline; gaps and process ends always stay. */
class TimelineFilterTest {
    private val all = flow + gap + ended

    private fun ids(f: TimelineFilter) = all.filter(f::matches).map { it.id }

    @Test
    fun `by source`() {
        assertEquals(listOf(click.id, gap.id, ended.id), ids(TimelineFilter(sources = setOf("input"))))
    }

    @Test
    fun `by text, ignoring case, over what the line says and the id`() {
        assertEquals(listOf(toast.id, gap.id, ended.id), ids(TimelineFilter(text = "DELETED")))
        assertEquals(listOf(navigate.id, gap.id, ended.id), ids(TimelineFilter(text = "7:7")))
    }

    @Test
    fun `only events without a known cause, inputs being roots`() {
        assertEquals(listOf(toast.id, navigate.id, gap.id, ended.id), ids(TimelineFilter(unexplainedOnly = true)))
    }
}
