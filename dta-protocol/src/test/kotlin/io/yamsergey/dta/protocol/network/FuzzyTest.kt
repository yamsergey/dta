package io.yamsergey.dta.protocol.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FuzzyTest {
    private fun spans(text: String, term: String) = Fuzzy.Text(text).find(term).map { text.substring(it.start, it.end) }

    @Test
    fun `case and separators do not matter`() {
        assertEquals(listOf("orderId"), spans("""{"orderId":7}""", "orderid"))
        assertEquals(listOf("order_id"), spans("""{"order_id":7}""", "orderId"))
        assertEquals(listOf("order-id"), spans("x-order-id: 7", "order id"))
        assertEquals(listOf("Token Expired"), spans("error: Token Expired.", "token expired"))
    }

    @Test
    fun `one edit in words of five letters or more`() {
        assertEquals(listOf("token"), spans("the token expired", "tokan"))
        assertEquals(listOf("expired"), spans("the token expired", "expird"))
        assertEquals(emptyList(), spans("the token expired", "tokn"))
        assertEquals(emptyList(), spans("the token expired", "takan"))
        assertEquals(listOf("tok"), spans("tok", "tok"))
        // Numbers and ids match exactly.
        assertEquals(emptyList(), spans("item-15 item-20", "item10"))
    }

    @Test
    fun `every occurrence, in order`() {
        assertEquals(listOf("ACME", "acme"), spans("ACME and acme", "acme"))
    }

    @Test
    fun `an exact hit wins over a near one`() {
        val text = "tokan token"
        assertEquals(listOf(6 to 11), Fuzzy.Text(text).find("token").map { it.start to it.end })
    }

    @Test
    fun `a term of only separators finds nothing`() {
        assertEquals(emptyList(), spans("a - b", "-"))
    }

    @Test
    fun `excerpt around a span`() {
        val text = "a".repeat(100) + "token" + "b".repeat(100)
        val e = Fuzzy.excerpt(text, Fuzzy.Span(100, 105), around = 10)
        assertEquals("…aaaaaaaaaa[token]bbbbbbbbbb…", e.render { "[$it]" })
        assertTrue(Fuzzy.excerpt("token", Fuzzy.Span(0, 5)).text == "token")
    }

    @Test
    fun `large text stays fast`() {
        val text = buildString { repeat(50_000) { append("{\"price\":20.$it,\"symbol\":\"ACME\"}") } } + "expired"
        val t0 = System.nanoTime()
        assertEquals(1, Fuzzy.Text(text).find("expird").size)
        assertTrue(System.nanoTime() - t0 < 500_000_000, "took ${(System.nanoTime() - t0) / 1_000_000} ms")
    }
}
