package io.yamsergey.dta.daemon.network.proto

import io.yamsergey.dta.protocol.network.Direction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A schema could not be read, compiled, stored, or a mapping is invalid; the message names the problem. */
class SchemaException(message: String) : Exception(message)

/** Schema input (contract §2): a compiled `FileDescriptorSet`, or `.proto` sources by path. */
sealed interface SchemaInput {
    class DescriptorSet(val bytes: ByteArray) : SchemaInput
    class ProtoSources(val files: Map<String, String>) : SchemaInput
}

@Serializable
enum class SchemaSource {
    @SerialName("descriptor-set") DESCRIPTOR_SET,
    @SerialName("proto-sources") PROTO_SOURCES,
}

/** A named schema set of one app (data-model). `otlp` is reserved for the built-in one. */
@Serializable
data class SchemaSet(
    val name: String,
    val source: SchemaSource,
    val files: List<String>,
    val types: List<String>,
    val addedAtMs: Long,
)

/** What the user asks to map (contract §3). */
data class BodyMappingSpec(
    /** Glob over the full URL; `*` is any run of characters. */
    val urlPattern: String,
    val part: MappingPart,
    val schema: String,
    /** Fully qualified message type. */
    val type: String,
    /** HTTP parts only. */
    val method: String? = null,
    /** Message part only. */
    val direction: Direction? = null,
)

/** A stored mapping; [builtin] marks the OTLP rules in listings, [broken] says why it cannot be used. */
@Serializable
data class BodyMapping(
    val id: String,
    val urlPattern: String,
    val part: MappingPart,
    val schema: String,
    val type: String,
    val method: String? = null,
    val direction: Direction? = null,
    val addedAtMs: Long = 0,
    val builtin: Boolean = false,
    val broken: String? = null,
)
