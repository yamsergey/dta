package io.yamsergey.dta.studio.toolwindow

import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkEmptyHintTest {
    @Test fun `loading is said only while there is nothing to show`() {
        assertEquals("Loading…", NetworkEmptyHint.of(true, true, loading = true, rowsEmpty = true, noSaved = true, noFilter = true, allKinds = true))
        assertEquals("", NetworkEmptyHint.of(true, true, loading = true, rowsEmpty = false, noSaved = true, noFilter = true, allKinds = true), "a row is shown: no Loading…")
    }

    @Test fun `empty words`() {
        assertEquals("No traffic yet. Requests appear here as the app makes them.", NetworkEmptyHint.of(true, true, false, true, true, true, true))
        assertEquals("Nothing matches these filters.", NetworkEmptyHint.of(true, true, false, true, false, true, true))
        assertEquals("", NetworkEmptyHint.of(true, false, true, true, true, true, true))
    }
}
