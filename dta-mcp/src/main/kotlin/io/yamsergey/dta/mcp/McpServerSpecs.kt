package io.yamsergey.dta.mcp

import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

/** The tool specifications both transports serve: one per [McpTools] tool (constitution VI). */
object McpServerSpecs {
    val CAPABILITIES: McpSchema.ServerCapabilities = McpSchema.ServerCapabilities.builder().tools(false).build()

    /** The catalog's tools, then `app_run` when the server can run apps ([appRun]). */
    fun tools(tools: McpTools, mapper: McpJsonMapper, appRun: AppRunTool? = null): List<McpServerFeatures.SyncToolSpecification> =
        catalogTools(tools, mapper) + listOfNotNull(appRun?.let { appRunTool(it, mapper) })

    /** `app_run`, with a progress notification per step when the agent asked for progress. */
    private fun appRunTool(tool: AppRunTool, mapper: McpJsonMapper): McpServerFeatures.SyncToolSpecification =
        McpServerFeatures.SyncToolSpecification.builder()
            .tool(
                McpSchema.Tool.builder()
                    .name(AppRunTool.NAME)
                    .description(AppRunTool.DESCRIPTION)
                    .inputSchema(mapper, DtaJson.encodeToString(JsonObject.serializer(), AppRunTool.INPUT_SCHEMA))
                    .build(),
            )
            .callHandler { exchange, request ->
                val args = McpTools.arguments(request.arguments()) { mapper.writeValueAsString(it) }
                val token = request.meta()?.get("progressToken")
                val outcome = runBlocking {
                    tool.invoke(args) { p ->
                        if (token != null) {
                            runCatching {
                                exchange.progressNotification(McpSchema.ProgressNotification(token, p.step.toDouble(), AppRunTool.TOTAL.toDouble(), p.message))
                            }
                        }
                    }
                }
                McpSchema.CallToolResult.builder().addTextContent(outcome.text).isError(outcome.isError).build()
            }
            .build()

    private fun catalogTools(tools: McpTools, mapper: McpJsonMapper): List<McpServerFeatures.SyncToolSpecification> = tools.tools.map { spec ->
        McpServerFeatures.SyncToolSpecification.builder()
            .tool(
                McpSchema.Tool.builder()
                    .name(spec.toolName)
                    .description(spec.description)
                    .inputSchema(mapper, DtaJson.encodeToString(JsonObject.serializer(), spec.inputSchema()))
                    .build(),
            )
            .callHandler { _, request ->
                val args = McpTools.arguments(request.arguments()) { mapper.writeValueAsString(it) }
                val outcome = runBlocking { tools.invoke(spec.toolName, args) }
                McpSchema.CallToolResult.builder().addTextContent(outcome.text).isError(outcome.isError).build()
            }
            .build()
    }
}
