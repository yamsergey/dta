package io.yamsergey.dta.studio.toolwindow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D-107: a help line takes the width it is given, and grows in height instead of in width. */
class WrappedLabelTest {
    private val long = "Applies to this server, dta mcp and the CLI. A masked value keeps its length and a fingerprint, <code>#3f9a2c81d04e</code>: the same value has the same fingerprint everywhere, so an agent can tell an old token from a new one."

    @Test
    fun `a long help line never asks for more than the width it was given`() {
        val label = WrappedLabel(long, indent = 24)
        for (width in listOf(300, 420, 640)) {
            label.wrapTo(width)
            assertTrue(label.preferredSize.width <= width, "at $width px it asks for ${label.preferredSize.width}")
        }
    }

    @Test
    fun `a narrower page makes it taller`() {
        val label = WrappedLabel(long, indent = 24)
        label.wrapTo(640); val wide = label.preferredSize.height
        label.wrapTo(300); val narrow = label.preferredSize.height
        assertTrue(narrow > wide, "wide $wide, narrow $narrow")
    }

    @Test
    fun `new text keeps the width`() {
        val label = WrappedLabel("short", indent = 0)
        label.wrapTo(300)
        label.html = long
        assertTrue(label.preferredSize.width <= 300, "${label.preferredSize.width}")
        assertEquals(long, label.html)
    }

    @Test
    fun `before the page says its width a long line does not make the page wide`() {
        val label = WrappedLabel(long + long + long, indent = 24)
        assertTrue(label.preferredSize.width < 300, "${label.preferredSize.width}")
    }
}
