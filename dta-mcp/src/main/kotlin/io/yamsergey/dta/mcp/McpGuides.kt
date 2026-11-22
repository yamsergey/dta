package io.yamsergey.dta.mcp

import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.spec.McpSchema

/**
 * Guides served as MCP resources, for agents that reach DTA without the Claude Code skill (the
 * plugin's HTTP server, other agents). Their text is the skill's own file, copied into the jar at
 * build time (build.gradle.kts), so the two cannot drift.
 */
object McpGuides {
    const val SCRIPT_API_URI = "dta://network/script-api"

    class Guide(val uri: String, val name: String, val title: String, val description: String, private val path: String) {
        val text: String by lazy {
            McpGuides::class.java.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }
                ?: error("$path is not in the jar (dta-mcp processResources copies it from plugins/dta/skills/dta)")
        }
    }

    val all: List<Guide> = listOf(
        Guide(
            SCRIPT_API_URI, "network-script-api", "Network script API",
            "How to write a network script (network_script_set): handlers, objects, return values, globals, pushes and timers, with recipes.",
            "guides/scripting.md",
        ),
    )

    fun resources(): List<McpServerFeatures.SyncResourceSpecification> = all.map { guide ->
        McpServerFeatures.SyncResourceSpecification(
            McpSchema.Resource.builder().uri(guide.uri).name(guide.name).title(guide.title).description(guide.description).mimeType(MIME).build(),
        ) { _, _ -> McpSchema.ReadResourceResult(listOf(McpSchema.TextResourceContents(guide.uri, MIME, guide.text))) }
    }

    private const val MIME = "text/markdown"
}
