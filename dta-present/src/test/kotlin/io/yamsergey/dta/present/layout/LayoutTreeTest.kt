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
        assertEquals(listOf("MixedContent (MainActivity.kt:115)", "Column (ComposeScreens.kt:45)"), d.callChain.take(2))
        assertEquals(emptyList(), d.statements)
    }

    @Test
    fun `a view's detail has its class and resource id, no source`() {
        val d = NodeDetail.of(mixed, listOf(0, 0, 0, 0, 0))!!
        assertTrue(d.fields.contains("Class" to "android.widget.TextView"))
        assertTrue(d.fields.contains("Resource id" to "io.yamsergey.dta.fixture.channel:id/disturbance_counter"))
        assertNull(d.source)
        assertNull(NodeDetail.of(mixed, listOf(0, 9)))
    }
}
