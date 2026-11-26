package io.yamsergey.dta.protocol.ops

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OperationCatalogTest {

    @Test
    fun `every entry is well formed and unique`() {
        val names = Catalog.all.map { it.name }
        assertEquals(names.distinct(), names)
        Catalog.all.forEach { op ->
            assertTrue(Regex("""[a-z]+(\.[a-z]+)+""").matches(op.name), op.name)
            assertTrue(op.description.length > 20, "${op.name} needs a real description")
            op.params.forEach { p ->
                assertTrue(p.description.isNotBlank(), "${op.name}.${p.name}")
                if (p.type == ParamType.ENUM || p.type == ParamType.ENUM_LIST) assertTrue(p.enumValues.isNotEmpty(), "${op.name}.${p.name}")
            }
            assertEquals(op.params.map { it.name }.distinct(), op.params.map { it.name }, op.name)
            assertTrue(op.params.none { it.name == "device" || it.name == "app" }, "device/app come from the scope, not params: ${op.name}")
        }
    }

    @Test
    fun `the 008 operation set plus apps watch (009) and the archive (011) and app data (027)`() {
        assertEquals(
            setOf(
                "devices.list", "apps.list", "apps.watch", "capability.report", "layout.tree", "layout.watch", "layout.selection", "layout.select",
                "network.list", "network.search", "network.entry", "network.body", "network.clear", "network.history", "network.history.set", "network.export", "network.watch",
                "network.script.set", "network.script.clear", "network.script.status", "network.script.logs",
                "network.ws.push",
                "network.schema.add", "network.schema.list", "network.schema.remove", "network.schema.sync",
                "network.mapping.add", "network.mapping.list", "network.mapping.remove",
                "network.format.add", "network.format.test", "network.format.list", "network.format.remove",
                "network.filters.list", "network.filters.add", "network.filters.remove",
                "links.list", "links.entry", "links.open", "links.verification",
                "archive.list", "archive.delete",
                "timeline.events", "timeline.watch", "timeline.causes", "timeline.sequence", "timeline.hidden",
                "web.capture", "web.status", "web.reload", "web.devtools",
                "timeline.flow.start", "timeline.enabled", "timeline.flow.end", "timeline.flow.list", "timeline.flow.rename", "timeline.flow.remove",
                "state.current", "state.graph", "state.path",
                "daemon.status", "daemon.stop", "agents.redaction", "agents.redaction.set",
                "data.list", "data.db.schema", "data.db.query", "data.db.exec",
                "data.prefs.read", "data.prefs.set", "data.prefs.remove", "data.proto.read", "data.proto.write",
                "data.files.list", "data.files.read", "data.files.write", "data.files.mkdir", "data.files.delete",
                "runtime.activities", "runtime.navigation", "runtime.navigate", "runtime.back", "runtime.viewmodels",
                "runtime.vm.set", "runtime.saved.set", "runtime.memory", "runtime.threads", "runtime.di",
                "runtime.functions", "runtime.invoke", "runtime.heap.snapshot", "runtime.heap.snapshots", "runtime.heap.compare",
            ),
            Catalog.all.map { it.name }.toSet(),
        )
    }

    @Test
    fun `tool names and cli paths`() {
        assertEquals("network_script_set", Catalog["network.script.set"].toolName)
        assertEquals(listOf("network", "script", "set"), Catalog["network.script.set"].cliPath)
        assertEquals(listOf("devices"), Catalog["devices.list"].cliPath)
        assertEquals(listOf("layout"), Catalog["layout.tree"].cliPath)
        assertEquals(listOf("capability"), Catalog["capability.report"].cliPath)
        assertEquals(listOf("network", "list"), Catalog["network.list"].cliPath)
        assertEquals(listOf("network", "history"), Catalog["network.history"].cliPath)
        assertEquals(listOf("network", "history", "set"), Catalog["network.history.set"].cliPath)
        assertEquals("network_history_set", Catalog["network.history.set"].toolName)
        assertEquals("timeline_events", Catalog["timeline.events"].toolName)
        assertEquals(listOf("timeline", "events"), Catalog["timeline.events"].cliPath)
        assertEquals(listOf("timeline", "watch"), Catalog["timeline.watch"].cliPath)
        assertEquals(listOf("timeline", "causes"), Catalog["timeline.causes"].cliPath)
        assertEquals(listOf("state"), Catalog["state.current"].cliPath)
        assertEquals(listOf("state", "graph"), Catalog["state.graph"].cliPath)
        assertEquals("state_graph", Catalog["state.graph"].toolName)
        assertEquals("network_ws_push", Catalog["network.ws.push"].toolName)
        assertEquals(listOf("network", "ws", "push"), Catalog["network.ws.push"].cliPath)
        val push = Catalog["network.ws.push"]
        assertEquals(listOf("connection", "text", "base64", "waitMs"), push.params.map { it.name })
        assertTrue(push.param("text")!!.positional)
    }

    @Test
    fun `heap operations take no snapshot as a side effect and have cli paths`() {
        assertEquals(listOf("name", "gc", "maxPause"), Catalog["runtime.heap.snapshot"].params.map { it.name })
        assertEquals(listOf("runtime", "heap", "snapshot"), Catalog["runtime.heap.snapshot"].cliPath)
        assertEquals(listOf("runtime", "heap", "list"), Catalog["runtime.heap.snapshots"].cliPath)
        assertEquals("runtime_heap_compare", Catalog["runtime.heap.compare"].toolName)
        assertEquals(listOf("a", "b", "origin", "package", "top"), Catalog["runtime.heap.compare"].params.map { it.name })
        assertTrue(Catalog["runtime.heap.compare"].param("a")!!.positional)
    }

    @Test
    fun `agent tools exclude stop and the watches`() {
        val tools = Catalog.agentTools.map { it.name }
        assertTrue("daemon.stop" !in tools && "network.watch" !in tools && "apps.watch" !in tools && "timeline.watch" !in tools && "layout.watch" !in tools && "agents.redaction.set" !in tools, "the setting is changed from the plugin, not offered to agents")
        assertEquals(Catalog.all.size - 6, tools.size)
    }

    @Test
    fun `json schema per param type and scope`() {
        val schema = Catalog["network.list"].inputSchema()
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        val props = schema["properties"]!!.jsonObject
        assertEquals("string", props["url"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("integer", props["limit"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("boolean", props["archive"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue("redact" !in props, "no per-call redaction (072)")
        val kinds = props["kinds"]!!.jsonObject
        assertEquals("array", kinds["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("http", "ws-connection", "ws-message"), kinds["items"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue("device" in props && "app" in props, "app-scoped tools take device and app")

        val entry = Catalog["network.entry"].inputSchema()
        assertEquals(listOf("id"), entry["required"]!!.jsonArray.map { it.jsonPrimitive.content })

        val devices = Catalog["devices.list"].inputSchema()["properties"]!!.jsonObject
        assertTrue("device" !in devices && "app" !in devices)
        val apps = Catalog["apps.list"].inputSchema()["properties"]!!.jsonObject
        assertTrue("device" in apps && "app" !in apps)

        val sources = Catalog["network.schema.add"].inputSchema()["properties"]!!.jsonObject["sources"]!!.jsonObject
        assertEquals("object", sources["type"]!!.jsonPrimitive.content)
        assertEquals("string", sources["additionalProperties"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `selected envelope round trips`() {
        val json = Json.encodeToString(Selected.serializer(), Selected(SelectionStatement("emulator-5554", DeviceRule.ONLY_DEVICE, "com.example", AppRule.FOREGROUND), JsonObject(emptyMap())))
        assertTrue("\"deviceRule\":\"only-device\"" in json && "\"appRule\":\"foreground\"" in json, json)
    }

    @Test
    fun `timeline answers take Network's opt-out from its saved filters (065)`() {
        val network = Catalog["network.list"].param("ignoreSavedFilters")!!
        for (op in listOf("timeline.events", "timeline.sequence", "timeline.causes", "timeline.hidden")) {
            val p = Catalog[op].param("ignoreSavedFilters") ?: error("$op has no ignoreSavedFilters")
            assertEquals(ParamType.BOOLEAN, p.type, op)
            assertEquals(network.cliName, p.cliName, op)
        }
    }

    /** D-016 / 051 FR-003: bodyContains ignores case in text (and decoded) bodies, as body: does. */
    @Test
    fun `bodyContains says that case is ignored`() {
        for (op in listOf("network.list", "network.export")) {
            val text = Catalog[op].param("bodyContains")!!.description.lowercase()
            assertTrue("case-sensitive" !in text, "$op bodyContains: $text")
            assertTrue("case ignored" in text, "$op bodyContains: $text")
        }
    }

    /** D-017 / 067 contract, 069 R13, 070: what view does now — a different query supersedes the running pass, the same query does not. */
    @Test
    fun `view says a different query supersedes the running pass and the same query does not`() {
        for (op in listOf("network.list", "network.search")) {
            val text = Catalog[op].param("view")!!.description.lowercase()
            assertTrue("different query" in text && "supersed" in text, "$op view: $text")
            assertTrue("same query" in text && "next page" in text, "$op view: $text")
            assertTrue("recent" !in text && "narrower" !in text, "$op view: 069 R13 removed match-set reuse: $text")
        }
    }

    private fun texts(op: OperationSpec) = listOf(op.description) + op.params.map { it.description }

    /**
     * Texts name other operations so each surface can call them: an agent sees tool names
     * (network_list — it has no network.list to call), the CLI its commands (dta network list).
     */
    @Test
    fun `agent texts name operations by tool name and cli texts by command`() {
        val dotted = Regex("""(?<![\w.])(${Catalog.all.sortedByDescending { it.name.length }.joinToString("|") { Regex.escape(it.name) }})(?![\w-]|\.[a-z])""")
        val tool = Regex("""\b(${Catalog.all.joinToString("|") { it.toolName }})\b""")
        val agentLeaks = Catalog.agentTools.flatMap { op -> texts(op).flatMap { t -> dotted.findAll(t).map { "${op.toolName}: ${it.value}" } } }
        assertEquals(emptyList(), agentLeaks, "catalog names in agent texts")
        val cliLeaks = Catalog.cliCommands.flatMap { op -> texts(op).flatMap { t -> (dotted.findAll(t) + tool.findAll(t)).map { "${op.name}: ${it.value}" } } }
        assertEquals(emptyList(), cliLeaks, "catalog or tool names in CLI texts")

        val agentEntryId = Catalog.agentTools.single { it.name == "network.entry" }.param("id")!!.description
        assertTrue("network_list" in agentEntryId, agentEntryId)
        val cliEntryId = Catalog.cliCommands.single { it.name == "network.entry" }.param("id")!!.description
        assertTrue("dta network list" in cliEntryId, cliEntryId)
        val cliEpoch = Catalog.cliCommands.single { it.name == "archive.delete" }.param("epoch")!!.description
        assertTrue("dta archive list" in cliEpoch, cliEpoch)
        val agentEpoch = Catalog.agentTools.single { it.name == "archive.delete" }.param("epoch")!!.description
        assertTrue("archive_list" in agentEpoch, agentEpoch)
        assertEquals(Catalog.all.map { it.name }, Catalog.cliCommands.map { it.name })
    }

    private fun agent(tool: String) = Catalog.agentTools.single { it.toolName == tool }

    /**
     * D-018 (005 contract §6, FR-001/004/005/006, 020 FR-007/009): an agent that knows DTA only
     * through its tools learns the script API from them — handler names with their arguments, how
     * an effect is returned, the runtime it runs in, persistence, and where silent failures show.
     */
    @Test
    fun `network_script_set teaches the script api`() {
        val set = agent("network_script_set").description
        val needed = listOf(
            "onRequest(req)", "onResponse(res, req)", "onWsSend(msg, conn)", "onWsReceive(msg, conn)",
            "sleep(ms)", "throttle(bytesPerSecond)", "respond({status, headers, body})", "drop()", "description(text)", "log(",
            "text()", "json()", "return",
            "replaces", "persists across app restarts", "network_script_clear", "network_script_status", "network_script_logs",
            "function onRequest(req)", "dta://network/script-api",
        )
        assertEquals(emptyList(), needed.filter { it !in set }, set)
        assertTrue("{delayMs" in set && "null" in set, "says that returning {delayMs} or null does nothing: $set")
        assertTrue("URL" in set && "async" in set, "names what the runtime lacks: $set")
        val cliSet = Catalog.cliCommands.single { it.name == "network.script.set" }.description
        assertTrue("dta://" !in cliSet, "an MCP resource means nothing on the command line: $cliSet")
    }

    @Test
    fun `network_script_status describes ScriptStatus and nothing else`() {
        val status = agent("network_script_status").description
        val fields = listOf("active", "id", "installedAtMs", "persisted", "source", "handlers", "invocations", "changes", "errors", "unavailable")
        assertEquals(emptyList(), fields.filter { it !in status }, status)
        assertTrue("last error" !in status.lowercase(), "ScriptStatus has an error count, no last error: $status")
    }

    @Test
    fun `network_script_logs and clear say what they hold and stop`() {
        val logs = agent("network_script_logs").description
        assertTrue("log(" in logs && "error" in logs && "entry" in logs && "next" in logs, logs)
        val clear = agent("network_script_clear").description
        assertTrue("timers" in clear && "restart" in clear, clear)
    }

    /** D-018 task 9: how to see what a rule changed, and the original. */
    @Test
    fun `entry and body say where a script's change and the original are`() {
        val entry = agent("network_entry").description
        listOf("intervention", "changed", "delayMs", "mocked", "dropped", "error", "description").forEach { assertTrue(it in entry, "$it: $entry") }
        val part = agent("network_body").param("part")!!.description
        assertTrue("app-request" in part && "app-response" in part && "mocked" in part, part)
        val list = agent("network_list").param("intervened")!!.description
        assertTrue("network script" in list, list)
    }

    /** 072 B3 (FR-004): every command and tool whose answer is redacted for agents says how to read a mask and that fingerprints are searched. */
    @Test
    fun `operations whose answers are redacted say how masks read and that fingerprints search`() {
        val redacting = listOf(
            "network.list", "network.search", "network.entry", "network.body", "network.export", "links.list", "links.entry",
            "timeline.events", "timeline.causes", "timeline.sequence", "state.current", "state.graph", "state.path", "network.watch", "timeline.watch",
        )
        for (name in redacting) {
            val cli = Catalog.cliCommands.single { it.name == name }.description
            assertTrue("[redacted len=N #fingerprint]" in cli && "redacted:#fingerprint" in cli, "$name (cli): $cli")
            Catalog.agentTools.singleOrNull { it.name == name }?.let { agent ->
                assertTrue("[redacted len=N #fingerprint]" in agent.description && "redacted:#fingerprint" in agent.description, "$name (agent): ${agent.description}")
            }
        }
        val q = Catalog["network.list"].param("q")!!.description
        assertTrue("redacted:#" in q && "fingerprint" in q, q)
        assertTrue("no per-call way to turn it off" in Catalog["agents.redaction"].description, Catalog["agents.redaction"].description)
    }
}
