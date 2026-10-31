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

/** Where schemas and mappings came from (feature 013): a project in the IDE, a folder, or by hand. */
@Serializable
enum class OriginKind {
    @SerialName("project") PROJECT,
    @SerialName("folder") FOLDER,
    @SerialName("manual") MANUAL,
}

/** [path] is the project or folder; [client] who synced it (ide, cli, mcp); [syncedMs] when. */
@Serializable
data class SchemaOrigin(val kind: OriginKind, val path: String? = null, val client: String? = null, val syncedMs: Long = 0)

/** A named schema set of one app (data-model). `otlp` is reserved for the built-in one; [origin] null is manual. */
@Serializable
data class SchemaSet(
    val name: String,
    val source: SchemaSource,
    val files: List<String>,
    val types: List<String>,
    val addedAtMs: Long,
    val origin: SchemaOrigin? = null,
)

/** A mapping a client derived from code (013): its [location] in the code, e.g. `CartApi.kt:12`. */
data class DerivedMapping(val spec: BodyMappingSpec, val location: String? = null)

/** A derived mapping as a client sends it (`network.schema.sync` `mappings`). */
@Serializable
data class DerivedMappingWire(
    val urlPattern: String,
    val part: MappingPart,
    val type: String,
    val method: String? = null,
    val direction: Direction? = null,
    val location: String? = null,
) {
    fun toDerived() = DerivedMapping(BodyMappingSpec(urlPattern, part, schema = "", type = type, method = method, direction = direction), location)
}

/** A file a sync could not compile, and why. */
@Serializable
data class SyncFailure(val file: String, val reason: String)

/** What a sync did (013 contracts): the set now in use (null: nothing compiled), and what it could not use. */
@Serializable
data class SyncResult(
    val set: SchemaSet? = null,
    val replaced: Boolean = false,
    val failed: List<SyncFailure> = emptyList(),
    val mappings: Int = 0,
    val rejectedMappings: List<String> = emptyList(),
    /** Message types of the set no mapping uses. */
    val unmappedTypes: List<String> = emptyList(),
    /** Discovery notes: the file bound hit, no .proto found under the folder. */
    val notes: List<String> = emptyList(),
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
    /** Null: added by hand. */
    val origin: SchemaOrigin? = null,
    /** Where a derived mapping comes from in the code. */
    val location: String? = null,
)
