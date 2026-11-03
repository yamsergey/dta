package io.yamsergey.dta.present.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphLayoutTest {
    private val measure: (String) -> Int = { it.length * 7 }
    private val style = GraphStyle()
    private val root = DataTrees.json("""{"user":{"name":"Ann","tags":["a","b"]},"items":[{"id":1},{"id":2}],"ok":true}""")!!

    private fun layout(expanded: Set<String>) = GraphLayout.layout(root, expanded, measure, style)

    @Test
    fun `one card per shown object or array, a row per member, an edge from a row to its card`() {
        val g = layout(setOf("$.user", "$.items"))
        assertEquals(listOf("$", "$.user", "$.items"), g.cards.map { it.path })
        val top = g.card("$")!!
        assertEquals(listOf("user" to "{2}", "items" to "[2]", "ok" to "true"), top.rows.map { it.key to it.text })
        assertTrue(top.rows[0].expanded)
        assertFalse(top.rows[2].container)
        assertEquals(listOf(GraphEdge("$", 0, "$.user"), GraphEdge("$", 1, "$.items")), g.edges)
        // $.user.tags is collapsed: a row, no card.
        assertFalse(g.card("$.user")!!.rows[1].expanded)
        assertNull(g.card("$.user.tags"))
    }

    @Test
    fun `left to right by depth, a parent centred on its children, no two cards overlap`() {
        val g = layout(GraphLayout.expandAll(root).paths)
        val byDepth = g.cards.groupBy { it.depth }
        // Every card of a column starts at the same x, right of the previous column.
        byDepth.values.forEach { column -> assertEquals(1, column.map { it.x }.distinct().size) }
        assertTrue(byDepth.getValue(1).first().x >= byDepth.getValue(0).first().let { it.x + it.width } + style.gapX)
        // Centred: the root's middle lies within its children's span.
        val kids = g.cards.filter { it.depth == 1 }
        val top = g.card("$")!!
        assertTrue(top.y + top.height / 2 in kids.minOf { it.y }..kids.maxOf { it.y + it.height })
        g.cards.forEach { a ->
            g.cards.filter { it !== a && it.x == a.x }.forEach { b -> assertTrue(a.y + a.height <= b.y || b.y + b.height <= a.y, "${a.path} overlaps ${b.path}") }
        }
        assertTrue(g.cards.all { it.x + it.width <= g.width && it.y + it.height <= g.height })
    }

    @Test
    fun `a card is as wide as its widest row, within bounds`() {
        val g = layout(emptySet())
        val top = g.card("$")!!
        assertEquals(style.padding * 2 + measure("items: [2]").coerceAtLeast(measure("user: {2}")).coerceAtLeast(style.minWidth - style.padding * 2), top.width)
        val wide = DataTrees.json("""{"k":"${"x".repeat(500)}"}""")!!
        assertEquals(style.maxWidth, GraphLayout.layout(wide, emptySet(), measure, style).card("$")!!.width)
        assertEquals(style.titleHeight + 3 * style.rowHeight + style.padding, top.height)
    }

    @Test
    fun `long arrays show the first rows and "+N more", which expands`() {
        val big = DataTrees.json((1..120).joinToString(",", "[", "]"))!!
        val collapsed = GraphLayout.layout(big, emptySet(), measure, style).card("$")!!
        assertEquals(style.maxRows + 1, collapsed.rows.size)
        assertEquals("+100 more", collapsed.rows.last().text)
        assertEquals(100, collapsed.rows.last().more)
        val all = GraphLayout.layout(big, GraphLayout.toggle(emptySet(), GraphLayout.moreKey("$")), measure, style).card("$")!!
        assertEquals(120, all.rows.size)
    }

    @Test
    fun `big bodies open two levels deep and at most so many cards`() {
        val deep = DataTrees.json("""{"a":{"b":{"c":{"d":1}}}}""")!!
        assertEquals(setOf("$.a", "$.a.b"), GraphLayout.defaultExpanded(deep))
        val wide = DataTrees.json((1..300).joinToString(",", "[", "]") { "{\"i\":$it}" })!!
        val expansion = GraphLayout.expandAll(wide, maxCards = 200)
        assertTrue(expansion.truncated)
        assertEquals(199, expansion.paths.count { !it.endsWith(GraphLayout.MORE) }) // + the root card = 200
        assertTrue(GraphLayout.defaultExpanded(wide, maxCards = 10).size <= 9)
    }

    @Test
    fun `by default an array opens only its first items, and so many cards in all`() {
        val catalog = DataTrees.json("""{"items":[${(1..60).joinToString(",") { "{\"id\":$it,\"tags\":[\"a\"]}" }}],"page":{"n":1}}""")!!
        val open = GraphLayout.defaultExpanded(catalog)
        assertEquals(setOf("$.items", "$.items[0]", "$.items[1]", "$.items[2]", "$.page"), open)
        assertTrue(GraphLayout.defaultExpanded(catalog, depth = 5).size <= GraphLayout.DEFAULT_CARDS)
    }

    @Test
    fun `toggling a row adds or removes its card and everything under it`() {
        val open = GraphLayout.toggle(setOf("$.user", "$.user.tags"), "$.user")
        assertEquals(emptySet(), open)
        assertEquals(setOf("$.items"), GraphLayout.toggle(emptySet(), "$.items"))
    }

    @Test
    fun `XML elements are cards too, attributes and text-only children are rows`() {
        val xml = DataTrees.xml("""<feed lang="en"><entry id="1"><title>One</title></entry></feed>""")!!
        val g = GraphLayout.layout(xml, GraphLayout.defaultExpanded(xml), measure, style)
        assertEquals(listOf("/feed", "/feed/entry"), g.cards.map { it.path })
        assertEquals("<feed>", g.card("/feed")!!.title)
        assertEquals(listOf("@id" to "\"1\"", "title" to "\"One\""), g.card("/feed/entry")!!.rows.map { it.key to it.text })
    }
}
