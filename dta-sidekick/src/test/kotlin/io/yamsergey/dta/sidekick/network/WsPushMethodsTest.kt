package io.yamsergey.dta.sidekick.network

import io.yamsergey.dta.protocol.network.InjectionDelivery
import io.yamsergey.dta.protocol.network.InjectionSource
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsInjection
import io.yamsergey.dta.protocol.network.WsState
import io.yamsergey.dta.protocol.rpc.InvalidParamsException
import io.yamsergey.dta.sidekick.util.ThreadScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Feature 020 (contract §2): `network_ws_push` validates its params and waits for the delivery. */
class WsPushMethodsTest {

    private val got = mutableListOf<PushMessage>()
    private val recorded = mutableListOf<WsInjection>()
    private val recorder = object : PushRecorder {
        override val runId = "r2-7"
        override fun connection(id: String) =
            if (id == "r2-7:1") WsConnectionEntry(id, runId, 0, "okhttp3-ws", url = "ws://h", state = WsState.OPEN) else null
        override fun record(connectionId: String, message: PushMessage, injection: WsInjection): String {
            recorded += injection
            return "r2-7:5"
        }
    }
    private val injector = WsInjector(recorder, ThreadScheduler("test-push")).apply {
        attach("r2-7:1", object : WsDelivery {
            override fun alive() = true
            override fun ping() = false
            override fun deliver(message: PushMessage) {
                got += message
            }
        })
    }
    private var hooksOff: String? = null
    private val methods = WsPushMethods({ injector }) { hooksOff }

    private fun params(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `a text push waits for the delivery and returns its entry`() {
        val result = methods.push(params("""{"connection":"r2-7:1","text":"hi","waitMs":0}"""))
        assertEquals("r2-7:5", result.entryId)
        assertEquals("r2-7:1", result.connectionId)
        assertEquals(InjectionDelivery.DTA_THREAD, result.delivery)
        assertEquals("hi", got.single().text)
        assertEquals(InjectionSource.CLIENT, recorded.single().by)
    }

    @Test
    fun `base64 is a binary push, and the only open connection is used when none is named`() {
        methods.push(params("""{"base64":"AQL/","waitMs":0}"""))
        assertContentEquals(byteArrayOf(1, 2, -1), got.single().bytes)
    }

    @Test
    fun `params are checked`() {
        assertEquals("give exactly one of text or base64", assertFailsWith<InvalidParamsException> { methods.push(params("""{"connection":"r2-7:1"}""")) }.message)
        assertEquals("give exactly one of text or base64", assertFailsWith<InvalidParamsException> { methods.push(params("""{"text":"a","base64":"AQ=="}""")) }.message)
        assertTrue(assertFailsWith<InvalidParamsException> { methods.push(params("""{"base64":"*not*"}""")) }.message!!.startsWith("base64 is not valid"))
        assertEquals("waitMs must be 0 or more", assertFailsWith<InvalidParamsException> { methods.push(params("""{"text":"a","waitMs":-1}""")) }.message)
    }

    @Test
    fun `a push that cannot start fails with its plain reason`() {
        val e = assertFailsWith<IllegalStateException> { methods.push(params("""{"connection":"r2-7:9","text":"a"}""")) }
        assertTrue(e.message!!.startsWith("r2-7:9 is not a WebSocket connection of the app"), e.message)
    }

    @Test
    fun `with hooks off a push says why`() {
        hooksOff = "they need Android 9 (API 28) or later, and this device runs API 26"
        val e = assertFailsWith<IllegalStateException> { methods.push(params("""{"text":"a"}""")) }
        assertEquals("pushes cannot be delivered, because DTA's hooks are off: they need Android 9 (API 28) or later, and this device runs API 26", e.message)
    }
}
