package io.yamsergey.dta.protocol.data

import io.yamsergey.dta.protocol.rpc.DegradationMarker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Feature 027: the app's stores, their content and what DTA changed in them. */
@Serializable
enum class StoreKind {
    @SerialName("database") DATABASE,
    @SerialName("prefs") PREFS,
    @SerialName("datastore") DATASTORE,
    ;

    val prefix: String get() = when (this) { DATABASE -> "db"; PREFS -> "prefs"; DATASTORE -> "datastore" }

    companion object {
        fun ofPrefix(prefix: String): StoreKind? = entries.firstOrNull { it.prefix == prefix }
    }
}

/** How DTA reaches a store (spec "Organisation"). */
@Serializable
enum class Access {
    /** Through the app's own live object: the app sees writes at once, no key needed. */
    @SerialName("live") LIVE,

    /** DTA opens the file itself. */
    @SerialName("file") FILE,

    /** Not readable now; [StoreInfo.accessNote] says why and what to do. */
    @SerialName("unreadable") UNREADABLE,
}

/** A store's id is `<kind prefix>:<name>` — `db:notes.db`, `prefs:settings`, `datastore:settings.preferences_pb`. */
object StoreIds {
    fun of(kind: StoreKind, name: String) = "${kind.prefix}:$name"

    fun parse(id: String): Pair<StoreKind, String> {
        val kind = StoreKind.ofPrefix(id.substringBefore(':', ""))
        require(kind != null && id.substringAfter(':').isNotEmpty()) { "a store is <db|prefs|datastore>:<name> (as data list shows it); got \"$id\"" }
        return kind to id.substringAfter(':')
    }
}

@Serializable
data class StoreInfo(
    val id: String,
    val kind: StoreKind,
    val name: String,
    val path: String,
    val sizeBytes: Long? = null,
    /** database: sqlite, room, sqlcipher, driver; prefs: plain, encrypted; datastore: preferences, proto. */
    val flavor: String,
    val access: Access,
    /** How DTA reaches it, in plain words (and what to do when unreadable). */
    val accessNote: String,
    /** Tables (database) or keys (prefs, Preferences DataStore), when known. */
    val entries: Int? = null,
)

@Serializable
data class FileRoot(val name: String, val path: String)

@Serializable
data class StoreList(
    val stores: List<StoreInfo>,
    val roots: List<FileRoot> = emptyList(),
    val completeness: List<DegradationMarker> = emptyList(),
)

// ---- databases ----

@Serializable
data class DbColumn(
    val name: String,
    val type: String = "",
    val notNull: Boolean = false,
    val defaultValue: String? = null,
    /** 1-based position in the primary key; 0 when not part of it. */
    val primaryKey: Int = 0,
)

@Serializable
data class DbObject(
    val name: String,
    /** table, view, index, trigger. */
    val type: String,
    val sql: String? = null,
    val columns: List<DbColumn> = emptyList(),
    val rowCount: Long? = null,
    val withoutRowid: Boolean = false,
    /** index / trigger: the table it belongs to. */
    val table: String? = null,
)

@Serializable
data class DbSchema(
    val store: String,
    val objects: List<DbObject>,
    val userVersion: Int? = null,
    val room: Boolean = false,
    val access: Access,
    val accessNote: String,
)

@Serializable
enum class CellType {
    @SerialName("null") NULL,
    @SerialName("integer") INTEGER,
    @SerialName("real") REAL,
    @SerialName("text") TEXT,
    @SerialName("blob") BLOB,
}

/**
 * One value of a row. Numbers travel as text (no precision lost); a blob is its size and the
 * hex of its first bytes ([truncated]) or, asked whole, base64.
 */
@Serializable
data class Cell(
    val type: CellType,
    val value: String? = null,
    val size: Int? = null,
    val truncated: Boolean = false,
) {
    companion object {
        val NULL = Cell(CellType.NULL)
        const val BLOB_PREFIX = 32
    }
}

@Serializable
data class QueryPage(
    val store: String,
    val columns: List<String>,
    val rows: List<List<Cell>>,
    val offset: Int,
    /** All rows of the result, when the cursor counted them. */
    val total: Int? = null,
)

@Serializable
data class ExecResult(
    val store: String,
    /** Rows an UPDATE / DELETE / INSERT changed; null for other statements. */
    val changed: Int? = null,
    val insertedRowId: Long? = null,
    /** Room invalidation trackers told about the change (the app's observers re-query). */
    val invalidated: Int = 0,
    val statement: String,
)

// ---- preferences, DataStore ----

