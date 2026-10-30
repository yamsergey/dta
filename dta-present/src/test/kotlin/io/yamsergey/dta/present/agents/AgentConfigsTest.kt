package io.yamsergey.dta.present.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The copy-only agent configurations of contract agent-server.md. */
class AgentConfigsTest {

    private val url = "http://127.0.0.1:12321/mcp"
    private val token = "tok-123"

    private fun config(agent: Agent) = AgentConfigs.render(agent, url, token)

    @Test
    fun `every agent of the contract is offered, Claude Code first`() {
        assertEquals(
            listOf("Claude Code", "Cursor", "Gemini in Android Studio", "Claude Desktop", "VS Code"),
            Agent.entries.map { it.title },
        )
    }

    @Test
    fun `Claude Code gets a command and a note about the DTA plugin`() {
        val config = config(Agent.CLAUDE_CODE)
        assertEquals("""claude mcp add --scope user --transport http dta $url --header "Authorization: Bearer $token"""", config.text)
        assertTrue("/plugin install dta@dta" in config.note!!, config.note)
    }

    @Test
    fun `JSON agents get valid JSON with the URL and the header, in their own file`() {
        val golden = mapOf(
            Agent.CURSOR to """{"mcpServers":{"dta":{"url":"$url","headers":{"Authorization":"Bearer $token"}}}}""",
            Agent.GEMINI_ANDROID_STUDIO to """{"mcpServers":{"dta":{"httpUrl":"$url","headers":{"Authorization":"Bearer $token"}}}}""",
            Agent.CLAUDE_DESKTOP to """{"mcpServers":{"dta":{"command":"npx","args":["-y","mcp-remote","$url","--header","Authorization: Bearer $token"]}}}""",
            Agent.VS_CODE to """{"servers":{"dta":{"type":"http","url":"$url","headers":{"Authorization":"Bearer $token"}}}}""",
        )
        for ((agent, expected) in golden) {
            val config = config(agent)
            assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(config.text), agent.title)
            assertNotNull(Json.parseToJsonElement(config.text).jsonObject)
            assertNotNull(config.where, agent.title)
        }
        assertEquals("~/.cursor/mcp.json", config(Agent.CURSOR).where)
        assertEquals(".vscode/mcp.json", config(Agent.VS_CODE).where)
    }

    @Test
    fun `JSON is pretty-printed for pasting`() {
        assertTrue("\n" in config(Agent.CURSOR).text)
    }

    @Test
    fun `the prompt asks the agent to set itself up, with the URL, the header and its own configuration`() {
        for (agent in Agent.entries) {
            val prompt = AgentConfigs.prompt(agent, url, token)
            assertTrue(url in prompt && "Authorization: Bearer $token" in prompt, agent.title)
            assertTrue(config(agent).text in prompt, "${agent.title}: the configuration itself")
            assertTrue("list" in prompt && "tools" in prompt, "${agent.title}: ends by checking the tools")
        }
        assertTrue("merge" in AgentConfigs.prompt(Agent.CURSOR, url, token).lowercase(), "JSON files are merged, not replaced")
        assertTrue("commit" in AgentConfigs.prompt(Agent.VS_CODE, url, token), "a project file must not carry the credential into git")
    }
}
