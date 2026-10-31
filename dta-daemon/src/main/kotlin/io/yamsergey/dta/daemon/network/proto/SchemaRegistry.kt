package io.yamsergey.dta.daemon.network.proto

import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import com.google.protobuf.Descriptors
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The user's protobuf schemas and body mappings, per app package (R5) — the one owner of
 * that state (II). Stored under `<dataDir>/schemas/<package>/`: each set as a built
 * `FileDescriptorSet` (`<name>.desc`) with its listing (`<name>.json`), mappings in
 * `mappings.json`. [rules] puts the user's mappings ahead of the built-in OTLP ones.
 */
class SchemaRegistry(
    private val dataDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Loaded(val set: SchemaSet, val types: Map<String, Descriptors.Descriptor>)

    @Serializable
    private data class MappingsFile(val nextId: Int = 1, val mappings: List<BodyMapping> = emptyList())

    private class AppState(val sets: MutableMap<String, Loaded>, var mappings: MappingsFile)

    private val apps = mutableMapOf<String, AppState>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Synchronized
    fun addSchema(pkg: String, name: String, input: SchemaInput): SchemaSet {
        if (name == Otlp.SCHEMA) throw SchemaException("schema name '${Otlp.SCHEMA}' is reserved for the built-in OTLP schema")
        if (!NAME.matches(name)) throw SchemaException("schema name '$name' must match ${NAME.pattern}")
        val (source, files) = when (input) {
            is SchemaInput.DescriptorSet -> SchemaSource.DESCRIPTOR_SET to DescriptorSetReader.read(input.bytes)
            is SchemaInput.ProtoSources -> SchemaSource.PROTO_SOURCES to ProtoSourceCompiler.compile(input.files)
        }
        val types = types(files)
        val set = SchemaSet(name, source, files.map { it.name }, types.keys.toList(), clock())
        val dir = dir(pkg).apply { mkdirs() }
        File(dir, "$name.desc").writeBytes(FileDescriptorSet.newBuilder().addAllFile(files.map { it.toProto() }).build().toByteArray())
        File(dir, "$name.json").writeText(json.encodeToString(SchemaSet.serializer(), set))
        state(pkg).sets[name] = Loaded(set, types)
        return set
    }

    @Synchronized
    fun schemas(pkg: String): List<SchemaSet> = state(pkg).sets.values.map { it.set }

    /**
     * Replaces the set and the derived mappings of one [origin] (feature 013, research R1/R6):
     * files that do not compile are left out and reported; when nothing compiles the previous
     * set of the origin stays. Other origins and what was added by hand are untouched.
     */
    @Synchronized
    fun sync(pkg: String, origin: SchemaOrigin, files: Map<String, String>, mappings: List<DerivedMapping>, notes: List<String> = emptyList()): SyncResult {
        val stamped = origin.copy(syncedMs = clock())
        val name = "${stamped.kind.name.lowercase()}-" + sha(stamped.path ?: stamped.kind.name).take(10)
        val failed = mutableListOf<SyncFailure>()
        val remaining = LinkedHashMap(files)
        var compiled: List<Descriptors.FileDescriptor>? = null
        while (remaining.isNotEmpty()) {
            try {
                compiled = ProtoSourceCompiler.compile(remaining)
                break
            } catch (e: SchemaException) {
                val message = e.message ?: "does not compile"
                // Leave out the file the error names (or, unnamed, every file) and try the rest.
                val culprit = remaining.keys.firstOrNull { message.contains(it) && !message.contains("(imported by $it)") }
                    ?: remaining.keys.firstOrNull { message.contains(it) }
                if (culprit == null) {
                    remaining.keys.forEach { failed += SyncFailure(it, message) }
                    remaining.clear()
                } else {
                    failed += SyncFailure(culprit, message)
                    remaining.remove(culprit)
                }
            }
        }
        val state = state(pkg)
        if (compiled == null) {
            return SyncResult(set = state.sets[name]?.set, failed = failed, notes = notes + "nothing compiled; the previous set of this source stays in use")
        }
        val types = types(compiled)
        val replaced = state.sets.containsKey(name)
        val set = SchemaSet(name, SchemaSource.PROTO_SOURCES, compiled.map { it.name }, types.keys.toList(), clock(), stamped)
        val dir = dir(pkg).apply { mkdirs() }
        File(dir, "$name.desc").writeBytes(FileDescriptorSet.newBuilder().addAllFile(compiled.map { it.toProto() }).build().toByteArray())
        File(dir, "$name.json").writeText(json.encodeToString(SchemaSet.serializer(), set))
        state.sets[name] = Loaded(set, types)

        // The origin's derived mappings are replaced as a whole.
        val rejected = mutableListOf<String>()
        var nextId = state.mappings.nextId
        val derived = mappings.mapNotNull { d ->
            if (d.spec.type !in types) {
                rejected += "${d.spec.type} (${d.location ?: d.spec.urlPattern}): not a message type of the synced schemas"
                return@mapNotNull null
            }
            BodyMapping(
                id = "m${nextId++}", urlPattern = d.spec.urlPattern, part = d.spec.part, schema = name, type = d.spec.type,
                method = d.spec.method?.uppercase(), direction = d.spec.direction, addedAtMs = clock(), origin = stamped, location = d.location,
            )
        }
        val kept = state.mappings.mappings.filterNot { it.origin != null && it.origin.kind == stamped.kind && it.origin.path == stamped.path }
        state.mappings = MappingsFile(nextId, kept + derived)
        saveMappings(pkg, state)
        val mapped = state.mappings.mappings.filter { it.schema == name }.map { it.type }.toSet()
        return SyncResult(set, replaced, failed, derived.size, rejected, types.keys.filter { it !in mapped }, notes)
    }

    private fun sha(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Returns the mappings that used [name] — now broken until a schema of that name returns. */
    @Synchronized
    fun removeSchema(pkg: String, name: String): List<BodyMapping> {
        val state = state(pkg)
        state.sets.remove(name) ?: throw SchemaException("unknown schema '$name'; known: ${state.sets.keys.sorted()}")
        File(dir(pkg), "$name.desc").delete()
        File(dir(pkg), "$name.json").delete()
        return mappings(pkg).filter { it.schema == name }
    }

    @Synchronized
    fun addMapping(pkg: String, spec: BodyMappingSpec): BodyMapping {
        val state = state(pkg)
        if (spec.urlPattern.isBlank()) throw SchemaException("urlPattern is empty")
        if (spec.direction != null && spec.part != MappingPart.MESSAGE) throw SchemaException("direction applies to WebSocket messages only (part = message)")
        if (spec.method != null && spec.part == MappingPart.MESSAGE) throw SchemaException("method applies to HTTP requests and responses only")
        val schema = state.sets[spec.schema]
            ?: throw SchemaException("unknown schema '${spec.schema}'; known: ${state.sets.keys.sorted()}")
        if (spec.type !in schema.types) {
            val simple = spec.type.substringAfterLast('.').lowercase()
            val close = schema.types.keys.filter { t ->
                val s = t.substringAfterLast('.').lowercase()
                s == simple || s.contains(simple) || simple.contains(s) || editDistance(s, simple) <= 2
            }
            throw SchemaException("unknown type '${spec.type}' in schema '${spec.schema}'" + if (close.isEmpty()) "" else "; close matches: ${close.joinToString(", ")}")
        }
        val mapping = BodyMapping(
            id = "m${state.mappings.nextId}", urlPattern = spec.urlPattern, part = spec.part, schema = spec.schema,
            type = spec.type, method = spec.method?.uppercase(), direction = spec.direction, addedAtMs = clock(),
        )
        state.mappings = MappingsFile(state.mappings.nextId + 1, state.mappings.mappings + mapping)
        saveMappings(pkg, state)
        return mapping
    }

    /** User mappings (with why they are broken, if they are), then the built-in OTLP rules. */
    @Synchronized
    fun mappings(pkg: String): List<BodyMapping> {
        val state = state(pkg)
        val user = state.mappings.mappings.map { it.copy(broken = brokenReason(state, it)) }
        val builtin = Otlp.listing.map { BodyMapping(it.id, it.pattern.substringAfter(' '), it.part, Otlp.SCHEMA, it.type, method = "POST", builtin = true) }
        return user + builtin
    }

    @Synchronized
    fun removeMapping(pkg: String, id: String): Boolean {
        val state = state(pkg)
        val remaining = state.mappings.mappings.filterNot { it.id == id }
        if (remaining.size == state.mappings.mappings.size) return false
        state.mappings = state.mappings.copy(mappings = remaining)
        saveMappings(pkg, state)
        return true
    }

    /**
     * Decoding rules of [pkg] in selection order (FR-012): the best matching user mapping
     * — most literal pattern, then most recent — before the built-in OTLP rules.
     */
    fun rules(pkg: String): List<DecodingRule> = listOf(DecodingRule { target -> selectUser(pkg, target) }) + Otlp.rules

    @Synchronized
    private fun selectUser(pkg: String, target: BodyTarget): Selection? {
        val state = state(pkg)
        // Added by hand before derived from code (013 FR-009), then the most literal pattern, then the newest.
        val best = state.mappings.mappings
            .filter { matches(it, target) }
            .maxWithOrNull(compareBy<BodyMapping>({ it.origin == null }, { it.urlPattern.count { c -> c != '*' } }, { it.id.drop(1).toInt() }))
            ?: return null
        val descriptor = state.sets[best.schema]?.types?.get(best.type)
        val origin = state.sets[best.schema]?.set?.origin?.let { o ->
            "${o.kind.name.lowercase()}" + (o.path?.let { " $it" } ?: "") + ", synced " + java.time.Instant.ofEpochMilli(o.syncedMs)
        }
        return Selection(descriptor, "protobuf", best.schema, best.id, type = best.type, broken = brokenReason(state, best), origin = origin)
    }

    private fun matches(mapping: BodyMapping, target: BodyTarget): Boolean =
        mapping.part == target.part &&
            (mapping.method == null || mapping.method.equals(target.method, ignoreCase = true)) &&
            (mapping.direction == null || mapping.direction == target.direction) &&
            glob(mapping.urlPattern).matches(target.url)

    private fun brokenReason(state: AppState, mapping: BodyMapping): String? {
        val set = state.sets[mapping.schema] ?: return "schema '${mapping.schema}' was removed"
        return if (mapping.type in set.types) null else "type '${mapping.type}' is no longer in schema '${mapping.schema}'"
    }

    private fun state(pkg: String): AppState = apps.getOrPut(pkg) {
        val dir = dir(pkg)
        val sets = mutableMapOf<String, Loaded>()
        dir.listFiles { f -> f.name.endsWith(".json") && f.name != MAPPINGS }?.sortedBy { it.name }?.forEach { meta ->
            val set = json.decodeFromString(SchemaSet.serializer(), meta.readText())
            val desc = File(dir, "${set.name}.desc")
            if (desc.isFile) sets[set.name] = Loaded(set, types(DescriptorSetReader.read(desc.readBytes())))
        }
        val mappings = File(dir, MAPPINGS).takeIf { it.isFile }?.let { json.decodeFromString(MappingsFile.serializer(), it.readText()) } ?: MappingsFile()
        AppState(sets, mappings)
    }

    private fun saveMappings(pkg: String, state: AppState) {
        val dir = dir(pkg).apply { mkdirs() }
        File(dir, MAPPINGS).writeText(json.encodeToString(MappingsFile.serializer(), state.mappings))
    }

    private fun dir(pkg: String) = File(dataDir, "schemas/$pkg")

    /** Top-level and nested message types of the set's files, by full name. */
    private fun types(files: List<Descriptors.FileDescriptor>): Map<String, Descriptors.Descriptor> {
        val out = LinkedHashMap<String, Descriptors.Descriptor>()
        fun add(d: Descriptors.Descriptor) {
            if (d.options.mapEntry) return
            out[d.fullName] = d
            d.nestedTypes.forEach(::add)
        }
        files.forEach { f -> f.messageTypes.forEach(::add) }
        return out
    }

    private fun glob(pattern: String) = Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) })

    private fun editDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return d[a.length][b.length]
    }

    private companion object {
        const val MAPPINGS = "mappings.json"
        val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}
