package io.yamsergey.dta.mcp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D-019: the scripting guide (the skill's scripting.md, served over MCP) states the pitfalls the
 * specs define, in prose, so a recipe's code cannot stand in for them.
 */
class McpGuidesTest {

    private val prose = McpGuides.all.single { it.uri == McpGuides.SCRIPT_API_URI }.text
        .replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), "").lowercase()

    @Test
    fun `the scripting guide states the pitfalls`() {
        val pitfalls = mapOf(
            "installing replaces the active script (005 FR-001)" to Regex("""replaces (the |any )?(previous|active|current|existing|earlier|installed) (network )?script"""),
            "it persists across app restarts until cleared (FR-004)" to Regex("""persists? across (app )?restarts?"""),
            "sleep blocks the thread that made the call (005 Assumptions)" to Regex("""sleep[^.\n]*\bblocks? the (calling|app's|request's|caller's|issuing)? ?thread"""),
            "calls in flight keep the script they started with (FR-015)" to Regex("""in[- ]flight[^.\n]*(keep|finish)"""),
            "a mocked request never goes on the wire (005 edge cases)" to Regex("""without the network|never (reaches|goes to) the server"""),
        )
        assertEquals(emptyList(), pitfalls.filterValues { !it.containsMatchIn(prose) }.keys.toList())
    }
}
