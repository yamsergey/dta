package io.yamsergey.dta.sidekick.ui

import io.yamsergey.dta.protocol.ui.EventLink
import io.yamsergey.dta.protocol.ui.LinkRelation
import kotlin.test.Test
import kotlin.test.assertEquals

/** Feature 015 (research R9): network entries related to a UI event by time, never as its cause. */
class RecentNetworkTest {
    private val recent = RecentNetwork(windowMs = 2000, capacity = 3)

    @Test
    fun `in flight and finished shortly before`() {
        recent.started("a", 1_000)
        recent.started("b", 1_100)
        recent.finished("b", 2_000)
        recent.started("c", 1_200)
        recent.finished("c", 500)
        assertEquals(
            listOf(EventLink("network", "a", LinkRelation.IN_FLIGHT), EventLink("network", "b", LinkRelation.FINISHED_BEFORE)),
            recent.links(now = 3_000),
        )
    }

    @Test
    fun `finished too long ago or after the moment are not related`() {
        recent.started("a", 0)
        recent.finished("a", 500)
        recent.started("b", 4_000)
        assertEquals(emptyList(), recent.links(now = 3_000))
    }

    @Test
    fun `only the latest entries are kept`() {
        listOf("a", "b", "c", "d").forEachIndexed { i, id -> recent.started(id, 1_000L + i) }
        assertEquals(listOf("b", "c", "d"), recent.links(now = 2_000).map { it.id })
    }
}
