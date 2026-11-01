package io.yamsergey.dta.mcp

import io.yamsergey.dta.client.DaemonCallException
import io.yamsergey.dta.daemon.ui.UiJournal
import io.yamsergey.dta.protocol.ops.DeviceRule
import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.protocol.ops.SelectionStatement
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
import io.yamsergey.dta.protocol.ui.UiEventsPage
import io.yamsergey.dta.protocol.ui.UiJournalRead
import io.yamsergey.dta.protocol.ui.UiSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Feature 015 (T017, research R10): every tool answer says what UI came and went since the last call. */
class McpUiSummaryTest {
    private val journal = UiJournal(session = "j")
    private val uiCalls = mutableListOf<JsonObject>()
    private var uiFails = false
    private var seq = 0L

    private fun toast(text: String) {
        seq++
        journal.ingest(UiJournalRead(1, listOf(UiEvent("1:$seq", seq, seq, seq, UiEventKind.TOAST, UiSource.TOAST, text = text)), lastSeq = seq))
    }

    private val tools = McpTools(call = { op, params ->
        if (op == "ui.events") {
            uiCalls += params
            if (uiFails) throw DaemonCallException(-32014, "app not connected", null)
            val since = (params["since"] as? JsonPrimitive)?.content
            val page: UiEventsPage = if (since == "end") journal.page(journal.end(), 50) else journal.page(since, 50)
            Selected(null, DtaJson.encodeToJsonElement(UiEventsPage.serializer(), page))
        } else {
            Selected(SelectionStatement("emulator-5554", DeviceRule.ONLY_DEVICE, app = "com.example"), buildJsonObject { put("ok", true) })
        }
    })

    private fun call(session: String? = null) = runBlocking { tools.invoke("network_list", JsonObject(emptyMap()), session) }

    @Test
    fun `the first call starts after what is kept and adds nothing`() {
        toast("old")
        val first = call()
        assertFalse("Since your last call" in first.text, first.text)
        assertEquals("end", (uiCalls.single()["since"] as JsonPrimitive).content)
        assertEquals("com.example", (uiCalls.single()["app"] as JsonPrimitive).content)
    }

    @Test
    fun `events between two calls end the second answer`() {
        call()
        toast("Saved")
        val second = call()
        assertTrue(second.text.endsWith("Since your last call: 1 UI event — toast \"Saved\". ui_events since=j:1 for details."), second.text)
        val third = call()
        assertFalse("Since your last call" in third.text, third.text)
    }

    @Test
    fun `each session has its own cursor`() {
        call("a")
        call("b")
        toast("Saved")
        assertTrue("toast \"Saved\"" in call("a").text)
        assertTrue("toast \"Saved\"" in call("b").text)
    }

    @Test
    fun `a journal that cannot be read never fails the tool`() {
        call()
        uiFails = true
        val outcome = call()
        assertFalse(outcome.isError)
        assertFalse("Since your last call" in outcome.text)
    }

    @Test
    fun `reading the journal itself adds no summary`() = runBlocking {
        call()
        toast("Saved")
        val calls = uiCalls.size
        tools.invoke("ui_events", JsonObject(emptyMap()), null)
        assertEquals(calls + 1, uiCalls.size)
    }
}
