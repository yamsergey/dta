package io.yamsergey.dta.daemon.network

import io.yamsergey.dta.protocol.network.BodyInfo
import io.yamsergey.dta.protocol.network.BodyPart
import io.yamsergey.dta.protocol.network.Completeness
import io.yamsergey.dta.protocol.network.Coverage
import io.yamsergey.dta.protocol.network.Direction
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.HttpEntry
import io.yamsergey.dta.protocol.network.HttpState
import io.yamsergey.dta.protocol.network.MessageOpcode
import io.yamsergey.dta.protocol.network.NetworkEntry
import io.yamsergey.dta.protocol.network.NetworkEntryDetail
import io.yamsergey.dta.protocol.network.NetworkExportResult
import io.yamsergey.dta.protocol.network.NetworkPage
import io.yamsergey.dta.protocol.network.NetworkQuery
import io.yamsergey.dta.protocol.network.Order
import io.yamsergey.dta.protocol.network.WsConnectionEntry
import io.yamsergey.dta.protocol.network.WsMessageEntry
import io.yamsergey.dta.protocol.network.WsState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 026: the export pages through the list, takes full entries and bodies, and groups messages. */
class NetworkExportTest {
    private val get = HttpEntry("r1:1", "r1", 1_000, "okhttp3", method = "GET", url = "http://h/a", state = HttpState.COMPLETE, status = 200,
        responseBody = BodyInfo(BodyPart.RESPONSE, 2, contentType = "application/json", completeness = Completeness.COMPLETE))
    private val zip = HttpEntry("r1:2", "r1", 2_000, "okhttp3", method = "GET", url = "http://h/z", state = HttpState.COMPLETE, status = 200,
        responseBody = BodyInfo(BodyPart.RESPONSE, 3, contentType = "application/zip", binary = true, completeness = Completeness.COMPLETE))
    private val unread = HttpEntry("r1:3", "r1", 3_000, "urlconnection", method = "POST", url = "http://h/u", state = HttpState.COMPLETE, status = 200,
        responseBody = BodyInfo(BodyPart.RESPONSE, 0, declaredBytes = 41, completeness = Completeness.NOT_READ, completenessReason = "the app closed the body without reading it"))
    private val socket = WsConnectionEntry("r1:4", "r1", 4_000, "okhttp3-ws", url = "ws://h/ws", state = WsState.OPEN, openedAtMs = 4_010)
    private val m1 = WsMessageEntry("r1:5", "r1", 5_000, "okhttp3-ws", connectionId = "r1:4", direction = Direction.OUT, opcode = MessageOpcode.TEXT,
        payload = BodyInfo(BodyPart.MESSAGE, 2, completeness = Completeness.COMPLETE))
    private val m2 = m1.copy(id = "r1:6", timeMs = 6_000, direction = Direction.IN)

    private val listed: List<NetworkEntry> = listOf(get, zip, unread, m1, m2) // the socket itself is filtered out
    private val queries = mutableListOf<NetworkQuery>()
    private val bytes = mapOf(("r1:1" to BodyPart.RESPONSE) to "{}".toByteArray(), ("r1:2" to BodyPart.RESPONSE) to byteArrayOf(1, 2, 3),
        ("r1:5" to BodyPart.MESSAGE) to "hi".toByteArray(), ("r1:6" to BodyPart.MESSAGE) to "yo".toByteArray())

    private val export = NetworkExport(
        page = { q ->
            queries += q
            // Two pages of at most 2 entries, oldest first.
            val start = q.before?.toInt() ?: 0
            val slice = listed.drop(start).take(2)
            NetworkPage(slice, total = listed.size, next = (start + 2).takeIf { it < listed.size }?.toString(), coverage = Coverage(searched = 5, stored = 5, persisted = true))
        },
        entry = { id ->
            when (id) {
                "r1:4" -> NetworkEntryDetail(socket, messages = listOf(m1, m2), messagesTruncated = 1)
                "r1:1" -> NetworkEntryDetail(get.copy(responseHeaders = listOf(Header("Content-Type", "application/json"))))
                else -> NetworkEntryDetail(listed.first { it.id == id })
            }
        },
        body = { id, part, file -> file.writeBytes(bytes[id to part] ?: error("no body")) },
        version = "0.10.0",
        pageSize = 2,
    )

    @Test
    fun `every listed entry once, with headers, bodies and its socket's messages`() = runBlocking {
        val out = File(Files.createTempDirectory("har").toFile(), "s.har")
        val result = export.export(NetworkQuery(), out)
        assertEquals(NetworkExportResult(out.path, entries = 4, webSocketMessages = 2, bodies = 4, bodiesOmitted = 1, messagesNotExported = 1), result)
        assertEquals(listOf(Order.OLDEST_FIRST), queries.map { it.order }.distinct())
        assertEquals(listOf(null, "2", "4"), queries.map { it.before })

        val entries = Json.parseToJsonElement(out.readText()).jsonObject["log"]!!.jsonObject["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("http://h/a", "http://h/z", "http://h/u", "ws://h/ws"), entries.map { it["request"]!!.jsonObject["url"]!!.jsonPrimitive.content })
        val first = entries[0]["response"]!!.jsonObject
        assertEquals("Content-Type", first["headers"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("{}", first["content"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("AQID", entries[1]["response"]!!.jsonObject["content"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue("not-read" in entries[2]["response"]!!.jsonObject["content"]!!.jsonObject["comment"]!!.jsonPrimitive.content)
        assertEquals(listOf("hi", "yo"), entries[3]["_webSocketMessages"]!!.jsonArray.map { it.jsonObject["data"]!!.jsonPrimitive.content })
    }
}
