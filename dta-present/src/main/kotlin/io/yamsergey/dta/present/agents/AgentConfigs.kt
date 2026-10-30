package io.yamsergey.dta.present.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The agents the agent server offers a configuration for (contract agent-server.md), in display order. */
enum class Agent(val title: String) {
    CLAUDE_CODE("Claude Code"),
    CURSOR("Cursor"),
    GEMINI_ANDROID_STUDIO("Gemini in Android Studio"),
    CLAUDE_DESKTOP("Claude Desktop"),
    VS_CODE("VS Code"),
}

/** What the person copies: [text], where it goes ([where], null for a command), and an optional [note]. */
data class AgentConfig(val text: String, val where: String?, val note: String?)

/**
 * The configuration each agent needs to reach the agent server (FR-019). Copy-only: DTA never
 * writes into an agent's files. IDE-free, so the CLI can offer the same text later.
 */
object AgentConfigs {
    private val pretty = Json { prettyPrint = true }

    fun render(agent: Agent, url: String, token: String): AgentConfig {
        val bearer = "Bearer $token"
        return when (agent) {
            Agent.CLAUDE_CODE -> AgentConfig(
                text = """claude mcp add --transport http dta $url --header "Authorization: $bearer"""",
                where = null,
                note = "Or install the DTA plugin for Claude Code (/plugin install dta@dta), which runs `dta mcp` and needs no server here.",
            )
            Agent.CURSOR -> json("~/.cursor/mcp.json") {
                putJsonObject("mcpServers") { putJsonObject("dta") { put("url", url); headers(bearer) } }
            }
            Agent.GEMINI_ANDROID_STUDIO -> json("Settings › Tools › AI › MCP Servers (saved to mcp.json)") {
                putJsonObject("mcpServers") { putJsonObject("dta") { put("httpUrl", url); headers(bearer) } }
            }
            Agent.CLAUDE_DESKTOP -> json("claude_desktop_config.json") {
                putJsonObject("mcpServers") {
                    putJsonObject("dta") {
                        put("command", "npx")
                        putJsonArray("args") { listOf("-y", "mcp-remote", url, "--header", "Authorization: $bearer").forEach { add(JsonPrimitive(it)) } }
                    }
                }
            }
            Agent.VS_CODE -> json(".vscode/mcp.json") {
                putJsonObject("servers") { putJsonObject("dta") { put("type", "http"); put("url", url); headers(bearer) } }
            }
        }
    }

    private fun JsonObjectBuilder.headers(bearer: String) =
        putJsonObject("headers") { put("Authorization", bearer) }

    private fun json(where: String, build: JsonObjectBuilder.() -> Unit): AgentConfig =
        AgentConfig(pretty.encodeToString(JsonObject.serializer(), buildJsonObject(build)), where, note = null)
}
