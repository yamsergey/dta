package io.yamsergey.dta.cli.format

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/** Feature 019: a partly captured library shows what is not captured, in the terminal. */
class CapabilityFormatTest {
    private val f = Formatters(Style.PLAIN)

    @Test
    fun `a degraded library lists its events not captured and keeps the raw detail out`() {
        val report = """{"packageName":"io.example","sidekickVersion":"1","capabilities":[{"name":"network","status":"degraded",
            "reason":"okhttp3-ws: failures is not captured (…).","libraries":[
            {"name":"okhttp3-ws","status":"degraded","reason":"failures is not captured (RealWebSocket.failWebSocket(Exception, Response) is not in this version of RealWebSocket).",
             "notCaptured":["failures"],"captured":["connections","close"],"detail":"RetransformClasses: JVMTI_ERROR_X"}]}]}"""
        val text = f.format("capability.report", Json.parseToJsonElement(report))
        assertTrue("not captured: failures" in text, text)
        assertTrue("JVMTI" !in text, text)
    }
}
