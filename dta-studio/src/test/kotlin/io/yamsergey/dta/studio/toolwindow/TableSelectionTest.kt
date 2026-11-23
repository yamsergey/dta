package io.yamsergey.dta.studio.toolwindow

import javax.swing.DefaultListSelectionModel
import kotlin.test.Test
import kotlin.test.assertEquals

/** D-056 (025 FR-004): the table shows the list's selection, and no row when the selected entry is not among the rows. */
class TableSelectionTest {
    @Test
    fun `the selected entry's row is selected, and none when it is not listed`() {
        val selection = DefaultListSelectionModel().apply { setSelectionInterval(4, 4) }
        assertEquals(true, TableSelection.follow(selection, 2))
        assertEquals(2, selection.minSelectionIndex)
        assertEquals(false, TableSelection.follow(selection, 2), "already there: nothing changes")
        // A link to an entry the filters hide: the old row must not stay highlighted.
        assertEquals(true, TableSelection.follow(selection, null))
        assertEquals(true, selection.isSelectionEmpty)
        assertEquals(false, TableSelection.follow(selection, null))
    }
}
