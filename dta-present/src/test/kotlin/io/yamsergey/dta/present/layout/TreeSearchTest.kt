package io.yamsergey.dta.present.layout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T034: search in the tree, and the selection across a refresh. */
class TreeSearchTest {
    private fun canned(name: String): JsonObject = Json.parseToJsonElement(javaClass.getResource("/canned/$name.json")!!.readText()).jsonObject
    private val mixed = LayoutTree.of(canned("layout-mixed"))
    private val composeText = listOf(0, 0, 0, 0, 1, 0, 0, 0, 0, 0)
    private val embeddedText = listOf(0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0)

    @Test
    fun `a query matches name, text, ids and test tags, case-insensitively`() {
        assertEquals(listOf(composeText), TreeSearch.find(mixed, "MIXED_compose").matches, "test tag")
        assertEquals(listOf(embeddedText), TreeSearch.find(mixed, "embedded androidview").matches, "text")
        assertEquals(listOf(listOf(0, 0, 0, 0, 0)), TreeSearch.find(mixed, "disturbance_counter").matches, "resource id")
        assertEquals(1, TreeSearch.find(mixed, "column").matches.size, "composable name")
        assertTrue(TreeSearch.find(mixed, "  ").matches.isEmpty(), "a blank query matches nothing")
    }

    @Test
    fun `ancestors of matches are expanded, and dimmed when non-matching rows are hidden`() {
        val s = TreeSearch.find(mixed, "Compose in XML")
        assertEquals(listOf(composeText), s.matches)
        assertEquals((1 until composeText.size).map { composeText.take(it) }.toSet(), s.ancestors)
        assertTrue(s.visible(composeText) && s.visible(listOf(0)))
        assertFalse(s.visible(listOf(0, 1)), "an unrelated branch is hidden")
        assertTrue(s.dimmed(listOf(0, 0)))
        assertFalse(s.dimmed(composeText))
    }

    @Test
    fun `next and previous walk the matches in tree order and wrap`() {
        val s = TreeSearch.find(mixed, "TextView")
        assertEquals(3, s.matches.size)
        assertEquals(s.matches[0], s.next(null))
        assertEquals(s.matches[1], s.next(s.matches[0]))
        assertEquals(s.matches[0], s.next(s.matches[2]))
        assertEquals(s.matches[2], s.previous(s.matches[0]))
        assertEquals("2 of 3", s.position(s.matches[1]))
        assertEquals("3 matches", s.position(null))
        assertEquals("no matches", TreeSearch.find(mixed, "zzz").position(null))
    }

    @Test
    fun `a refresh keeps the selection by kind and id even when the node moved`() {
        val moved = LayoutTree.of(withExtraFirstChild(canned("layout-mixed"), listOf(0, 0, 0, 0)))
        val kept = TreeSearch.reselect(mixed, composeText, moved)
        assertEquals(listOf(0, 0, 0, 0, 2, 0, 0, 0, 0, 0), kept.path)
        assertNull(kept.note)
    }

    @Test
    fun `without an id the selection is kept by path and name, else cleared with a note`() {
        val plain = LayoutTree.of(canned("layout-plain-fallback"))
        val path = plain.all().drop(2).first().path
        assertEquals(path, TreeSearch.reselect(plain, path, plain).path)

        val gone = TreeSearch.reselect(mixed, composeText, plain)
        assertNull(gone.path)
        assertEquals("The selected node Text (#3) is no longer on screen.", gone.note)
    }

    /** The capture with a new view inserted as the first child at [at]: every later sibling's path shifts. */
    private fun withExtraFirstChild(capture: JsonObject, at: List<Int>): JsonObject {
        fun insert(node: JsonObject, rest: List<Int>): JsonObject {
            val children = (node["children"] as? JsonArray).orEmpty().map { it.jsonObject }
            val updated = if (rest.isEmpty()) {
                listOf(JsonObject(mapOf("kind" to JsonPrimitive("view"), "id" to JsonPrimitive(999), "className" to JsonPrimitive("android.view.View"),
                    "bounds" to JsonObject(mapOf("left" to JsonPrimitive(0), "top" to JsonPrimitive(0), "right" to JsonPrimitive(1), "bottom" to JsonPrimitive(1)))))) + children
            } else {
                children.mapIndexed { i, c -> if (i == rest.first()) insert(c, rest.drop(1)) else c }
            }
            return JsonObject(node + ("children" to JsonArray(updated)))
        }
        val windows = (capture["windows"] as JsonArray).mapIndexed { i, w ->
            val window = w.jsonObject
            if (i == at.first()) JsonObject(window + ("root" to insert(window["root"]!!.jsonObject, at.drop(1)))) else window
        }
        return JsonObject(capture + ("windows" to JsonArray(windows)))
    }
}
