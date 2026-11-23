package io.yamsergey.dta.studio.toolwindow

import javax.swing.ListSelectionModel

/** The Network table's selection, set from the list's selected entry. */
internal object TableSelection {
    /** Selects [row] (the selected entry's row, null when it is not among the rows); true when the selection changed. */
    fun follow(selection: ListSelectionModel, row: Int?): Boolean {
        if (row == null || (selection.minSelectionIndex == row && selection.maxSelectionIndex == row)) return false
        selection.setSelectionInterval(row, row)
        return true
    }
}
