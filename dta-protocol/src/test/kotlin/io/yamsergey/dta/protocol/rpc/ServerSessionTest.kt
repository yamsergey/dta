package io.yamsergey.dta.protocol.rpc

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerSessionTest {

    private class Harness(protocolVersion: Int = PROTOCOL_VERSION) {
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val closes = mutableListOf<Pair<Int, String>>()
        val registry = MethodRegistry().apply {
            register("ping") { JsonPrimitive("pong") }
        }
        val session = ServerSession(
            identity = SessionIdentity(
                packageName = "com.example.fixture",
                serverVersion = "0.1.0",
                protocolVersion = protocolVersion,
            ),
            registry = registry,
            sendText = { sent += it },
            requestClose = { code, reason -> closes += code to reason },
        )

        fun receive(message: Message) =
            session.handleText(DtaJson.encodeToString(Message.serializer(), message))

        fun lastSent(): Message = DtaJson.decodeFromString(Message.serializer(), sent.last())

        fun hello(version: Int = PROTOCOL_VERSION) = receive(
            Request(
                id = 1,
                method = "hello",
                params = DtaJson.encodeToJsonElement(
                    HelloParams.serializer(),
                    HelloParams(protocolVersion = version, client = "dta-daemon", clientVersion = "0.1.0"),
                ).jsonObject,
            ),
        )
    }

    @Test
    fun `requests before hello are refused with NOT_READY`() {
        val h = Harness()
        h.receive(Request(id = 5, method = "ping"))

        val response = assertIs<Response>(h.lastSent())
        assertEquals(5, response.id)
        assertEquals(ErrorCodes.NOT_READY, response.error!!.code)
        assertFalse(h.session.helloCompleted)
    }

    @Test
    fun `hello with matching version succeeds and reports identity`() {
        val h = Harness()
        h.hello()

        val response = assertIs<Response>(h.lastSent())
        assertNull(response.error)
        val result = DtaJson.decodeFromJsonElement(HelloResult.serializer(), response.result!!)
        assertEquals(PROTOCOL_VERSION, result.protocolVersion)
        assertEquals("com.example.fixture", result.packageName)
        assertEquals("dta-sidekick", result.server)
        assertEquals("0.1.0", result.serverVersion)
        assertTrue(h.session.helloCompleted)
        assertTrue(h.closes.isEmpty())
    }

    @Test
    fun `methods dispatch normally after successful hello`() {
        val h = Harness()
        h.hello()
        h.receive(Request(id = 2, method = "ping"))

        val response = assertIs<Response>(h.lastSent())
        assertEquals(2, response.id)
        assertEquals("pong", response.result!!.jsonPrimitive.content)
    }

    @Test
    fun `version mismatch answers VERSION_INCOMPATIBLE with both versions then closes normally`() {
        val h = Harness()
        h.hello(version = 99)

        val response = assertIs<Response>(h.lastSent())
        val error = response.error!!
        assertEquals(ErrorCodes.VERSION_INCOMPATIBLE, error.code)
        assertEquals(99, error.details!!["daemon"]!!.jsonPrimitive.int)
        assertEquals(PROTOCOL_VERSION, error.details!!["sidekick"]!!.jsonPrimitive.int)
        assertTrue("rebuild" in error.message.lowercase(), "remedy expected in: ${error.message}")
        assertEquals(1000, h.closes.single().first)
        assertFalse(h.session.helloCompleted)
    }

    @Test
    fun `hello with malformed params is INVALID_PARAMS not a crash`() {
        val h = Harness()
        h.receive(Request(id = 1, method = "hello"))

        val response = assertIs<Response>(h.lastSent())
        assertEquals(ErrorCodes.INVALID_PARAMS, response.error!!.code)
    }

    @Test
    fun `session works against a mismatching-then-correct daemon sequence`() {
        val h = Harness()
        h.hello(version = 99)
        // A fresh connection would be a fresh session; but a retry on the same
        // session with the right version must also succeed (no poisoned state).
        h.hello()
        assertTrue(h.session.helloCompleted)
    }

    @Test
    fun `events flow through the session with seq`() {
        val h = Harness()
        h.hello()
        h.session.sendEvent("fixture_tick", JsonPrimitive(1))
        val event = assertIs<Event>(h.lastSent())
        assertEquals("fixture_tick", event.name)
        assertEquals(1, event.seq)
    }
}
