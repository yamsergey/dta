package io.yamsergey.dta.mcp

import io.yamsergey.dta.client.DaemonCallException
import io.yamsergey.dta.daemon.timeline.Timeline
import io.yamsergey.dta.protocol.ops.DeviceRule
import io.yamsergey.dta.protocol.ops.Selected
import io.yamsergey.dta.protocol.ops.SelectionStatement
import io.yamsergey.dta.protocol.rpc.DtaJson
import io.yamsergey.dta.protocol.timeline.InputAction
import io.yamsergey.dta.protocol.timeline.InputPayload
import io.yamsergey.dta.protocol.timeline.Kinds
import io.yamsergey.dta.protocol.timeline.Sources
import io.yamsergey.dta.protocol.timeline.TimelineEvent
import io.yamsergey.dta.protocol.timeline.TimelinePage
import io.yamsergey.dta.protocol.timeline.TimelineRead
import io.yamsergey.dta.protocol.ui.UiEvent
import io.yamsergey.dta.protocol.ui.UiEventKind
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

/** Features 015/016 (T015): every tool answer says what the app did since the last call, from every source. */
class McpTimelineSummaryTest {
    private val journal = Timeline(session = "j")
    private val uiCalls = mutableListOf<JsonObject>()
    private var uiFails = false
    private var seq = 0L

    private fun toast(text: String) {
        seq++
        val ui = UiEvent("1:$seq", seq, seq, seq, UiEventKind.TOAST, UiSource.TOAST, text = text)
        journal.ingest(TimelineRead(1, listOf(TimelineEvent(ui.id, seq, seq, seq, Sources.UI, Kinds.ui("toast"), ui = ui)), lastSeq = seq))
    }

    private fun click(text: String) {
        seq++
        journal.ingest(TimelineRead(1, listOf(TimelineEvent("1:$seq", seq, seq, seq, Sources.INPUT, Kinds.CLICK, input = InputPayload(InputAction.CLICK, text = text, via = "view"))), lastSeq = seq))
    }

    private val tools = McpTools(call = { op, params ->
        if (op == "timeline.events") {
            uiCalls += params
            if (uiFails) throw DaemonCallException(-32014, "app not connected", null)
            val since = (params["since"] as? JsonPrimitive)?.content
            val page: TimelinePage = journal.page(if (since == "end") journal.end() else since, 50)
            Selected(null, DtaJson.encodeToJsonElement(TimelinePage.serializer(), page))
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
        assertTrue(second.text.endsWith("Since your last call: 1 event — toast \"Saved\". timeline_events since=j:1 for details."), second.text)
        val third = call()
        assertFalse("Since your last call" in third.text, third.text)
    }

    @Test
    fun `every source is counted`() {
        call()
        click("Delete")
        toast("Deleted")
        assertTrue(call().text.endsWith("Since your last call: 2 events — click \"Delete\", toast \"Deleted\". timeline_events since=j:2 for details."))
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
    fun `a timeline that cannot be read never fails the tool`() {
        call()
        uiFails = true
        val outcome = call()
        assertFalse(outcome.isError)
        assertFalse("Since your last call" in outcome.text)
    }

    @Test
    fun `reading the timeline itself adds no summary`() = runBlocking {
        call()
        toast("Saved")
        val calls = uiCalls.size
        tools.invoke("timeline_events", JsonObject(emptyMap()), null)
        assertEquals(calls + 1, uiCalls.size)
    }
}
