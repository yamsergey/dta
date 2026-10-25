package io.yamsergey.dta.protocol.rpc

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvelopeTest {

    @Test
    fun `request round-trips with type discriminator`() {
        val request = Request(id = 7, method = "identity", params = buildJsonObject { put("x", 1) })
        val json = DtaJson.encodeToString<Message>(request)
        assertTrue(""""type":"request"""" in json, json)
        assertEquals(request, DtaJson.decodeFromString<Message>(json))
    }

    @Test
    fun `response with result round-trips`() {
        val response = Response(id = 7, result = JsonPrimitive("ok"))
        val decoded = DtaJson.decodeFromString<Message>(DtaJson.encodeToString<Message>(response))
        assertEquals(response, decoded)
    }

    @Test
    fun `response with error round-trips including details`() {
        val response = Response(
            id = 8,
            error = ErrorInfo(
                code = ErrorCodes.VERSION_INCOMPATIBLE,
                message = "sidekick protocol 2 != daemon protocol 1 — rebuild the app",
                details = buildJsonObject {
                    put("daemon", 1)
                    put("sidekick", 2)
                },
            ),
        )
        val decoded = DtaJson.decodeFromString<Message>(DtaJson.encodeToString<Message>(response))
        assertEquals(response, decoded)
    }

    @Test
    fun `event round-trips and carries seq`() {
        val event = Event(name = "fixture_tick", seq = 3, data = buildJsonObject { put("n", 3) })
        val json = DtaJson.encodeToString<Message>(event)
        assertTrue(""""type":"event"""" in json)
        assertEquals(event, DtaJson.decodeFromString<Message>(json))
    }

    @Test
    fun `response must carry exactly one of result or error - neither rejected`() {
        assertFailsWith<IllegalArgumentException> { Response(id = 1) }
        val raw = """{"type":"response","id":1}"""
        assertFailsWith<Exception> { DtaJson.decodeFromString<Message>(raw) }
    }

    @Test
    fun `response must carry exactly one of result or error - both rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Response(id = 1, result = JsonPrimitive(1), error = ErrorInfo(1, "x"))
        }
        val raw = """{"type":"response","id":1,"result":1,"error":{"code":1,"message":"x"}}"""
        assertFailsWith<Exception> { DtaJson.decodeFromString<Message>(raw) }
    }

    @Test
    fun `unknown fields are ignored for forward compatibility`() {
        val raw = """{"type":"request","id":1,"method":"ping","futureField":{"a":1}}"""
        val decoded = DtaJson.decodeFromString<Message>(raw)
        assertIs<Request>(decoded)
        assertEquals("ping", decoded.method)
    }

    @Test
    fun `absent optional fields stay null and are omitted when encoding`() {
        val json = DtaJson.encodeToString<Message>(Request(id = 1, method = "ping"))
        assertTrue("params" !in json, json)
        val decoded = DtaJson.decodeFromString<Message>(json)
        assertNull((decoded as Request).params)
    }

    @Test
    fun `capability status requires reason unless available`() {
        // available without reason: fine
        CapabilityStatus(name = "channel", status = CapabilityStatus.Status.AVAILABLE)
        // degraded/unavailable without reason: rejected (constitution III)
        assertFailsWith<IllegalArgumentException> {
            CapabilityStatus(name = "layout", status = CapabilityStatus.Status.DEGRADED)
        }
        assertFailsWith<IllegalArgumentException> {
            CapabilityStatus(name = "layout", status = CapabilityStatus.Status.UNAVAILABLE)
        }
        val raw = """{"name":"layout","status":"degraded"}"""
        assertFailsWith<Exception> { DtaJson.decodeFromString<CapabilityStatus>(raw) }
    }

    @Test
    fun `capability status serializes status names in lowercase`() {
        val json = DtaJson.encodeToString(
            CapabilityStatus("channel", CapabilityStatus.Status.AVAILABLE),
        )
        assertTrue(""""status":"available"""" in json, json)
    }

    @Test
    fun `degraded markers and binary announcement round-trip on responses`() {
        val response = Response(
            id = 9,
            result = buildJsonObject { put("ok", true) },
            degraded = listOf(DegradationMarker("layout", "inspection disabled: too-late init")),
            binary = BinaryAnnouncement(frames = 2, totalBytes = 1024),
        )
        val json = DtaJson.encodeToString<Message>(response)
        assertTrue(""""degraded"""" in json)
        assertTrue(""""binary"""" in json)
        assertEquals(response, DtaJson.decodeFromString<Message>(json))
    }

    @Test
    fun `hello params and result round-trip`() {
        val params = HelloParams(protocolVersion = 1, client = "dta-daemon", clientVersion = "0.1.0")
        assertEquals(params, DtaJson.decodeFromString(DtaJson.encodeToString(params)))

        val result = HelloResult(
            protocolVersion = 1,
            server = "dta-sidekick",
            serverVersion = "0.1.0",
            packageName = "com.example.fixture",
        )
        assertEquals(result, DtaJson.decodeFromString(DtaJson.encodeToString(result)))
    }

    @Test
    fun `malformed json raises a serialization error not a crash`() {
        assertFailsWith<SerializationException> {
            DtaJson.decodeFromString<Message>("""{"type":"nope","id":1}""")
        }
    }

    @Test
    fun `layout capability lists servable detail levels and omits them when null`() {
        val withLevels = CapabilityStatus(
            "layout", CapabilityStatus.Status.DEGRADED, "compose-tooling-absent",
            levels = listOf(io.yamsergey.dta.protocol.layout.DetailLevel.SEMANTIC),
        )
        val json = DtaJson.encodeToString(withLevels)
        assertTrue(""""levels":["semantic"]""" in json, json)
        assertEquals(withLevels, DtaJson.decodeFromString<CapabilityStatus>(json))
        assertTrue("levels" !in DtaJson.encodeToString(CapabilityStatus("channel", CapabilityStatus.Status.AVAILABLE)))
    }
}
