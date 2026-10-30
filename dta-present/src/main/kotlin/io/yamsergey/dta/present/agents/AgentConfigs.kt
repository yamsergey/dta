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
                text = """claude mcp add --scope user --transport http dta $url --header "Authorization: $bearer"""",
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

    /**
     * An instruction the person pastes into [agent] so it configures the server itself: what DTA
     * is, the endpoint and header, the agent's own configuration, and a check that the tools load.
     */
    fun prompt(agent: Agent, url: String, token: String): String {
        val config = render(agent, url, token)
        val step = when {
            config.where == null -> "Run this command:\n\n```\n${config.text}\n```"
            else -> "Add this server to ${config.where}. If the file already exists, merge the `dta` entry into it instead of replacing the file:\n\n```json\n${config.text}\n```"
        }
        val caution = when (agent) {
            Agent.VS_CODE -> "\n\nThis file is inside the project and holds a credential: make sure it is not committed (add it to .gitignore), or put the server in your user-level MCP configuration instead."
            else -> ""
        }
        return """
            |Set up the DTA MCP server for me (${agent.title}).
            |
            |DTA inspects a running Android app built with DTA inside: its screen as a View/Compose tree, and its HTTP and WebSocket traffic. Android Studio serves DTA's tools over MCP Streamable HTTP:
            |- URL: $url
            |- Every request needs the header `Authorization: Bearer $token`
            |
            |$step$caution
            |
            |Then check that the `dta` server connects and list its tools. The credential is a secret: keep it out of commits and shared files.
        """.trimMargin()
    }

    private fun JsonObjectBuilder.headers(bearer: String) =
        putJsonObject("headers") { put("Authorization", bearer) }

    private fun json(where: String, build: JsonObjectBuilder.() -> Unit): AgentConfig =
        AgentConfig(pretty.encodeToString(JsonObject.serializer(), buildJsonObject(build)), where, note = null)
}
