package io.yamsergey.dta.studio.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BreadcrumbFitTest {
    private val names = listOf("DecorView", "ActionBarOverlayLayout", "FrameLayout", "LinearLayout", "TextView")
    private val widths = names.map { it.length }

    @Test fun `all of it when it fits`() = assertEquals(listOf(0, 1, 2, 3, 4), BreadcrumbFit.kept(widths, 3, 1, 200))

    @Test fun `the middle goes first and the selected stays`() {
        // first + … + parent + selected
        val room = 9 + 3 + 1 + 3 + 12 + 3 + 8
        assertEquals(listOf(0, 3, 4), BreadcrumbFit.kept(widths, 3, 1, room))
    }

    @Test fun `then the parent, then the first, never the selected`() {
        assertEquals(listOf(0, 4), BreadcrumbFit.kept(widths, 3, 1, 9 + 3 + 1 + 3 + 8))
        assertEquals(listOf(4), BreadcrumbFit.kept(widths, 3, 1, 12))
        assertEquals(listOf(4), BreadcrumbFit.kept(widths, 3, 1, 1))
    }
}
