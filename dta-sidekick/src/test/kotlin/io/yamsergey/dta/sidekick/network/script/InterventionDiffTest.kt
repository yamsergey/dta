package io.yamsergey.dta.sidekick.network.script

import io.yamsergey.dta.protocol.network.ChangedField
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.InterventionRecord
import io.yamsergey.dta.protocol.network.ScriptHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InterventionDiffTest {

    @Test
    fun `no effect, delay, throttle or error is no record`() {
        assertNull(InterventionDiff.record("s", ScriptHandler.REQUEST, ScriptResult<RequestChange>(Effect.None, scriptMs = 3)))
    }

    @Test
    fun `changed fields are exactly the ones the script changed`() {
        val record = InterventionDiff.record(
            "s", ScriptHandler.REQUEST,
            ScriptResult(Effect.Change(RequestChange(headers = listOf(Header("A", "b")), body = byteArrayOf(1))), delayMs = 10, scriptMs = 2),
        )
        assertEquals(InterventionRecord("s", listOf(ScriptHandler.REQUEST), listOf(ChangedField.HEADERS, ChangedField.BODY), delayMs = 10, scriptMs = 2), record)
        val response = InterventionDiff.record("s", ScriptHandler.RESPONSE, ScriptResult(Effect.Change(ResponseChange(status = 200))))
        assertEquals(listOf(ChangedField.STATUS), response!!.changed)
        val message = InterventionDiff.record("s", ScriptHandler.WS_SEND, ScriptResult(Effect.Change(MessageChange(text = "x"))))
        assertEquals(listOf(ChangedField.PAYLOAD), message!!.changed)
    }

    @Test
    fun `mock, drop, delay-only, throttle and errors are recorded`() {
        assertEquals(true, InterventionDiff.record("s", ScriptHandler.REQUEST, ScriptResult<RequestChange>(Effect.Mock(ScriptedResponse(503, emptyList(), byteArrayOf()))))!!.mocked)
        assertEquals(true, InterventionDiff.record("s", ScriptHandler.REQUEST, ScriptResult<RequestChange>(Effect.Drop))!!.dropped)
        assertEquals(2000, InterventionDiff.record("s", ScriptHandler.REQUEST, ScriptResult<RequestChange>(Effect.None, delayMs = 2000))!!.delayMs)
        assertEquals(100, InterventionDiff.record("s", ScriptHandler.RESPONSE, ScriptResult<ResponseChange>(Effect.None, throttleBytesPerSecond = 100))!!.throttleBytesPerSecond)
        val error = InterventionDiff.record("s", ScriptHandler.REQUEST, ScriptResult<RequestChange>(Effect.None, error = "onRequest: boom"))!!
        assertEquals("onRequest: boom", error.error)
        assertEquals(emptyList(), error.handlers, "a failed handler had no effect")
    }

    @Test
    fun `request and response records of one exchange merge`() {
        val a = InterventionRecord("s", listOf(ScriptHandler.REQUEST), listOf(ChangedField.HEADERS), delayMs = 100, scriptMs = 1)
        val b = InterventionRecord("s", listOf(ScriptHandler.RESPONSE), listOf(ChangedField.STATUS), delayMs = 50, scriptMs = 2, throttleBytesPerSecond = 10)
        assertEquals(
            InterventionRecord("s", listOf(ScriptHandler.REQUEST, ScriptHandler.RESPONSE), listOf(ChangedField.HEADERS, ChangedField.STATUS), delayMs = 150, scriptMs = 3, throttleBytesPerSecond = 10),
            InterventionDiff.merge(a, b),
        )
        assertEquals(a, InterventionDiff.merge(a, null))
        assertEquals(b, InterventionDiff.merge(null, b))
    }
}
