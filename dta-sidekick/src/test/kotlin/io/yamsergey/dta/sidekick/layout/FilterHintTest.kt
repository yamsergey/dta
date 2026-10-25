package io.yamsergey.dta.sidekick.layout

import io.yamsergey.dta.protocol.layout.ComposeNode
import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.layout.DetailLevel
import io.yamsergey.dta.protocol.layout.LayoutFilter
import io.yamsergey.dta.protocol.layout.Match
import io.yamsergey.dta.protocol.layout.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A type filter that finds nothing below `layout` may be looking for a
 * container: say so instead of a silent empty result (feature 003 US4). */
class FilterHintTest {

    private val match = Match(0, ComposeNode(id = 1, bounds = Rect(0, 0, 1, 1), name = "Row"))

    @Test
    fun `zero matches for a type filter below layout yields the hint`() {
        for (delivered in listOf(DetailLevel.SEMANTIC)) {
            val hint = LevelResolution.filterHint(LayoutFilter(type = "Row"), emptyList(), delivered)
            assertEquals(DegradationCodes.FILTER_NEEDS_RICHER_DETAIL, hint?.reason)
        }
    }

    @Test
    fun `no hint when there are matches`() {
        assertNull(LevelResolution.filterHint(LayoutFilter(type = "Row"), listOf(match), DetailLevel.SEMANTIC))
    }

    @Test
    fun `no hint without a type filter`() {
        assertNull(LevelResolution.filterHint(LayoutFilter(text = "Nope"), emptyList(), DetailLevel.SEMANTIC))
    }

    @Test
    fun `no hint once containers were delivered`() {
        assertNull(LevelResolution.filterHint(LayoutFilter(type = "Row"), emptyList(), DetailLevel.LAYOUT))
        assertNull(LevelResolution.filterHint(LayoutFilter(type = "Row"), emptyList(), DetailLevel.COMPOSITION))
    }
}
