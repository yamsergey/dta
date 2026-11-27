package io.yamsergey.dta.studio.toolwindow

/** The line under the Network list that speaks of an empty or loading list; empty when there is nothing to say (D-153). */
internal object NetworkEmptyHint {
    fun of(hasApp: Boolean, online: Boolean, loading: Boolean, rowsEmpty: Boolean, noSaved: Boolean, noFilter: Boolean, allKinds: Boolean): String = when {
        !hasApp || !online -> ""
        loading && rowsEmpty -> "Loading…"
        rowsEmpty && noSaved && noFilter && allKinds -> "No traffic yet. Requests appear here as the app makes them."
        rowsEmpty -> "Nothing matches these filters."
        else -> ""
    }
}
