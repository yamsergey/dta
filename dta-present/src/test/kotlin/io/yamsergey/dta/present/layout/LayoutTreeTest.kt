package io.yamsergey.dta.present.layout

import io.yamsergey.dta.present.StatementKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T033: the Layout view's tree and node detail, from captures of the fixture's screens. */
class LayoutTreeTest {
    private fun canned(name: String): JsonObject = Json.parseToJsonElement(javaClass.getResource("/canned/$name.json")!!.readText()).jsonObject
    private val mixed = LayoutTree.of(canned("layout-mixed"))

    /** The fixture's mixed screen: Compose inside XML, an AndroidView inside Compose. */
    private val composeText = listOf(0, 0, 0, 0, 1, 0, 0, 0, 0, 0)
    private val embeddedText = listOf(0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0)

    @Test
    fun `the header states screen, activity, source and detail`() {
        assertEquals("1080×2400 px · MainActivity · sidekick · detail composition", mixed.summary)
        assertEquals(emptyList(), mixed.statements)
    }

    @Test
    fun `views and composables are one tree, each row with its kind, name, ids, text and marks`() {
        val window = mixed.windows.single()
        assertEquals("window application \"DecorView\"", window.label)
        assertEquals("DecorView", window.root.row.name)
        assertEquals(NodeKind.VIEW, window.root.row.kind)

        val text = mixed.node(composeText)!!.row
        assertEquals(NodeKind.COMPOSE, text.kind)
        assertEquals("Text", text.name)
        assertEquals(listOf("testTag=mixed_compose_label", "#3"), text.ids)
        assertEquals("\"Compose in XML\"", text.text)
        assertEquals("ComposeScreens.kt:46", text.source)

        val embedded = mixed.node(embeddedText)!!.row
        assertEquals(NodeKind.VIEW, embedded.kind)
        assertEquals("TextView", embedded.name)
        assertEquals("\"Embedded AndroidView\"", embedded.text)

        val counter = mixed.node(listOf(0, 0, 0, 0, 0))!!.row
        assertEquals(listOf("io.yamsergey.dta.fixture.channel:id/disturbance_counter", "#2"), counter.ids)
        assertEquals("view TextView io.yamsergey.dta.fixture.channel:id/disturbance_counter #2 \"disturbances=1\"", counter.line)
    }

    @Test
    fun `interactions are marked and an unnamed composable says what it is`() {
        val compose = LayoutTree.of(canned("layout-compose"))
        val button = compose.all().first { it.row.name == "Button" }.row
        assertEquals(listOf("clickable", "focusable"), button.interactions)
        assertEquals("Composable", compose.all().first { it.row.kind == NodeKind.COMPOSE }.row.name)
    }

    @Test
    fun `the window root and two levels below it start expanded`() {
        assertEquals(setOf(listOf(0), listOf(0, 0)), mixed.initiallyExpanded, "leaves have nothing to expand")
    }

