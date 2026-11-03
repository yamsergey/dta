package io.yamsergey.dta.daemon.data

import com.google.protobuf.DynamicMessage
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.UnknownFieldSet
import com.google.protobuf.util.JsonFormat
import io.yamsergey.dta.daemon.connection.AppConnection
import io.yamsergey.dta.daemon.network.proto.SchemaRegistry
import io.yamsergey.dta.protocol.data.DataWriteResult
import io.yamsergey.dta.protocol.data.DbSchema
import io.yamsergey.dta.protocol.data.ExecResult
import io.yamsergey.dta.protocol.data.FileChunk
import io.yamsergey.dta.protocol.data.FileContent
import io.yamsergey.dta.protocol.data.FileListing
import io.yamsergey.dta.protocol.data.PrefsContent
import io.yamsergey.dta.protocol.data.ProtoContent
import io.yamsergey.dta.protocol.data.QueryPage
import io.yamsergey.dta.protocol.data.StoreList
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/** A statement's outcome with the write record (feature 027). */
@Serializable
data class DbExecOutcome(val exec: ExecResult, val write: DataWriteResult)

/**
 * Feature 027: the daemon side of `data.*` — forwards to the sidekick's `data_*` methods,
 * assembles files from chunks (and streams host files up), and decodes / encodes Proto
 * DataStore messages with the app's protobuf schemas.
 */