@Serializable
enum class PrefType(private val words: String) {
    @SerialName("string") STRING("a string"),
    @SerialName("int") INT("an int (a 32-bit whole number)"),
    @SerialName("long") LONG("a long (a 64-bit whole number)"),
    @SerialName("float") FLOAT("a float"),
    @SerialName("boolean") BOOLEAN("a boolean (true or false)"),
    @SerialName("stringSet") STRING_SET("a string set (a JSON array of strings)"),
    @SerialName("double") DOUBLE("a double (Preferences DataStore only)"),
    @SerialName("bytes") BYTES("bytes as base64 (Preferences DataStore only)"),
    ;

    val wireName: String get() = when (this) { STRING_SET -> "stringSet"; else -> name.lowercase() }

    /** [text] as a JSON value of this type; refuses what does not fit. */
    fun parse(text: String): JsonElement = parseFor("the value", text)

    fun parseFor(key: String, text: String): JsonElement {
        val p = JsonPrimitive(text)
        val value: JsonElement? = when (this) {
            STRING -> p
            INT -> text.toIntOrNull()?.let(::JsonPrimitive)
            LONG -> text.toLongOrNull()?.let(::JsonPrimitive)
            FLOAT -> text.toFloatOrNull()?.let(::JsonPrimitive)
            DOUBLE -> text.toDoubleOrNull()?.let(::JsonPrimitive)
            BOOLEAN -> when (text) { "true" -> JsonPrimitive(true); "false" -> JsonPrimitive(false); else -> null }
            STRING_SET -> runCatching { Json.parseToJsonElement(text) as JsonArray }.getOrNull()
                ?.takeIf { a -> a.all { it is JsonPrimitive && it.isString } }
            BYTES -> runCatching { java.util.Base64.getDecoder().decode(text); p }.getOrNull()
        }
        return value ?: throw IllegalArgumentException("$key takes $words; got \"$text\"")
    }

    companion object {
        fun ofWire(name: String): PrefType = entries.firstOrNull { it.wireName == name }
            ?: throw IllegalArgumentException("type is one of ${entries.joinToString { it.wireName }}; got \"$name\"")
    }
}

/** One key of a preferences store, its type and its value (typed JSON: string, number, boolean, array). */
@Serializable
data class PrefEntry(val key: String, val type: PrefType, val value: JsonElement)

@Serializable
data class PrefsContent(
    val store: String,
    val entries: List<PrefEntry>,
    val access: Access,
    val accessNote: String,
)

/** A Proto DataStore's message: its bytes, decoded by the daemon when it can. */
@Serializable
data class ProtoContent(
    val store: String,
    val base64: String,
    val sizeBytes: Int,
    val access: Access,
    val accessNote: String,
    /** Decoded fields: by the schema type when given, else by field number. */
    val decoded: JsonElement? = null,
    val type: String? = null,
    val decodeNote: String? = null,
)

/** What a write changed, in plain words, and the timeline event that records it. */
@Serializable
data class DataWriteResult(
    val store: String,
    val change: String,
    val access: Access,
    val timelineEvent: String? = null,
)

// ---- files ----

@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    val dir: Boolean,
    val sizeBytes: Long = 0,
    val modifiedMs: Long = 0,
    /** Folders: how many entries they hold. */
    val children: Int? = null,
)

@Serializable
data class FileListing(
    val path: String,
    val entries: List<FileEntry>,
    val offset: Int = 0,
    val total: Int,
)

@Serializable
data class FileChunk(
    val path: String,
    val offset: Long,
    val base64: String,
    val sizeBytes: Long,
    val modifiedMs: Long = 0,
)

/** A file read: inline text when it is short text, else saved to [output] on this machine. */
@Serializable
data class FileContent(
    val path: String,
    val sizeBytes: Long,
    val text: String? = null,
    val base64: String? = null,
    val output: String? = null,
    val sha256: String? = null,
)

internal fun JsonElement.asPrefValueOrNull(type: PrefType): Any? {
    val p = this as? JsonPrimitive
    return when (type) {
        PrefType.STRING -> p?.content
        PrefType.INT -> p?.intOrNull
        PrefType.LONG -> p?.longOrNull
        PrefType.FLOAT -> p?.floatOrNull
        PrefType.DOUBLE -> p?.doubleOrNull
        PrefType.BOOLEAN -> p?.booleanOrNull
        PrefType.STRING_SET -> (this as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet()
        PrefType.BYTES -> p?.content?.let { java.util.Base64.getDecoder().decode(it) }
    }
}
