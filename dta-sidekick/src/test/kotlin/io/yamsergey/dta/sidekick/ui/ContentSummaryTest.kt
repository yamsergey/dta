package io.yamsergey.dta.sidekick.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 015 (T008, research R5): the bounded structure-and-text summary of a window. */
class ContentSummaryTest {
    private fun text(t: String, name: String? = null) = RawNode("android.widget.TextView", text = t, name = name)

    private val dialog = RawNode(
        "com.android.internal.policy.DecorView",
        children = listOf(
            RawNode("android.widget.LinearLayout", children = listOf(
                RawNode("com.android.internal.widget.DialogTitle", text = "Delete item?", name = "android:id/alertTitle", heading = true),
                text("This cannot be undone.", "android:id/message"),
                RawNode("android.widget.EditText", text = "secret", editable = true, password = true),
                RawNode("android.widget.Button", text = "Cancel", clickable = true, name = "android:id/button2"),
                RawNode("android.widget.Button", text = "Delete", clickable = true, name = "android:id/button1"),
                RawNode("android.widget.TextView", text = "hidden", visible = false),
            )),
        ),
    )

    @Test
    fun `elements with roles in drawing order`() {
        val s = ContentSummaries.summarize(dialog, secure = false, settled = true, takenMonoMs = 5)
        assertEquals(listOf("heading", "text", "input", "button", "button"), s.summary.elements.map { it.role })
        assertEquals(listOf("Delete item?", "This cannot be undone.", null, "Cancel", "Delete"), s.summary.elements.map { it.text })
        assertTrue(s.summary.elements[2].password)
        assertEquals(0, s.summary.omitted)
        assertEquals("Delete item?", s.heading)
    }

    @Test
    fun `secure windows keep structure without texts`() {
        val s = ContentSummaries.summarize(dialog, secure = true, settled = true, takenMonoMs = 5)
        assertTrue(s.summary.textsWithheld)
        assertTrue(s.summary.elements.all { it.text == null && it.description == null })
        assertNull(s.heading)
    }

    @Test
    fun `the bound cuts and counts what was left out`() {
        val many = RawNode("FrameLayout", children = (1..60).map { text("row $it") })
        val s = ContentSummaries.summarize(many, secure = false, settled = false, takenMonoMs = 0, maxElements = 50)
        assertEquals(50, s.summary.elements.size)
        assertEquals(10, s.summary.omitted)
        assertEquals(false, s.summary.settled)
    }

    @Test
    fun `long texts are cut`() {
        val s = ContentSummaries.summarize(text("x".repeat(500)), secure = false, settled = true, takenMonoMs = 0, maxText = 200)
        assertEquals(200, s.summary.elements.single().text!!.length)
        assertTrue(s.summary.elements.single().text!!.endsWith("…"))
    }

    @Test
    fun `keys are stable paths with sibling indexes`() {
        val s = ContentSummaries.summarize(RawNode("FrameLayout", children = listOf(text("a"), text("b"), text("c", "msg"))), secure = false, settled = true, takenMonoMs = 0)
        assertEquals(listOf("FrameLayout/TextView#0", "FrameLayout/TextView#1", "FrameLayout/msg#0"), s.summary.elements.map { it.key })
    }

    @Test
    fun `a description alone makes an image element`() {
        val s = ContentSummaries.summarize(RawNode("ImageView", description = "Profile"), secure = false, settled = true, takenMonoMs = 0)
        assertEquals("image", s.summary.elements.single().role)
        assertEquals("Profile", s.summary.elements.single().description)
    }
}
