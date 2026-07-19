package io.yamsergey.dta.sidekick.layout.compose

import io.yamsergey.dta.protocol.layout.ComposeNode
import io.yamsergey.dta.protocol.layout.Node
import io.yamsergey.dta.protocol.layout.Parameter
import io.yamsergey.dta.protocol.layout.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnrichmentOverlayTest {

    private fun compose(semId: Long, name: String? = null, children: List<Node> = emptyList()) =
        ComposeNode(id = semId, bounds = Rect(0, 0, 10, 10), name = name, children = children)

    private val tier1Tree = compose(1, children = listOf(compose(2), compose(3)))

    @Test
    fun `overlay applies name, source and params by semantics id`() {
        val overlay = mapOf(
            2L to EnrichmentEntry("SubmitButton", "Login.kt", 42, listOf(Parameter("enabled", "true", "Boolean")), library = false),
            3L to EnrichmentEntry("Text", "Text.kt", 10, emptyList(), library = true),
        )
        val enriched = EnrichmentOverlay.applyOrNull(tier1Tree, overlay)!!

        val n2 = enriched.children[0] as ComposeNode
        assertEquals("SubmitButton", n2.name)
        assertEquals("Login.kt", n2.sourceFile)
        assertEquals(42, n2.lineNumber)
        assertEquals(false, n2.library)
        assertEquals("enabled", n2.parameters!!.single().name)

        val n3 = enriched.children[1] as ComposeNode
        assertEquals(true, n3.library)
    }

    @Test
    fun `a null overlay entry (no match) leaves that node at tier 1`() {
        val overlay = mapOf(2L to EnrichmentEntry("Known", "F.kt", 1, emptyList(), false))
        val enriched = EnrichmentOverlay.applyOrNull(tier1Tree, overlay)!!
        val n3 = enriched.children[1] as ComposeNode
        assertNull(n3.name)
        assertNull(n3.sourceFile)
    }

    @Test
    fun `all-or-none - a thrown overlay build discards enrichment entirely`() {
        // Simulate a Tier-2 failure: the producer throws while building the overlay.
        val result = EnrichmentOverlay.buildOrNull { throw RuntimeException("mapTree exploded on version skew") }
        assertNull(result, "any Tier-2 error must yield no overlay (caller attaches compose-enrichment-failed)")
    }

    @Test
    fun `successful overlay build returns the map`() {
        val result = EnrichmentOverlay.buildOrNull { mapOf(1L to EnrichmentEntry("A", "A.kt", 1, emptyList(), false)) }
        assertTrue(result!!.containsKey(1L))
    }
}
