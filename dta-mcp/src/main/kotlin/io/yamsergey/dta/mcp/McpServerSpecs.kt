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

    fun tools(tools: McpTools, mapper: McpJsonMapper): List<McpServerFeatures.SyncToolSpecification> = tools.tools.map { spec ->
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
