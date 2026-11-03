package io.yamsergey.dta.sidekick.data

import android.content.Context
import io.yamsergey.dta.protocol.data.Access
import io.yamsergey.dta.protocol.data.DataWriteResult
import io.yamsergey.dta.protocol.data.DbSchema
import io.yamsergey.dta.protocol.data.ExecResult
import io.yamsergey.dta.protocol.data.FileChunk
import io.yamsergey.dta.protocol.data.FileListing
import io.yamsergey.dta.protocol.data.PrefType
import io.yamsergey.dta.protocol.data.PrefsContent
import io.yamsergey.dta.protocol.data.ProtoContent
import io.yamsergey.dta.protocol.data.QueryPage
import io.yamsergey.dta.protocol.data.StoreIds
import io.yamsergey.dta.protocol.data.StoreKind
import io.yamsergey.dta.protocol.data.StoreList
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.rpc.DegradationMarker
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import io.yamsergey.dta.protocol.rpc.MethodRegistry
import io.yamsergey.dta.protocol.rpc.RpcContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Feature 027: the sidekick's `data_*` methods; nothing runs until a client asks. */
internal class DataMethods(context: Context) {
    private val databases = Databases(context)
    private val prefs = Prefs(context)
    private val dataStores = DataStores(context)
    private val files = AppFiles(context)

    fun install(registry: MethodRegistry) = with(registry) {
        register("data_list") { _ ->
            val completeness = if (LiveObjects.available) emptyList() else listOf(DegradationMarker(NAME, "the app's live objects cannot be seen (${LiveObjects.reason}): files only; encrypted stores the app opened are not reachable without keys"))
            encode(StoreList.serializer(), StoreList(databases.list() + prefs.list() + dataStores.list(), files.roots(), completeness))
        }
        register("data_db_schema") { c -> encode(DbSchema.serializer(), databases.schema(c.store(), c.str("passphrase"))) }
        register("data_db_query") { c ->
            encode(QueryPage.serializer(), databases.query(c.store(), c.required("sql"), c.strings("args"), c.int("offset") ?: 0, c.int("limit") ?: 100, c.str("blobs") == "full", c.str("passphrase")))
        }
        register("data_db_exec") { c ->
            val result = databases.exec(c.store(), c.required("sql"), c.strings("args"), c.str("passphrase"))
            val change = describe(result)
            val event = DataWrites.record(result.store, change, "db.exec")
            buildJsonObject {
                put("exec", DtaJson.encodeToJsonElement(ExecResult.serializer(), result))
                put("write", DtaJson.encodeToJsonElement(DataWriteResult.serializer(), DataWriteResult(result.store, change, Access.LIVE, event)))
            }
        }
        register("data_prefs_read") { c ->
            val store = c.store()
            encode(PrefsContent.serializer(), if (kindOf(store) == StoreKind.DATASTORE) dataStores.readPrefs(store) else prefs.read(store))
        }
        register("data_prefs_set") { c ->
            val store = c.store()
            val type = c.str("type")?.let { t -> runCatching { PrefType.ofWire(t) }.getOrElse { throw InvalidParamsException(it.message!!) } }
            val (change, access) = if (kindOf(store) == StoreKind.DATASTORE) dataStores.setPref(store, c.required("key"), c.required("value"), type)
            else prefs.set(store, c.required("key"), c.required("value"), type) to Access.LIVE
            write(store, change, access, "prefs.set")
        }
        register("data_prefs_remove") { c ->
            val store = c.store()
            val keys = c.strings("keys").ifEmpty { throw InvalidParamsException("give the keys to remove") }
            val (change, access) = if (kindOf(store) == StoreKind.DATASTORE) dataStores.removePrefs(store, keys) else prefs.remove(store, keys) to Access.LIVE
            write(store, change, access, "prefs.remove")
        }
        register("data_proto_read") { c -> encode(ProtoContent.serializer(), dataStores.readProto(c.store())) }
        register("data_proto_write") { c ->
            val store = c.store()
            val bytes = java.util.Base64.getDecoder().decode(c.required("base64"))
            val access = dataStores.writeProto(store, bytes)
            write(store, c.str("change") ?: "message replaced (${bytes.size} bytes)", access, "proto.write")
        }
        register("data_files_list") { c -> encode(FileListing.serializer(), files.list(c.str("path") ?: "data:/", c.int("offset") ?: 0, c.int("limit") ?: 500)) }
        register("data_files_read") { c -> encode(FileChunk.serializer(), files.read(c.required("path"), c.long("offset") ?: 0, c.int("length") ?: CHUNK)) }
        register("data_files_write") { c ->
            val path = c.required("path")
            val last = c.bool("last") ?: true
            val size = files.write(path, c.long("offset") ?: 0, c.str("base64") ?: "", last)
            if (last) write(path, "written ($size bytes)", Access.LIVE, "files.write") else buildJsonObject { put("written", size) }
        }
        register("data_files_mkdir") { c -> files.mkdir(c.required("path")); write(c.required("path"), "folder created", Access.LIVE, "files.mkdir") }
        register("data_files_delete") { c ->
            val count = files.delete(c.required("path"))
            write(c.required("path"), "deleted ($count ${if (count == 1) "entry" else "entries"})", Access.LIVE, "files.delete")
        }
    }

    private fun write(store: String, change: String, access: Access, op: String): JsonElement =
        encode(DataWriteResult.serializer(), DataWriteResult(store, change, access, DataWrites.record(store, change, op)))

    private fun describe(r: ExecResult) = when {
        r.insertedRowId != null -> "inserted row ${r.insertedRowId}"
        r.changed != null -> "${r.changed} ${if (r.changed == 1) "row" else "rows"} changed"
        else -> "statement run"
    } + ": " + r.statement.take(120)

    private fun kindOf(store: String) = try { StoreIds.parse(store).first } catch (e: IllegalArgumentException) { throw InvalidParamsException(e.message!!) }

    private fun <T> encode(s: KSerializer<T>, v: T): JsonElement = DtaJson.encodeToJsonElement(s, v)

    private fun RpcContext.prim(name: String) = params?.get(name) as? JsonPrimitive
    private fun RpcContext.str(name: String) = prim(name)?.content
    private fun RpcContext.int(name: String) = prim(name)?.intOrNull
    private fun RpcContext.long(name: String) = prim(name)?.longOrNull
    private fun RpcContext.bool(name: String) = prim(name)?.booleanOrNull
    private fun RpcContext.required(name: String) = str(name) ?: throw InvalidParamsException("missing $name")
    private fun RpcContext.store() = required("store")
    private fun RpcContext.strings(name: String) = (params?.get(name) as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()

    companion object {
        const val NAME = "data"
        const val CHUNK = 256 * 1024

        fun capability(): CapabilityStatus =
            if (LiveObjects.available) CapabilityStatus(NAME, CapabilityStatus.Status.AVAILABLE)
            else CapabilityStatus(NAME, CapabilityStatus.Status.DEGRADED, "files only: the app's open databases and preference instances cannot be seen (${LiveObjects.reason}); encrypted stores need their keys")
    }
}
