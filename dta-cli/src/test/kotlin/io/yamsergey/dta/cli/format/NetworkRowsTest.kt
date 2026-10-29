package io.yamsergey.dta.cli.format

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `dta network list` rows read as the plugin's: the same row model (dta-present EntryRows). */
class NetworkRowsTest {
    private val list = Json.parseToJsonElement("""
        {"entries": [
          {"type":"ws-connection","id":"r1-1:6","runId":"r1-1","timeMs":1700000004500,"library":"okhttp3","url":"wss://chat.example.com/chat",
           "state":"closed","openedAtMs":1700000004600,"closedAtMs":1700000005900,"closeCode":1000,"messageCount":2},
          {"type":"ws-message","id":"r1-1:7","runId":"r1-1","timeMs":1700000005000,"library":"okhttp3","connectionId":"r1-1:6",
           "url":"wss://chat.example.com/chat","direction":"out","opcode":"binary","payload":{"part":"message","storedBytes":16,"completeness":"complete","binary":true}},
          {"type":"ws-message","id":"r1-1:8","runId":"r1-1","timeMs":1700000005100,"library":"okhttp3","connectionId":"r1-1:6",
           "url":"wss://chat.example.com/chat","direction":"in","opcode":"text","payload":{"part":"message","storedBytes":5,"completeness":"complete","preview":"hello"}}
        ], "total": 3}
    """)

    private val out = Formatters(Style.PLAIN).format("network.list", list).lines()

    @Test
    fun `a connection reads like its handshake`() {
        val row = out[0]
        assertTrue("GET" in row && "chat.example.com/chat" in row && "closed 1000" in row && "1.3 s · 2 msgs" in row, row)
    }

    @Test
    fun `a message names its socket and shows its payload, never an internal id or opcode as a method`() {
        val (binary, text) = out[1] to out[2]
        assertTrue("/chat" in binary && "binary 16 B" in binary, binary)
        assertTrue("/chat" in text && "hello" in text, text)
        assertFalse(out.any { "conn r1-1" in it }, out.joinToString("\n"))
    }
}
