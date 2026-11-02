package io.yamsergey.dta.cli.format

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** Feature 020: a push result in the terminal. */
class WsPushFormatTest {
    private val f = Formatters(Style.PLAIN)

    @Test
    fun `a push says where the message went and how`() {
        assertEquals(
            "Pushed to r2-7:12 as entry r2-7:57, delivered on the library's reading thread after 43 ms.",
            f.format("network.ws.push", Json.parseToJsonElement("""{"entryId":"r2-7:57","connectionId":"r2-7:12","delivery":"reading-thread","waitedMs":43}""")),
        )
        assertEquals(
            "Pushed to r2-7:12 as entry r2-7:58, delivered from DTA's thread after 1000 ms (no frame arrived).\nThe app's listener threw: IllegalStateException: boom",
            f.format("network.ws.push", Json.parseToJsonElement("""{"entryId":"r2-7:58","connectionId":"r2-7:12","delivery":"dta-thread","waitedMs":1000,"listenerError":"IllegalStateException: boom"}""")),
        )
    }
}
