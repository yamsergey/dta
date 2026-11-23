package io.yamsergey.dta.studio.toolwindow

import javax.swing.ListSelectionModel

/** The Network table's selection, set from the list's selected entry. */
internal object TableSelection {
    /**
     * Selects [row], the selected entry's row; null (nothing selected, or an entry the list does not show:
     * reached by a link, D-056) leaves no row selected. True when the selection changed.
     */
    fun follow(selection: ListSelectionModel, row: Int?): Boolean {
        if (row == null) {
            if (selection.isSelectionEmpty) return false
            selection.clearSelection()
            return true
        }
        if (selection.minSelectionIndex == row && selection.maxSelectionIndex == row) return false
        selection.setSelectionInterval(row, row)
        return true
    }
}
