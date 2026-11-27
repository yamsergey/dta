package io.yamsergey.dta.mcp

import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

/** The tool specifications both transports serve: one per [McpTools] tool (constitution VI). */
object McpServerSpecs {
    val CAPABILITIES: McpSchema.ServerCapabilities = McpSchema.ServerCapabilities.builder().tools(false).resources(false, false).build()

    /** The catalog's tools, then `app_run` when the server can run apps ([appRun]). */
    fun tools(tools: McpTools, mapper: McpJsonMapper, appRun: AppRunTool? = null, cancels: McpCancels? = null): List<McpServerFeatures.SyncToolSpecification> =
        catalogTools(tools, mapper, cancels) + listOfNotNull(appRun?.let { appRunTool(it, mapper) })

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
                var reported = 0
                val outcome = runBlocking {
                    tool.invoke(args) { p ->
                        if (token != null) {
                            runCatching { exchange.progressNotification(AppRunTool.notification(token, reported++, p)) }
                        }
                    }
                }
                McpSchema.CallToolResult.builder().addTextContent(outcome.text).isError(outcome.isError).build()
            }
            .build()

    private fun catalogTools(tools: McpTools, mapper: McpJsonMapper, cancels: McpCancels?): List<McpServerFeatures.SyncToolSpecification> = tools.tools.map { spec ->
        McpServerFeatures.SyncToolSpecification.builder()
            .tool(
                McpSchema.Tool.builder()
                    .name(spec.toolName)
                    .description(spec.description)
                    .inputSchema(mapper, DtaJson.encodeToString(JsonObject.serializer(), spec.inputSchema()))
                    .build(),
            )
            .callHandler { exchange, request ->
                val args = McpTools.arguments(request.arguments()) { mapper.writeValueAsString(it) }
                val session = runCatching { exchange.sessionId() }.getOrNull()
                val token = request.meta()?.get("progressToken")
                // Feature 071: progress as a streamed search finds rows; the agent's cancel stops the call.
                val progress: (Int) -> Unit = { n ->
                    if (token != null) runCatching { exchange.progressNotification(McpSchema.ProgressNotification(token, n.toDouble(), null, "$n found")) }
                }
                val outcome = try {
                    runBlocking {
                        val release = cancels?.claim(spec.toolName, args, coroutineContext[kotlinx.coroutines.Job]!!, session.takeIf { cancels.bySession }) ?: {}
                        try { tools.invoke(spec.toolName, args, session, progress) } finally { release() }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    ToolOutcome("cancelled by the client", isError = true)
                }
                McpSchema.CallToolResult.builder().addTextContent(outcome.text)
                    .apply { outcome.images.forEach { addContent(McpSchema.ImageContent(null, it.data, it.mimeType)) } }
                    .isError(outcome.isError).build()
            }
            .build()
    }
}