    @Test
    fun `a very large tree opens collapsed below two levels, and builds quickly`() {
        // 20 wide, 3 levels below the root: 1 + 20 + 400 + 8,000 = 8,421 nodes.
        fun node(depth: Int): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {
            put("kind", kotlinx.serialization.json.JsonPrimitive("view"))
            put("className", kotlinx.serialization.json.JsonPrimitive("android.widget.FrameLayout"))
            put("children", kotlinx.serialization.json.JsonArray(if (depth == 0) emptyList() else List(20) { node(depth - 1) }))
        }
        val capture = kotlinx.serialization.json.buildJsonObject {
            put("windows", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("application"))
                put("root", node(3))
            })))
        }
        val start = System.nanoTime()
        val tree = LayoutTree.of(capture)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(8_421, tree.all().count(), "every node is in the model")
        assertEquals(1 + 20, tree.initiallyExpanded.size, "the root and its 20 children: nothing deeper opens")
        assertTrue(ms < 2_000, "built in $ms ms")
    }

    @Test
    fun `a fallback capture is a banner with its reason, and its nodes are tagged fallback`() {
        val plain = LayoutTree.of(canned("layout-plain-fallback"))
        val fallback = plain.statements.single()
        assertEquals(StatementKind.FALLBACK, fallback.kind)
        assertTrue("running on emulator-5554 without DTA" in fallback.sentence, fallback.sentence)
        assertEquals("1080×2400 px · uiautomator", plain.summary)
        assertTrue(plain.all().all { it.row.kind == NodeKind.FALLBACK })
        assertEquals("caveat", NodeKind.FALLBACK.token)
    }

    @Test
    fun `reduced detail and degradation codes are stated verbatim`() {
        val reduced = LayoutTree.of(canned("layout-reduced"))
        assertEquals(listOf(StatementKind.DEGRADED, StatementKind.REDUCED_DETAIL), reduced.statements.map { it.kind })
        assertEquals("compose-tooling-absent", reduced.statements[0].code)
        assertEquals("detail composition reduced to semantic", reduced.statements[1].detail)
    }

    @Test
    fun `node detail reads bounds, ids, text, source, parameters, provenance, call chain and breadcrumb`() {
        val d = NodeDetail.of(mixed, composeText)!!
        assertEquals("Text", d.title)
        assertEquals(listOf("DecorView", "ActionBarOverlayLayout", "FrameLayout", "LinearLayout", "FrameLayout", "ComposeView", "AndroidComposeView", "Composable", "Column", "Text"), d.breadcrumb)
        assertTrue(d.fields.contains("Kind" to "compose"))
        assertTrue(d.fields.contains("Test tag" to "mixed_compose_label"))
        assertTrue(d.fields.contains("Text" to "\"Compose in XML\""))
        assertTrue(d.fields.contains("Provenance" to "semantics"))
        assertTrue(d.fields.any { it.first == "Bounds" && Regex("""-?\d+,-?\d+ – -?\d+,-?\d+ \(\d+×\d+\)""").matches(it.second) }, "${d.fields}")
        assertEquals(Source("ComposeScreens.kt", 46), d.source)
        assertEquals("text", d.parameters.first().name)
        assertEquals(listOf("MixedContent (MainActivity.kt:115)", "Column (ComposeScreens.kt:45)"), d.callChain.take(2).map { it.text })
        assertEquals(Source("MainActivity.kt", 115), d.callChain.first().source)
        assertEquals(emptyList(), d.statements)
    }

    @Test
    fun `a node of a call names where its parameters are, how many are at defaults, and what was cut`() {
        val capture = Json.parseToJsonElement(
            """{"screen":{"widthPx":100,"heightPx":100},"source":"sidekick","windows":[{"type":"application","zOrder":0,"root":
               {"type":"compose","id":54,"bounds":{"left":0,"top":0,"right":10,"bottom":10},"name":"CenterAlignedTopAppBar",
                "parameters":[{"name":"title","value":"<lambda>","type":"lambda"},
                              {"name":"style","value":"TextStyle(color=…","type":"TextStyle","cut":true},
                              {"name":"colors","value":"<TopAppBarColors>","type":"TopAppBarColors","default":true}],
                "defaultParameters":3,
                "children":[{"type":"compose","id":55,"bounds":{"left":0,"top":0,"right":10,"bottom":10},"name":"CenterAlignedTopAppBar","sameCallAs":54}]}}]}""",
        ).jsonObject
        val tree = LayoutTree.of(capture)
        val owner = NodeDetail.of(tree, listOf(0))!!
        assertTrue(owner.fields.contains("Defaults" to "3 parameters at their defaults, not listed"), "${owner.fields}")
        assertEquals("cut", owner.parameters.single { it.name == "style" }.note)
        assertEquals("default", owner.parameters.single { it.name == "colors" }.note)
        val inner = NodeDetail.of(tree, listOf(0, 0))!!
        assertTrue(inner.fields.contains("Same call as" to "#54 (its parameters are there)"), "${inner.fields}")
    }

    @Test
    fun `a view's detail has its class and resource id, no source`() {
        val d = NodeDetail.of(mixed, listOf(0, 0, 0, 0, 0))!!
        assertTrue(d.fields.contains("Class" to "android.widget.TextView"))
        assertTrue(d.fields.contains("Resource id" to "io.yamsergey.dta.fixture.channel:id/disturbance_counter"))
        assertNull(d.source)
        assertNull(NodeDetail.of(mixed, listOf(0, 9)))
    }

    @Test
    fun `054 - a source carries its package and origin, and the app calls above it are offered`() {
        val capture = Json.parseToJsonElement(
            """{"screen":{"widthPx":100,"heightPx":100},"source":"sidekick","windows":[{"type":"application","zOrder":0,"root":
               {"type":"compose","id":1,"bounds":{"left":0,"top":0,"right":10,"bottom":10},"name":"Screen","sourceFile":"Home.kt","lineNumber":10,"packageName":"io.example.ui","children":[
                 {"type":"compose","id":2,"bounds":{"left":0,"top":0,"right":10,"bottom":10},"name":"Header","sourceFile":"h.kt","lineNumber":3,"unknownOrigin":true,"children":[
                   {"type":"compose","id":3,"bounds":{"left":0,"top":0,"right":10,"bottom":10},"name":"Text","sourceFile":"Text.kt","lineNumber":70,"library":true,"packageName":"androidx.compose.material3",
                    "callChain":[{"name":"Screen","sourceFile":"Home.kt","lineNumber":10,"packageName":"io.example.ui"},{"name":"Text","sourceFile":"Text.kt","lineNumber":70,"library":true,"packageName":"androidx.compose.material3"},{"name":"Inner"}]}]}]}}]}""",
        ).jsonObject
        val tree = LayoutTree.of(capture)
        val d = NodeDetail.of(tree, listOf(0, 0, 0))!!
        assertEquals(Source("Text.kt", 70, "androidx.compose.material3", library = true), d.source)
        assertEquals(listOf("Screen (Home.kt:10)"), d.callers.map { it.text }, "app calls above only; unknown and library ones are not offered")
        assertEquals(Source("Home.kt", 10, "io.example.ui"), d.callers.single().source)
        assertEquals(listOf(Source("Home.kt", 10, "io.example.ui"), Source("Text.kt", 70, "androidx.compose.material3", library = true), null), d.callChain.map { it.source })
        assertTrue(d.fields.contains("Package" to "androidx.compose.material3"))
        val header = NodeDetail.of(tree, listOf(0, 0))!!
        assertEquals(Source("h.kt", 3, unknown = true), header.source)
        assertTrue(header.fields.contains("Origin" to "unknown (package not among the app's)"))
    }
}
