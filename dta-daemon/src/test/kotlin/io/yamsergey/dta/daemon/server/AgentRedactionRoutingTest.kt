package io.yamsergey.dta.daemon.server

import io.yamsergey.dta.daemon.AgentSettings
import io.yamsergey.dta.protocol.ops.AppInfo
import io.yamsergey.dta.protocol.ops.Catalog
import io.yamsergey.dta.protocol.ops.ClientKind
import io.yamsergey.dta.protocol.timeline.CauseLink
import io.yamsergey.dta.protocol.timeline.FacetValue
import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 072 A4 (FR-002): who is redacted is decided by the connection's kind and the agents' setting, never by the call.
 * `ide` is never redacted; `cli`, `mcp`, `mcp-http` follow the setting; no operation takes `redact` any more.
 */
class AgentRedactionRoutingTest {
    private val fake = FakeOperations()
    private val agents = AgentSettings.inMemory(true)
    private val dispatcher = OperationDispatcher(fake, Selector(fake::devices, fake::apps), agents = agents)

    private fun call(method: String, kind: ClientKind?, params: JsonObject = JsonObject(emptyMap())) = runBlocking { dispatcher.dispatch(method, params, kind) }

    private val agentKinds = listOf(ClientKind.CLI, ClientKind.MCP, ClientKind.MCP_HTTP, null)

    @Test
    fun `no operation takes redact or allowUnredacted any more`() {
        for (op in Catalog.all) {
            assertNull(op.param("redact"), "${op.name} still takes redact")
            assertNull(op.param("allowUnredacted"), "${op.name} still takes allowUnredacted")
        }
        for (name in listOf("redact", "allowUnredacted")) {
            val e = kotlin.test.assertFailsWith<ClientException> { call("network.list", ClientKind.CLI, buildJsonObject { put(name, true) }) }
            assertTrue("unknown parameter '$name'" in e.message!!, e.message)
        }
    }

    /** What each operation that took `redact` was asked, read from what the library saw. */
    private fun redactedAsked(op: String): Boolean {
        fake.lastQuery = null; fake.lastEntryRedact = null; fake.lastBodyRedact = null
        when (op) {
            "network.search" -> { call(op, kindUnderTest, buildJsonObject { put("q", "word") }); return fake.searchQuery!!.redact }
            "network.list", "links.list" -> { call(op, kindUnderTest); return fake.lastQuery!!.redact }
            "network.entry", "links.entry" -> { call(op, kindUnderTest, buildJsonObject { put("id", "r1-1:1") }); return fake.lastEntryRedact!! }
            "network.body" -> { call(op, kindUnderTest, buildJsonObject { put("id", "r1-1:1") }); return fake.lastBodyRedact!! }
            "timeline.sequence" -> { call(op, kindUnderTest); return "redact" in fake.lastTimeline!!.last().toString() }
            else -> error(op)
        }
    }

    private var kindUnderTest: ClientKind? = null

    @Test
    fun `every library read follows the connection kind and the setting`() {
        val ops = listOf("network.list", "network.search", "links.list", "network.entry", "links.entry", "network.body", "timeline.sequence")
        for (op in ops) {
            for (kind in ClientKind.entries + listOf<ClientKind?>(null)) {
                for (setting in listOf(true, false)) {
                    agents.setRedaction(setting)
                    kindUnderTest = kind
                    val expected = kind != ClientKind.IDE && setting
                    assertEquals(expected, redactedAsked(op), "$op as $kind with the setting $setting")
                }
            }
        }
    }

    private val secret = listOf(TimelineEvent(
        "7:6", 6, 6, 6, "network", "network/failed", title = "POST /login failed: token=SEED-TL1",
        causes = listOf(CauseLink("7:5", Basis.INFERRED, "text-match", detail = "password=SEED-TL2")),
    ))

