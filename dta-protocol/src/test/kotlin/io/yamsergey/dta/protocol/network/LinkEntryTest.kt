package io.yamsergey.dta.protocol.network

import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.protocol.rpc.DtaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 012 T001/T002: links in the app's history, out of the network views unless asked for. */
class LinkEntryTest {
    private val link = LinkEntry(
        "r1-1:3", "r1-1", 30, uri = "fixture://item/42?ref=mail", scheme = "fixture", host = "item", path = "/42",
        query = listOf(Header("ref", "mail")), action = "android.intent.action.VIEW", activity = "io.example.DeepLinkActivity",
        delivery = LinkDelivery.COLD_START, extras = listOf(IntentExtra("token", "String", "abc"), IntentExtra("blob", notRead = "class not found")),
    )
    private val http = HttpEntry("r1-1:1", "r1-1", 10, "okhttp3", method = "GET", url = "https://api/x", state = HttpState.COMPLETE, status = 200)

    @Test
    fun `a link serializes with its kind`() {
        val json = DtaJson.encodeToString(NetworkEntry.serializer(), link)
        assertTrue("\"kind\":\"link\"" in json || "\"type\":\"link\"" in json, json)
        assertEquals(link, DtaJson.decodeFromString(NetworkEntry.serializer(), json))
    }

    private fun view(entries: List<NetworkEntry>) = NetworkHistoryView(
        { entries }, { id -> entries.firstOrNull { it.id == id } },
        { HistoryContext("r1-1", Coverage(searched = 0, stored = 0, persisted = true), CapabilityStatus("network", CapabilityStatus.Status.AVAILABLE)) },
        bodyBytes = { _, _ -> null },
    )

    @Test
    fun `network views leave links out unless kinds asks for them`() {
        val v = view(listOf(http, link))
        assertEquals(listOf("r1-1:1"), v.list(NetworkQuery()).entries.map { it.id })
        assertEquals(1, v.list(NetworkQuery()).total)
        assertEquals(listOf("r1-1:3"), v.list(NetworkQuery(filter = NetworkFilter(kinds = listOf(EntryKind.LINK)))).entries.map { it.id })
    }

    @Test
    fun `links filter by URI text, activity and delivery`() {
        val v = view(listOf(http, link))
        fun ids(f: NetworkFilter) = v.list(NetworkQuery(filter = f.copy(kinds = listOf(EntryKind.LINK)))).entries.map { it.id }
        assertEquals(listOf("r1-1:3"), ids(NetworkFilter(url = "item/42")))
        assertEquals(listOf("r1-1:3"), ids(NetworkFilter(activity = "DeepLink")))
        assertEquals(emptyList(), ids(NetworkFilter(activity = "Main")))
        assertEquals(listOf("r1-1:3"), ids(NetworkFilter(delivery = "cold-start")))
        assertEquals(emptyList(), ids(NetworkFilter(delivery = "new-intent")))
    }
}
