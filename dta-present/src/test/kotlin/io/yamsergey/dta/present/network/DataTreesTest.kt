package io.yamsergey.dta.present.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DataTreesTest {

    @Test
    fun `JSON becomes a tree of kinds, previews, counts and paths`() {
        val root = DataTrees.json("""{"user":{"name":"Ann","age":31,"admin":false,"note":null},"items":[{"id":1},{"id":2}],"odd key":"x"}""")!!
        assertEquals(NodeKind.OBJECT, root.kind)
        assertEquals("$", root.path)
        assertEquals("{3}", root.summary)
        val user = root.children[0]
        assertEquals("user", user.key)
        assertEquals("$.user", user.path)
        assertEquals(listOf(NodeKind.STRING, NodeKind.NUMBER, NodeKind.BOOLEAN, NodeKind.NULL), user.children.map { it.kind })
        assertEquals(listOf("\"Ann\"", "31", "false", "null"), user.children.map { it.value })
        assertEquals("$.user.name", user.children[0].path)
        val items = root.children[1]
        assertEquals("[2]", items.summary)
        assertEquals("[1]", items.children[1].key)
        assertEquals("$.items[1].id", items.children[1].children[0].path)
        assertEquals("$[\"odd key\"]", root.children[2].path)
        assertEquals("x", root.children[2].copyValue)
        assertEquals("{\"id\":1}", items.children[0].copyValue)
    }

    @Test
    fun `long values are cut in the preview, never in the copied value`() {
        val long = "a".repeat(300)
        val node = DataTrees.json("""{"s":"$long"}""")!!.children.single()
        assertEquals(DataTrees.PREVIEW + 3, node.value!!.length) // quotes + ellipsis
        assertEquals(long, node.copyValue)
    }

    @Test
    fun `XML becomes elements with attributes and text, XPath-like paths`() {
        val root = DataTrees.xml("""<feed lang="en"><entry id="1"><title>One</title></entry><entry id="2"><title>Two</title></entry></feed>""")!!
        assertEquals(NodeKind.ELEMENT, root.kind)
        assertEquals("feed", root.key)
        assertEquals("/feed", root.path)
        assertEquals(listOf("@lang", "entry", "entry"), root.children.map { it.key })
        assertEquals("\"en\"", root.children[0].value)
        val second = root.children[2]
        assertEquals("/feed/entry[2]", second.path)
        assertEquals("/feed/entry[2]/@id", second.children[0].path)
        assertEquals("/feed/entry[2]/title", second.children[1].path)
        // An element with only text shows the text as its value.
        assertEquals("\"Two\"", second.children[1].value)
        assertEquals("<2>", second.summary)
        assertNull(DataTrees.xml("<!DOCTYPE a><a/>"))
    }

    @Test
    fun `a form becomes an object of its fields`() {
        val root = DataTrees.form("a=1&b=two+words&a=3")!!
        assertEquals(listOf("a", "b", "a"), root.children.map { it.key })
        assertEquals("\"two words\"", root.children[1].value)
        assertEquals("$.b", root.children[1].path)
    }

    @Test
    fun `JSON Lines becomes one top-level node per line`() {
        val tree = DataTrees.jsonLines("{\"a\":1}\n{\"b\":{\"c\":true}}")!!
        assertEquals(NodeKind.ARRAY, tree.kind)
        assertEquals(listOf("[0]", "[1]"), tree.children.map { it.key })
        assertEquals("$[1].b.c", tree.children[1].children.single().children.single().path)
        assertEquals(null, DataTrees.jsonLines("nope"))
    }

    @Test
    fun `nothing to show for text that is not the format`() {
        assertNull(DataTrees.json("{oops"))
        assertNull(DataTrees.json("42"))
        assertNull(DataTrees.xml("<a>"))
    }
}
