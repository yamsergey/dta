package io.yamsergey.dta.protocol.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 051: filters for every kind of traffic. */
class QueryLineAllKindsTest {
    private fun parse(q: String) = QueryLine.parse(q, nowMs = 1_000_000L)

    @Test
    fun `websocket keys`() {
        val p = parse("dir:received frame:text type:order.update topic:quotes conn:r1:5")
        assertEquals(emptyList(), p.problems)
        assertEquals(
            NetworkFilter(direction = Direction.IN, frame = MessageOpcode.TEXT, envelopeType = "order.update", topic = "quotes", connection = "r1:5"),
            p.filter,
        )
        assertEquals(Direction.OUT, parse("dir:sent").filter.direction)
        assertEquals(MessageOpcode.BINARY, parse("frame:binary").filter.frame)
    }

    @Test
    fun `status takes connection states and close codes`() {
        listOf("open", "closed", "connecting", "failed", "pending", "1006", "4000").forEach {
            assertEquals(it, parse("status:$it").filter.status, it)
        }
        assertTrue(parse("status:999").problems.isNotEmpty())
        assertTrue(parse("status:5000").problems.isNotEmpty())
    }

    @Test
    fun `http keys`() {
        val p = parse("header:content-encoding=gzip mime:json req:token resp:\"account id\"")
        assertEquals(emptyList(), p.problems)
        assertEquals(
            NetworkFilter(headerName = "content-encoding", headerValue = "gzip", mime = "json", requestContains = "token", responseContains = "account id"),
            p.filter,
        )
        assertEquals(NetworkFilter(headerName = "authorization"), parse("header:authorization").filter)
    }

    @Test
    fun `duration and size compare, two bounds make a range`() {
        assertEquals(NetworkFilter(minDurationMs = 1001), parse("duration:>1s").filter)
        assertEquals(NetworkFilter(minDurationMs = 1000), parse("duration:>=1s").filter)
        assertEquals(NetworkFilter(maxDurationMs = 199), parse("duration:<200ms").filter)
        assertEquals(NetworkFilter(maxDurationMs = 120_000), parse("duration:<=2m").filter)
        assertEquals(NetworkFilter(minSize = 1025, maxSize = 10 * 1024 - 1), parse("size:>1k size:<10k").filter)
        assertEquals(NetworkFilter(minSize = 2 * 1024 * 1024), parse("size:>=2m").filter)
        assertEquals(NetworkFilter(maxSize = 500), parse("size:<=500").filter)
    }

    @Test
    fun `bad values are problems under the word`() {
        listOf("dir:up", "frame:ping", "size:10k", "size:>abc", "duration:5s", "duration:>5x", "size:>1k size:>2k", "header:=x").forEach {
            assertTrue(parse(it).problems.isNotEmpty(), it)
        }
    }

    @Test
    fun `new keys negate, render back and are savable`() {
        val line = "dir:received frame:binary type:tick topic:q conn:c1 status:closed header:x-id=7 mime:json duration:>1s size:<=1k req:a resp:b -dir:sent -type:ping"
        val p = parse(line)
        assertEquals(emptyList(), p.problems)
        assertEquals(listOf(NetworkFilter(direction = Direction.OUT), NetworkFilter(envelopeType = "ping")), p.exclude)
        val again = parse(QueryLine.render(p))
        assertEquals(p.filter, again.filter)
        assertEquals(p.exclude, again.exclude)
        listOf("dir:received", "size:>1k", "header:x-id=7", "-type:ping", "status:1006").forEach { assertTrue(QueryLine.savable(it), it) }
    }

    @Test
    fun `every key has a hint and the kinds it applies to`() {
        assertEquals(QueryLine.KEYS, QueryLine.KEY_INFO.map { it.name })
        QueryLine.KEY_INFO.forEach { assertTrue(it.hint.isNotBlank(), it.name) }
        listOf("dir", "frame", "type", "topic", "conn", "header", "mime", "duration", "size", "req", "resp").forEach { assertTrue(it in QueryLine.KEYS, it) }
    }

    @Test
    fun `a near miss names a new key`() {
        assertTrue(parse("topik:x").problems.single().message.contains("topic:"))
    }

    @Test
    fun `resolved keeps the new fields over a base filter`() {
        val (f, _) = NetworkQuery(filter = NetworkFilter(method = "GET"), q = "dir:sent size:>1k").resolved(1_000_000L)
        assertEquals(NetworkFilter(method = "GET", direction = Direction.OUT, minSize = 1025), f)
    }
}
