package io.yamsergey.dta.protocol.web

import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WebModelTest {
    @Test
    fun `steps round trip and leave unset fields out`() {
        val steps = WebIngest(listOf(
            WebStep(WebStep.HTTP_START, "s1:7", library = Web.WEBVIEW, method = "GET", url = "http://x/a", headers = listOf(Header("Accept", "*/*")), timeMs = 5),
            WebStep(WebStep.WS_MESSAGE, "s1:9", direction = Direction.IN, opcode = MessageOpcode.BINARY, base64 = "AQI="),
        ))
        val json = DtaJson.encodeToString(WebIngest.serializer(), steps)
        assertEquals(steps, DtaJson.decodeFromString(WebIngest.serializer(), json))
        assertEquals(false, "status" in json, json)
    }

    @Test
    fun `a body comes as text or base64`() {
        assertContentEquals("hé".toByteArray(), WebStep(WebStep.HTTP_RESPONSE_BODY, "k", text = "hé").bytes)
        assertContentEquals(byteArrayOf(1, 2), WebStep(WebStep.HTTP_RESPONSE_BODY, "k", base64 = "AQI=").bytes)
        assertEquals(null, WebStep(WebStep.HTTP_COMPLETE, "k").bytes)
    }

    @Test
    fun `status round trips`() {
        val s = WebStatus(WebSourceStatus(WebSourceStatus.CAPTURING), WebSourceStatus(WebSourceStatus.OFF, Web.OFF),
            listOf(WebPage("T1", Web.WEBVIEW, "http://x/", partial = true, requests = 3, attachedAtMs = 1)),
            listOf(DevtoolsEndpoint(Web.WEBVIEW, "http://127.0.0.1:9222", "ws://127.0.0.1:9222/devtools/browser", "webview_devtools_remote_42")))
        assertEquals(s, DtaJson.decodeFromString(WebStatus.serializer(), DtaJson.encodeToString(WebStatus.serializer(), s)))
    }
}
