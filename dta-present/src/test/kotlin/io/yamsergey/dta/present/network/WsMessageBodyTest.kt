package io.yamsergey.dta.present.network

import kotlin.test.Test
import kotlin.test.assertEquals

class WsMessageBodyTest {
    @Test
    fun `a message's envelope is in the header and its JSON payload is the tree (038)`() {
        val result = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"body":{"part":"message","storedBytes":60},"text":${kotlinx.serialization.json.JsonPrimitive("MESSAGE\ndestination:/topic/quotes\nmessage-id:m-1\n\n{\"p\":1,\"s\":\"ACME\"}\u0000")}}""",
        ) as kotlinx.serialization.json.JsonObject
        val v = BodyView.of(result)
        assertEquals(true, v.header.line.endsWith("stomp MESSAGE #m-1 @/topic/quotes"), v.header.line)
        assertEquals(true, BodyMode.TREE in v.modes)
        assertEquals(listOf("p", "s"), v.tree()!!.children.map { it.key })
        assertEquals(true, v.render(BodyMode.FORMATTED).startsWith("{\n  \"p\": 1"), v.render(BodyMode.FORMATTED))
        assertEquals(true, v.render(BodyMode.RAW).startsWith("MESSAGE\n"))
    }
}

class DaemonEnvelopeTest {
    private fun obj(s: String) = kotlinx.serialization.json.Json.parseToJsonElement(s) as kotlinx.serialization.json.JsonObject

    @Test
    fun `a row shows the envelope the daemon read with the app's rule (039)`() {
        val row = EntryRows.of(obj("""{"type":"ws-message","id":"m1","timeMs":1,"direction":"in","url":"wss://x/live","payload":{"preview":"42|ORDER_UPDATE|{}"},
            "envelope":{"format":"orders","kind":"ORDER_UPDATE","id":"42","payload":"{}","rule":"f1"}}"""))
        assertEquals(listOf("orders", "ORDER_UPDATE #42", "f1"), listOf(row.envelope?.format, row.envelope?.label(), row.envelope?.rule))
        // An older daemon: detected here as in 038.
        val old = EntryRows.of(obj("""{"type":"ws-message","id":"m2","timeMs":1,"direction":"in","payload":{"preview":"{\"type\":\"tick\"}"}}"""))
        assertEquals("json", old.envelope?.format)
    }

    @Test
    fun `a body opens the payload the rule found, and says when it is not JSON`() {
        val v = BodyView.of(obj("""{"body":{"part":"message","storedBytes":20},"text":"42|ORDER_UPDATE|{\"orderId\":7}",
            "envelope":{"format":"orders","kind":"ORDER_UPDATE","id":"42","payload":"{\"orderId\":7}","rule":"f1"}}"""))
        assertEquals(true, v.header.line.endsWith("orders ORDER_UPDATE #42"), v.header.line)
        assertEquals(listOf("orderId"), v.tree()!!.children.map { it.key })
        val text = BodyView.of(obj("""{"body":{"part":"message","storedBytes":8},"text":"PING:abc",
            "envelope":{"format":"pings","kind":"PING","payload":"abc","rule":"f2","note":"payload is not JSON"}}"""))
        assertEquals(true, text.header.line.endsWith("pings PING · payload is not JSON"), text.header.line)
    }

    @Test
    fun `a JSON message whose rule names a payload field opens that payload (041)`() {
        val v = BodyView.of(obj("""{"body":{"part":"message","storedBytes":40},"text":"{\"h\":{\"op\":\"tick\"},\"b\":{\"price\":2}}",
            "envelope":{"format":"ticker","kind":"tick","payload":"{\"price\":2}","rule":"f1"}}"""))
        assertEquals(listOf("price"), v.tree()!!.children.map { it.key })
        assertEquals(true, v.render(BodyMode.RAW).startsWith("{\"h\""), v.render(BodyMode.RAW))
    }


    @Test
    fun `a connection's messages as a table, the format in columns, the text as sent (041)`() {
        val rows = listOf(
            EntryRows.of(obj("""{"type":"ws-message","id":"m1","timeMs":1000,"direction":"out","payload":{"preview":"{\"op\":\"sub\",\"rid\":1}"},
                "envelope":{"format":"json","kind":"sub","id":"1","payload":"{\"op\":\"sub\",\"rid\":1}"}}""")),
            EntryRows.of(obj("""{"type":"ws-message","id":"m2","timeMs":1040,"direction":"in","payload":{"preview":"{\"h\":{\"op\":\"ack\",\"rid\":1},\"b\":{\"ok\":true}}"},
                "envelope":{"format":"Ticker","kind":"ack","id":"1","payload":"{\"ok\":true}","rule":"f3"}}""")),
        )
        val t = MessageTable.of(rows)
        assertEquals(listOf("Time", "", "Kind", "Id", "Message", "Notes"), t.columns)
        assertEquals(listOf("↑", "sub", "1"), listOf(t.rows[0].direction, t.rows[0].kind, t.rows[0].messageId))
        // The text is the message, never prefixed with its envelope; a rule's payload is what a rule-read message carries.
        assertEquals("{\"op\":\"sub\",\"rid\":1}", t.rows[0].text)
        assertEquals("{\"ok\":true}", t.rows[1].text)
        assertEquals(true, t.rows[1].note.startsWith("↩ answers"), t.rows[1].note)
    }

}
