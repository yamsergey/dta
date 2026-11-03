package io.yamsergey.dta.daemon.data

import io.yamsergey.dta.daemon.connection.AppConnection
import io.yamsergey.dta.daemon.network.proto.SchemaRegistry
import io.yamsergey.dta.protocol.rpc.Event
import io.yamsergey.dta.protocol.rpc.HelloResult
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import io.yamsergey.dta.protocol.rpc.PROTOCOL_VERSION
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration

/** A device file of [content], read in the sidekick's chunks; writes collected. */
private class FakeFiles(val content: ByteArray, val chunk: Int) : AppConnection {
    override val hello = HelloResult(PROTOCOL_VERSION, "dta-sidekick", "0.1.0", "app")
    override val events: SharedFlow<Event> = MutableSharedFlow()
    override val isOpen = true
    val written = java.io.ByteArrayOutputStream()
    var lastSeen = false
    var calls = 0

    override suspend fun request(method: String, params: JsonObject?, timeout: Duration?): JsonElement {
        calls++
        val p = params!!
        return when (method) {
            "data_files_read" -> {
                val offset = p["offset"]!!.jsonPrimitive.long.toInt()
                val bytes = content.copyOfRange(offset, minOf(content.size, offset + chunk))
                buildJsonObject {
                    put("path", p["path"]!!.jsonPrimitive.content); put("offset", offset.toLong())
                    put("base64", Base64.getEncoder().encodeToString(bytes)); put("sizeBytes", content.size.toLong())
                }
            }
            "data_files_write" -> {
                check(p["offset"]!!.jsonPrimitive.long == written.size().toLong())
                written.write(Base64.getDecoder().decode(p["base64"]!!.jsonPrimitive.content))
                lastSeen = p["last"]!!.jsonPrimitive.boolean
                buildJsonObject { put("store", p["path"]!!.jsonPrimitive.content); put("change", "written"); put("access", "live") }
            }
            else -> error(method)
        }
    }

    override suspend fun close() = Unit
}

class DataServiceTest {
    private val schemas = SchemaRegistry(Files.createTempDirectory("schemas").toFile())

    @Test
    fun `a short text file comes inline, a binary one as base64, a long one only to a file`() = runBlocking<Unit> {
        val text = DataService("app", schemas) { FakeFiles("héllo\nworld".toByteArray(), 4) }.filesRead("data:/files/a.txt", null)
        assertEquals("héllo\nworld", text.text)
        assertNull(text.base64)
        val bin = DataService("app", schemas) { FakeFiles(byteArrayOf(0, 1, 2, -1), 3) }.filesRead("data:/files/b.bin", null)
        assertEquals("AAEC/w==", bin.base64)
        val big = ByteArray(DataService.INLINE + 10) { (it % 251).toByte() }
        val fake = FakeFiles(big, DataService.CHUNK)
        assertFailsWith<InvalidParamsException> { DataService("app", schemas) { fake }.filesRead("data:/files/big", null) }
        val out = File(Files.createTempDirectory("out").toFile(), "big.bin")
        val saved = DataService("app", schemas) { fake }.filesRead("data:/files/big", out.path)
        assertContentEquals(big, out.readBytes())
        assertEquals(out.absolutePath, saved.output)
        assertEquals(64, saved.sha256!!.length)
    }

    @Test
    fun `a host file goes up in chunks, the last one marked`() = runBlocking<Unit> {
        val data = ByteArray(DataService.CHUNK * 2 + 7) { it.toByte() }
        val input = File(Files.createTempDirectory("in").toFile(), "up.bin").apply { writeBytes(data) }
        val fake = FakeFiles(ByteArray(0), 1)
        DataService("app", schemas) { fake }.filesWrite("data:/files/up.bin", null, null, input.path)
        assertContentEquals(data, fake.written.toByteArray())
        assertEquals(true, fake.lastSeen)
        assertEquals(3, fake.calls)
        val empty = FakeFiles(ByteArray(0), 1)
        DataService("app", schemas) { empty }.filesWrite("data:/files/e.txt", "", null, null)
        assertEquals(1, empty.calls)
        assertFailsWith<InvalidParamsException> { DataService("app", schemas) { empty }.filesWrite("data:/x", "a", "AA==", null) }
    }

    @Test
    fun `a message without its type shows fields by number, nested messages and text`() {
        // { 1: 150, 2: "hi", 3: { 1: 7 } }
        val bytes = byteArrayOf(0x08, 0x96.toByte(), 0x01, 0x12, 0x02, 'h'.code.toByte(), 'i'.code.toByte(), 0x1a, 0x02, 0x08, 0x07)
        val json = DataService.rawFields(bytes).jsonObject
        assertEquals(JsonPrimitive(150), json["1"])
        assertEquals(JsonPrimitive("hi"), json["2"])
        assertEquals(JsonPrimitive(7), json["3"]!!.jsonObject["1"])
    }
}