class DataService(
    private val pkg: String,
    private val schemas: SchemaRegistry,
    private val connection: suspend () -> AppConnection,
) {
    private suspend fun <T> call(method: String, s: KSerializer<T>, params: JsonObject = JsonObject(emptyMap())): T =
        DtaJson.decodeFromJsonElement(s, connection().request(method, params))

    suspend fun list(): StoreList = call("data_list", StoreList.serializer())

    suspend fun schema(params: JsonObject): DbSchema = call("data_db_schema", DbSchema.serializer(), params)
    suspend fun query(params: JsonObject): QueryPage = call("data_db_query", QueryPage.serializer(), params)
    suspend fun exec(params: JsonObject): DbExecOutcome = call("data_db_exec", DbExecOutcome.serializer(), params)

    suspend fun prefsRead(params: JsonObject): PrefsContent = call("data_prefs_read", PrefsContent.serializer(), params)
    suspend fun prefsSet(params: JsonObject): DataWriteResult = call("data_prefs_set", DataWriteResult.serializer(), params)
    suspend fun prefsRemove(params: JsonObject): DataWriteResult = call("data_prefs_remove", DataWriteResult.serializer(), params)

    suspend fun protoRead(store: String, type: String?): ProtoContent {
        val content = call("data_proto_read", ProtoContent.serializer(), buildJsonObject { put("store", store) })
        val bytes = Base64.getDecoder().decode(content.base64)
        if (type == null) {
            return content.copy(decoded = rawFields(bytes), decodeNote = "decoded by field number; give type (a message of the app's schemas: ${schemas.typeNames(pkg).take(8).joinToString().ifEmpty { "none added yet" }}) for names")
        }
        val (set, descriptor) = schemas.descriptor(pkg, type) ?: throw InvalidParamsException(unknownType(type))
        return try {
            val json = JsonFormat.printer().omittingInsignificantWhitespace().print(DynamicMessage.parseFrom(descriptor, bytes))
            content.copy(decoded = DtaJson.parseToJsonElement(json), type = type, decodeNote = "decoded as $type (schema set $set)")
        } catch (e: InvalidProtocolBufferException) {
            content.copy(decoded = rawFields(bytes), type = type, decodeNote = "does not parse as $type (${e.message}); shown by field number")
        }
    }

    suspend fun protoWrite(store: String, type: String, json: String): DataWriteResult {
        val (_, descriptor) = schemas.descriptor(pkg, type) ?: throw InvalidParamsException(unknownType(type))
        val builder = DynamicMessage.newBuilder(descriptor)
        try {
            JsonFormat.parser().merge(json, builder)
        } catch (e: Exception) {
            throw InvalidParamsException("the JSON is not a $type: ${e.message?.lineSequence()?.first()}")
        }
        val bytes = builder.build().toByteArray()
        return call("data_proto_write", DataWriteResult.serializer(), buildJsonObject {
            put("store", store)
            put("base64", Base64.getEncoder().encodeToString(bytes))
            put("change", "message replaced with a $type (${bytes.size} bytes)")
        })
    }

    private fun unknownType(type: String) =
        "no message type $type in the app's schemas (${schemas.typeNames(pkg).take(8).joinToString().ifEmpty { "none: add them with network schema add or sync" }})"

    suspend fun filesList(params: JsonObject): FileListing = call("data_files_list", FileListing.serializer(), params)

    /** The whole file: inline when it is text up to 1 MiB (or base64 when binary), else written to [output]. */
    suspend fun filesRead(path: String, output: String?): FileContent {
        val first = chunk(path, 0)
        if (output == null && first.sizeBytes > INLINE) {
            throw InvalidParamsException("$path is ${first.sizeBytes} bytes; give output to save it on this machine")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = output?.let { File(it).apply { parentFile?.mkdirs() }.outputStream() }
        val inline = java.io.ByteArrayOutputStream()
        try {
            var chunk = first
            while (true) {
                val bytes = Base64.getDecoder().decode(chunk.base64)
                digest.update(bytes)
                if (sink != null) sink.write(bytes) else inline.write(bytes)
                val next = chunk.offset + bytes.size
                if (bytes.isEmpty() || next >= chunk.sizeBytes) break
                chunk = chunk(path, next)
            }
        } finally {
            sink?.close()
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (output != null) return FileContent(path, first.sizeBytes, output = File(output).absolutePath, sha256 = sha)
        val bytes = inline.toByteArray()
        val text = textOrNull(bytes)
        return FileContent(path, first.sizeBytes, text = text, base64 = if (text == null) Base64.getEncoder().encodeToString(bytes) else null, sha256 = sha)
    }

    private suspend fun chunk(path: String, offset: Long): FileChunk =
        call("data_files_read", FileChunk.serializer(), buildJsonObject { put("path", path); put("offset", offset); put("length", CHUNK) })

    suspend fun filesWrite(path: String, text: String?, base64: String?, input: String?): DataWriteResult {
        val given = listOfNotNull(text, base64, input)
        if (given.size != 1) throw InvalidParamsException("give exactly one of text, base64 or input")
        val file = input?.let { File(it).also { f -> if (!f.isFile) throw InvalidParamsException("no file $input on this machine") } }
        val size = file?.length() ?: (text?.toByteArray() ?: Base64.getDecoder().decode(base64!!)).size.toLong()
        val stream = file?.inputStream() ?: (text?.toByteArray() ?: Base64.getDecoder().decode(base64!!)).inputStream()
        stream.use { s ->
            var offset = 0L
            while (true) {
                val bytes = s.readNBytes(CHUNK)
                val last = offset + bytes.size >= size
                val params = buildJsonObject {
                    put("path", path); put("offset", offset)
                    put("base64", Base64.getEncoder().encodeToString(bytes)); put("last", last)
                }
                if (last) return call("data_files_write", DataWriteResult.serializer(), params)
                connection().request("data_files_write", params)
                offset += bytes.size
            }
        }
    }

    suspend fun mkdir(path: String): DataWriteResult = call("data_files_mkdir", DataWriteResult.serializer(), buildJsonObject { put("path", path) })
    suspend fun delete(path: String): DataWriteResult = call("data_files_delete", DataWriteResult.serializer(), buildJsonObject { put("path", path) })

    companion object {
        const val CHUNK = 256 * 1024
        const val INLINE = 1024 * 1024

        fun textOrNull(bytes: ByteArray): String? {
            val decoder = Charsets.UTF_8.newDecoder()
            val text = runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull() ?: return null
            return text.takeIf { t -> t.none { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' } }
        }

        /** A message without its type: fields by number (nested messages guessed where they parse). */
        fun rawFields(bytes: ByteArray): JsonElement = try {
            fields(UnknownFieldSet.parseFrom(bytes))
        } catch (e: InvalidProtocolBufferException) {
            JsonPrimitive("not a protobuf message: ${e.message}")
        }

        private fun fields(set: UnknownFieldSet): JsonObject = buildJsonObject {
            for ((number, field) in set.asMap()) {
                val values = field.varintList.map { JsonPrimitive(it) } +
                    field.fixed32List.map { JsonPrimitive(it) } + field.fixed64List.map { JsonPrimitive(it) } +
                    field.lengthDelimitedList.map { bytes ->
                        // Printable text first: text bytes often happen to parse as fields too.
                        val text = textOrNull(bytes.toByteArray())?.takeIf { it.isNotEmpty() }
                        val nested = if (text != null) null else runCatching { UnknownFieldSet.parseFrom(bytes) }.getOrNull()?.takeIf { it.asMap().isNotEmpty() && it.toByteString() == bytes }
                        text?.let { JsonPrimitive(it) } ?: nested?.let { fields(it) } ?: JsonPrimitive("base64:" + Base64.getEncoder().encodeToString(bytes.toByteArray()))
                    } + field.groupList.map { fields(it) }
                put(number.toString(), if (values.size == 1) values[0] else JsonArray(values))
            }
        }
    }
}