    @Test
    fun `timeline and state answers are masked for agents, whole for the plugin, and say which`() {
        fake.timelineEvents = secret
        fake.currentFacets = listOf(FacetValue("nav.args", "token=SEED-TL3", "nav", 1, 1))
        val asked = listOf(
            "timeline.events" to buildJsonObject { },
            "timeline.causes" to buildJsonObject { put("event", "7:6") },
            "state.current" to buildJsonObject { },
            "state.graph" to buildJsonObject { },
            "state.path" to buildJsonObject { put("from", "s0") },
        )
        for ((op, params) in asked) {
            agents.setRedaction(true)
            val plugin = call(op, ClientKind.IDE, params).result.jsonObject
            assertTrue("SEED-TL" in plugin.toString(), "$op to the plugin is whole: $plugin")
            assertNull(plugin["redaction"], "$op to the plugin carries no redaction")
            for (kind in agentKinds) {
                val agent = call(op, kind, params).result.jsonObject
                assertFalse("SEED-TL" in agent.toString(), "$op to $kind: $agent")
                assertEquals(true, agent["redaction"]!!.jsonObject["applied"]!!.jsonPrimitive.boolean, "$op to $kind says it is redacted")
            }
            agents.setRedaction(false)
            for (kind in agentKinds) {
                val agent = call(op, kind, params).result.jsonObject
                assertTrue("SEED-TL" in agent.toString(), "$op to $kind with the setting off is whole: $agent")
                val statement = agent["redaction"]!!.jsonObject
                assertEquals(false, statement["applied"]!!.jsonPrimitive.boolean, "$op to $kind says the setting is off")
                assertEquals(true, statement["forAgents"]!!.jsonPrimitive.boolean)
            }
        }
    }

    @Test
    fun `library answers to agents with the setting off say so too`() {
        agents.setRedaction(false)
        for ((op, params) in listOf("network.list" to buildJsonObject { }, "network.entry" to buildJsonObject { put("id", "r1-1:1") }, "network.body" to buildJsonObject { put("id", "r1-1:1") })) {
            val agent = call(op, ClientKind.MCP, params).result.jsonObject
            assertEquals(false, agent["redaction"]!!.jsonObject["applied"]!!.jsonPrimitive.boolean, "$op: $agent")
            assertNull(call(op, ClientKind.IDE, params).result.jsonObject["redaction"], "$op to the plugin says nothing")
        }
    }

    @Test
    fun `the sequence is built redacted for agents`() {
        call("timeline.sequence", ClientKind.MCP)
        assertEquals("redact", fake.lastTimeline!!.last())
        call("timeline.sequence", ClientKind.IDE)
        assertNotNull(fake.lastTimeline)
        assertTrue("redact" !in fake.lastTimeline!!.map { it.toString() })
    }

    @Test
    fun `an export follows the setting for agents, is redacted for the plugin unless it chooses otherwise, and unredacted is the plugin's alone`() {
        val out = File.createTempFile("dta-export", ".har").also { it.delete() }.path
        fun exportAs(kind: ClientKind?, unredacted: Boolean?): Pair<Boolean?, Boolean?> {
            fake.lastEntryRedact = null; fake.lastBodyRedact = null; fake.lastQuery = null
            call("network.export", kind, buildJsonObject { put("output", out); unredacted?.let { put("unredacted", it) } })
            return fake.lastQuery?.redact to fake.lastEntryRedact
        }
        for (setting in listOf(true, false)) {
            agents.setRedaction(setting)
            for (kind in agentKinds) assertEquals(setting, exportAs(kind, null).first, "$kind exports with the setting $setting")
            assertEquals(true, exportAs(ClientKind.IDE, null).first, "the plugin's export is redacted by default (setting $setting)")
            assertEquals(false, exportAs(ClientKind.IDE, true).first, "unless it chose Unredacted")
            assertEquals(true, exportAs(ClientKind.IDE, false).first)
            for (kind in agentKinds) {
                val e = kotlin.test.assertFailsWith<ClientException> { exportAs(kind, true) }
                assertTrue("plugin" in e.message!!, "$kind: ${e.message}")
            }
        }
    }
}
