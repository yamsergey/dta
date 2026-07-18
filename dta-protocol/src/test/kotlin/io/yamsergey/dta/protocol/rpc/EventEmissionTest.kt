package io.yamsergey.dta.protocol.rpc

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EventEmissionTest {

    private class Harness {
        val sentTexts = Collections.synchronizedList(mutableListOf<String>())
        val sentBinaries = Collections.synchronizedList(mutableListOf<ByteArray>())
        val registry = MethodRegistry().also { DiagnosticMethods.install(it) }
        val session = ServerSession(
            identity = SessionIdentity("com.example.fixture", "0.1.0"),
            registry = registry,
            sendText = { sentTexts += it },
            sendBinary = { sentBinaries += it },
        )

        init {
            session.handleText(
                DtaJson.encodeToString(
                    Message.serializer(),
                    Request(
                        id = 1000,
                        method = "hello",
                        params = DtaJson.encodeToJsonElement(
                            HelloParams.serializer(),
                            HelloParams(PROTOCOL_VERSION, "test", "dev"),
                        ).jsonObject,
                    ),
                ),
            )
            sentTexts.clear()
        }

        fun request(id: Long, method: String, params: kotlinx.serialization.json.JsonObject? = null) =
            session.handleText(
                DtaJson.encodeToString(Message.serializer(), Request(id, method, params)),
            )

        fun messages(): List<Message> =
            synchronized(sentTexts) { sentTexts.map { DtaJson.decodeFromString(Message.serializer(), it) } }
    }

    @Test
    fun `emit_event pushes the requested number of ordered events`() {
        val h = Harness()
        h.request(1, "emit_event", buildJsonObject { put("name", "fixture_tick"); put("count", 3) })

        val events = h.messages().filterIsInstance<Event>()
        assertEquals(3, events.size)
        assertEquals(listOf(1L, 2L, 3L), events.map { it.seq })
        assertTrue(events.all { it.name == "fixture_tick" })
        assertEquals(listOf(1, 2, 3), events.map { it.data!!.jsonObject["n"]!!.jsonPrimitive.int })

        val response = h.messages().filterIsInstance<Response>().single()
        assertEquals(3, response.result!!.jsonObject["emitted"]!!.jsonPrimitive.int)
    }

    @Test
    fun `seq continues gap-free across multiple emissions`() {
        val h = Harness()
        h.request(1, "emit_event", buildJsonObject { put("name", "fixture_tick"); put("count", 2) })
        h.request(2, "emit_event", buildJsonObject { put("name", "fixture_tick"); put("count", 2) })

        assertEquals(listOf(1L, 2L, 3L, 4L), h.messages().filterIsInstance<Event>().map { it.seq })
    }

    @Test
    fun `emit_event without a name is INVALID_PARAMS`() {
        val h = Harness()
        h.request(1, "emit_event", buildJsonObject { put("count", 1) })

        val response = assertIs<Response>(h.messages().single())
        assertEquals(ErrorCodes.INVALID_PARAMS, response.error!!.code)
        assertTrue("name" in response.error!!.message)
    }

    @Test
    fun `echo returns its params verbatim`() {
        val h = Harness()
        h.request(7, "echo", buildJsonObject { put("x", 42) })
        val response = assertIs<Response>(h.messages().single())
        assertEquals(42, response.result!!.jsonObject["x"]!!.jsonPrimitive.int)
    }

    @Test
    fun `blob announces and ships id-tagged binary frames`() {
        val h = Harness()
        h.request(9, "blob", buildJsonObject { put("bytes", 700_000) })

        val response = h.messages().filterIsInstance<Response>().single()
        val announcement = response.binary!!
        assertEquals(700_000L, announcement.totalBytes)
        assertEquals(3, announcement.frames, "700KB should chunk into 3 x 256KiB frames")
        assertEquals(3, h.sentBinaries.size)

        var offset = 0
        h.sentBinaries.forEach { frame ->
            var id = 0L
            for (i in 0 until 8) id = (id shl 8) or (frame[i].toLong() and 0xFF)
            assertEquals(9L, id, "binary frame must carry the request id")
            for (i in 8 until frame.size) {
                assertEquals(((offset + i - 8) % 251).toByte(), frame[i], "payload pattern mismatch at $offset")
            }
            offset += frame.size - 8
        }
        assertEquals(700_000, offset)
    }

    @Test
    fun `blob without positive bytes is INVALID_PARAMS`() {
        val h = Harness()
        h.request(1, "blob", buildJsonObject { put("bytes", -5) })
        val response = assertIs<Response>(h.messages().single())
        assertEquals(ErrorCodes.INVALID_PARAMS, response.error!!.code)
        assertTrue("bytes" in response.error!!.message)
    }
}
