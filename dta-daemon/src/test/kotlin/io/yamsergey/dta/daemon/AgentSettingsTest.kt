package io.yamsergey.dta.daemon

import io.yamsergey.dta.daemon.server.ClientException
import io.yamsergey.dta.daemon.server.FakeOperations
import io.yamsergey.dta.daemon.server.OperationDispatcher
import io.yamsergey.dta.daemon.server.Selector
import io.yamsergey.dta.protocol.ops.ClientKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 072 A3 (FR-001, FR-003, FR-007): the agents' setting and the fingerprint key live in the DTA home; only the plugin changes the setting. */
class AgentSettingsTest {
    private val home = Files.createTempDirectory("dta-agents").toFile()

    @Test
    fun `redaction for agents is on when nothing is stored`() {
        assertTrue(AgentSettings(home).redaction)
    }

    @Test
    fun `the setting is kept across a restart`() {
        AgentSettings(home).setRedaction(false)
        assertEquals(false, AgentSettings(home).redaction, "a new daemon over the same home")
        AgentSettings(home).setRedaction(true)
        assertTrue(AgentSettings(home).redaction)
        assertEquals("""{"redaction":true}""", java.io.File(home, "agents.json").readText().trim().replace(Regex("\\s"), ""))
    }

    @Test
    fun `a file that cannot be read means on, not off`() {
        java.io.File(home, "agents.json").writeText("{ not json")
        assertTrue(AgentSettings(home).redaction)
    }

    @Test
    fun `the key is made once, 32 random bytes, owner only, and the same after a restart`() {
        val first = AgentSettings(home)
        val file = java.io.File(home, "fingerprint.key")
        assertTrue(!file.exists(), "made on first need, not before")
        val fingerprint = first.key.of("secret")
        assertEquals(32, file.length())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file.toPath()))
        val bytes = file.readBytes()
        assertEquals(fingerprint, AgentSettings(home).key.of("secret"), "the same key in a new daemon")
        assertTrue(file.readBytes().contentEquals(bytes), "kept, not rewritten")
        assertNotEquals(fingerprint, AgentSettings(Files.createTempDirectory("dta-agents2").toFile()).key.of("secret"), "another home, another key")
    }

    @Test
    fun `a key file of the wrong size is not used`() {
        java.io.File(home, "fingerprint.key").writeBytes(ByteArray(5))
        assertFailsWith<IllegalStateException> { AgentSettings(home).key }
    }

    // --- the operations

    private val fake = FakeOperations()
    private val dispatcher = OperationDispatcher(fake, Selector(fake::devices, fake::apps), agents = AgentSettings(home))

    private fun call(method: String, kind: ClientKind?, params: JsonObject? = null) = runBlocking { dispatcher.dispatch(method, params, kind) }

    @Test
    fun `any kind reads the setting`() {
        for (kind in ClientKind.entries) {
            assertEquals(true, call("agents.redaction", kind).result.jsonObject["redaction"]!!.jsonPrimitive.boolean, "$kind")
        }
    }

    @Test
    fun `the plugin changes it, and a read says so`() {
        val set = call("agents.redaction.set", ClientKind.IDE, buildJsonObject { put("redaction", false) }).result.jsonObject
        assertEquals(false, set["redaction"]!!.jsonPrimitive.boolean)
        assertEquals(false, call("agents.redaction", ClientKind.CLI).result.jsonObject["redaction"]!!.jsonPrimitive.boolean)
        assertEquals(false, AgentSettings(home).redaction, "kept in the home")
    }

    @Test
    fun `cli, mcp and the plugin's agent server cannot change it, and are told where`() {
        for (kind in listOf(ClientKind.CLI, ClientKind.MCP, ClientKind.MCP_HTTP, null)) {
            val e = assertFailsWith<ClientException> { call("agents.redaction.set", kind, buildJsonObject { put("redaction", false) }) }
            assertEquals("changed from the plugin's settings (Agents/MCP)", e.message, "$kind")
            assertEquals(true, AgentSettings(home).redaction, "$kind left it on")
        }
    }
}
