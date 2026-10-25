package io.yamsergey.dta.sidekick.layout

import io.yamsergey.dta.protocol.layout.ComposeNode
import io.yamsergey.dta.protocol.layout.DegradationCodes
import io.yamsergey.dta.protocol.layout.DetailLevel
import io.yamsergey.dta.protocol.layout.Node
import io.yamsergey.dta.protocol.layout.Provenance
import io.yamsergey.dta.protocol.layout.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Per-host level resolution and degradation assembly (feature 003 US2). */
class LevelDegradationTest {

    private fun semanticWalk(host: Long) =
        ComposeWalkResult(host, ComposeNode(id = 1, bounds = Rect(0, 0, 1, 1), children = listOf(ComposeNode(id = 2, bounds = Rect(0, 0, 1, 1)))))

    private fun enrichedWalk(host: Long) = ComposeWalkResult(
        host,
        ComposeNode(
            id = 1, bounds = Rect(0, 0, 1, 1),
            children = listOf(ComposeNode(id = 3, bounds = Rect(0, 0, 1, 1), name = "Row", provenance = Provenance.TOOLING)),
        ),
    )

    private fun ok(host: Long, level: DetailLevel = DetailLevel.LAYOUT, sourceInfo: Boolean = true) =
        Result.success(ToolingOutcome(enrichedWalk(host), level, sourceInfoPresent = sourceInfo))

    private fun Node.codes() = degraded.orEmpty().map { it.reason }

    private fun Node.hasTooling(): Boolean =
        (this as? ComposeNode)?.provenance == Provenance.TOOLING || children.any { it.hasTooling() }

    @Test
    fun `a host whose tooling read throws is served at semantic with both reasons on its root`() {
        val host = LevelResolution.resolveHost(
            DetailLevel.LAYOUT, semanticWalk(9), Result.failure(IllegalStateException("no CompositionData on host")),
        )
        assertEquals(DetailLevel.SEMANTIC, host.served)
        assertEquals(
            listOf(DegradationCodes.COMPOSE_ENRICHMENT_FAILED, DegradationCodes.COMPOSE_DETAIL_REDUCED),
            host.walk.tree.codes(),
        )
        assertTrue(!host.walk.tree.hasTooling(), "partial tooling data must never ship for a failed host")
        assertTrue(host.enrichmentFailed)
    }

    @Test
    fun `a healthy host is served at the requested level without markers`() {
        val host = LevelResolution.resolveHost(DetailLevel.LAYOUT, semanticWalk(8), ok(8))
        assertEquals(DetailLevel.LAYOUT, host.served)
        assertNull(host.walk.tree.degraded)
        assertTrue(host.walk.tree.hasTooling())
    }

    @Test
    fun `delivered is the minimum level across hosts and requested when there are none`() {
        val failed = LevelResolution.resolveHost(DetailLevel.LAYOUT, semanticWalk(9), Result.failure(RuntimeException()))
        val healthy = LevelResolution.resolveHost(DetailLevel.LAYOUT, semanticWalk(8), ok(8))
        assertEquals(DetailLevel.SEMANTIC, LevelResolution.delivered(DetailLevel.LAYOUT, listOf(healthy, failed)))
        assertEquals(DetailLevel.LAYOUT, LevelResolution.delivered(DetailLevel.LAYOUT, listOf(healthy)))
        assertEquals(DetailLevel.COMPOSITION, LevelResolution.delivered(DetailLevel.COMPOSITION, emptyList()))
    }

    @Test
    fun `tooling unavailable reduces richer requests on every host root`() {
        val host = LevelResolution.resolveHost(DetailLevel.COMPOSITION, semanticWalk(7), tooling = null)
        assertEquals(DetailLevel.SEMANTIC, host.served)
        assertEquals(listOf(DegradationCodes.COMPOSE_DETAIL_REDUCED), host.walk.tree.codes())
        assertTrue(!host.enrichmentFailed)
    }

    @Test
    fun `semantic requests are never reported as reduced`() {
        val unavailable = LevelResolution.resolveHost(DetailLevel.SEMANTIC, semanticWalk(7), tooling = null)
        assertNull(unavailable.walk.tree.degraded)
        val failed = LevelResolution.resolveHost(DetailLevel.SEMANTIC, semanticWalk(7), Result.failure(RuntimeException()))
        assertEquals(listOf(DegradationCodes.COMPOSE_ENRICHMENT_FAILED), failed.walk.tree.codes())
    }

    @Test
    fun `missing source info is reported while containers are still delivered`() {
        val host = LevelResolution.resolveHost(DetailLevel.LAYOUT, semanticWalk(8), ok(8, sourceInfo = false))
        assertEquals(DetailLevel.LAYOUT, host.served)
        assertEquals(listOf(DegradationCodes.COMPOSE_SOURCE_INFO_MISSING), host.walk.tree.codes())
        assertTrue(host.walk.tree.hasTooling())
    }

    @Test
    fun `existing root markers are kept`() {
        val walk = semanticWalk(9).let {
            it.copy(tree = (it.tree as ComposeNode).copy(degraded = listOf(io.yamsergey.dta.protocol.rpc.DegradationMarker("layout", DegradationCodes.DEPTH_CAP_REACHED))))
        }
        val host = LevelResolution.resolveHost(DetailLevel.LAYOUT, walk, tooling = null)
        assertEquals(listOf(DegradationCodes.DEPTH_CAP_REACHED, DegradationCodes.COMPOSE_DETAIL_REDUCED), host.walk.tree.codes())
    }
}
