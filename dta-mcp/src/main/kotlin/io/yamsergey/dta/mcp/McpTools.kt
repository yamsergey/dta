package io.yamsergey.dta.mcp

import io.yamsergey.dta.client.DaemonCallException
import io.yamsergey.dta.client.DaemonGoneException
import io.yamsergey.dta.protocol.ops.Catalog
import io.yamsergey.dta.protocol.ops.ClientErrorCodes
import io.yamsergey.dta.protocol.ops.OperationSpec
import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.ErrorCodes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/** What a tool call returns: text, and whether it is an error. */
data class ToolOutcome(val text: String, val isError: Boolean)

/**
 * DTA's agent tools (feature 008 US2): one per catalog operation offered to agents, with the
 * catalog's description and input schema. Calls go to the daemon through [call]; results are
 * the same `Selected` JSON `dta … --json` prints, bounded by [bound]; errors name the next step.
 */
class McpTools(
    private val call: suspend (operation: String, params: JsonObject) -> Selected,
    private val bound: ResultBound = ResultBound(),
    private val tempDir: File = File(System.getProperty("java.io.tmpdir")),
    /** Set when the daemon cannot be used at all (version conflict, old Java): every call answers it. */
    private val unusable: () -> String? = { null },
    /** The project folder whose .proto files a binary body may need (the stdio server's working folder, feature 013). */
    private val schemaFolder: java.io.File? = null,
) {
    @Volatile
    private var schemasSynced = false
    val tools: List<OperationSpec> = Catalog.agentTools

    fun byTool(name: String): OperationSpec? = tools.firstOrNull { it.toolName == name }

    suspend fun invoke(toolName: String, arguments: JsonObject): ToolOutcome {
        unusable()?.let { return ToolOutcome(it, isError = true) }
        val spec = byTool(toolName) ?: return ToolOutcome("Unknown tool '$toolName'. Tools: ${tools.joinToString(", ") { it.toolName }}", true)
        return try {
            val selected = call(spec.name, arguments)
            val text = bound.apply(spec.name, selected)
            if (spec.name == "network.body" && "truncated" in text && arguments["output"] == null) {
                // A body too large for the response goes to a file instead (contract mcp.md).
                val file = File.createTempFile("dta-body-", ".bin", tempDir)
                val written = call(spec.name, JsonObject(arguments + ("output" to JsonPrimitive(file.absolutePath))))
                return ToolOutcome(bound.apply(spec.name, written), isError = false)
            }
            ToolOutcome(text, isError = false)
        } catch (e: DaemonCallException) {
            ToolOutcome(errorText(spec, e) + (autoSync(spec, arguments, e)?.let { "\n$it" } ?: ""), isError = true)
        } catch (e: DaemonGoneException) {
            ToolOutcome("${e.reason}. Call the tool again to reconnect.", isError = true)
        }
    }

    /**
     * A binary body without a schema: the project's .proto files are synced once per session
     * (research R5), and the answer says which message types still need a mapping from the code.
     */
    private suspend fun autoSync(spec: OperationSpec, arguments: JsonObject, e: DaemonCallException): String? {
        val folder = schemaFolder ?: return null
        if (spec.name != "network.body" || schemasSynced || "protobuf schema" !in (e.message ?: "")) return null
        schemasSynced = true
        val params = buildJsonObject {
            put("folder", folder.path)
            arguments["device"]?.let { put("device", it) }
            arguments["app"]?.let { put("app", it) }
        }
        return try {
            val result = call("network.schema.sync", params).result.jsonObject
            val set = result["set"] as? JsonObject ?: return "Looked for .proto files in ${folder.path}: none compiled."
            val types = (result["unmappedTypes"] as? JsonArray)?.joinToString(", ") { (it as JsonPrimitive).content }.orEmpty()
            "DTA synced the .proto files of ${folder.path} as schema set '${(set["name"] as JsonPrimitive).content}'. " +
                "Find the endpoint that sends or returns this body in the code and map it with network_mapping_add" +
                (if (types.isNotEmpty()) " (message types: $types)" else "") + ", then fetch the body again."
        } catch (sync: DaemonCallException) {
            "Looked for .proto files in ${folder.path}: ${sync.message}"
        }
    }

    private fun errorText(spec: OperationSpec, e: DaemonCallException): String {
        val data = e.data
        val next = when (e.code) {
            ErrorCodes.INVALID_PARAMS -> "Valid parameters for ${spec.toolName}: " + spec.params.joinToString("; ") { p ->
                p.name + (if (p.enumValues.isNotEmpty()) " (${p.enumValues.joinToString("|")})" else "") + if (p.required) " required" else ""
            }
            ClientErrorCodes.SEVERAL_DEVICES -> "Pass device: one of ${(data?.get("devices") as? JsonArray)?.joinToString(", ") { (it as JsonPrimitive).content }}."
            ClientErrorCodes.NO_APP -> "Pass app with a package that has DTA inside" +
                ((data?.get("appsWithDta") as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { ": ${it.joinToString(", ") { a -> (a as JsonPrimitive).content }}" } ?: ", or build the app with DTA and start it") + "."
            ClientErrorCodes.NO_DEVICE -> "Start an emulator or connect a device with USB debugging, then call again."
            else -> null
        }
        return listOfNotNull(e.message, next).joinToString("\n")
    }

    companion object {
        /** Tool arguments as the catalog's JSON params. */
        fun arguments(map: Map<String, Any?>?, toJson: (Any) -> String): JsonObject =
            if (map.isNullOrEmpty()) JsonObject(emptyMap()) else DtaJson.parseToJsonElement(toJson(map)).jsonObject

        val INSTRUCTIONS = """
            DTA inspects a running Android app built with DTA inside: its screen as a tree of Views and Compose nodes (layout_tree), and its network traffic — HTTP and WebSocket in one list (network_list, network_entry, network_body), with scripts that change traffic and protobuf decoding.
            Device and app may be omitted: one connected device and the foreground app are chosen, and the result's selection says so.
            Results state how values were obtained: fallback captures, degraded markers, incomplete bodies, script interventions. Read them before drawing conclusions.
            To follow traffic over time, call network_list with sinceMs set to the time of your previous call.
        """.trimIndent()
    }
}
