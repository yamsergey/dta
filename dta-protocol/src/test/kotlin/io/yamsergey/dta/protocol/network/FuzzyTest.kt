package io.yamsergey.dta.protocol.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FuzzyTest {
    /** With typos (`typos:on`), as most of these cases are about the one-edit allowance. */
    private fun spans(text: String, term: String) = Fuzzy.Text(text).find(term, typos = true).map { text.substring(it.start, it.end) }

    private fun exact(text: String, term: String) = Fuzzy.Text(text).find(term).map { text.substring(it.start, it.end) }

    @Test
    fun `exact by default - no typo is allowed unless asked (decision 2026-11-22)`() {
        assertEquals(emptyList(), exact("the token expired", "tokan"))
        assertEquals(emptyList(), exact("the token expired", "expird"))
        assertEquals(emptyList(), exact("rain cain majn", "main"))
        assertEquals(emptyList(), exact("remain contain", "remainz"))
        assertEquals(listOf("token"), exact("the token expired", "token"))
        // The normalisation stays: case and separators are still ignored.
        assertEquals(listOf("order-id", "ORDER_ID"), exact("x-order-id: 7, ORDER_ID", "orderId"))
        assertEquals(listOf("token expired"), exact("the token expired", "token expired"))
        assertEquals(false, Fuzzy.Text("the token expired").contains("tokan"))
        assertEquals(true, Fuzzy.Text("the token expired").contains("tokan", typos = true))
    }

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
    fun `in a phrase the edit is allowed only inside a word of five letters or more (040 US1-4)`() {
        assertEquals(emptyList(), spans("the token expired", "tokn expired"), "tokn has four letters: exact only, inside a phrase too")
        assertEquals(listOf("token expired"), spans("the token expired", "tokan expired"))
        assertEquals(listOf("token expired"), spans("the token expired", "token expird"))
        assertEquals(emptyList(), spans("the cart expired", "cort expired"), "four letters")
        assertEquals(emptyList(), spans("the token expired", "tokan expird"), "one edit per phrase")
        // Numbers and ids stay exact; a word of letters beside them still takes one edit.
        assertEquals(emptyList(), spans("order 42 failed", "order 43 failed"))
        assertEquals(listOf("order 42 failed"), spans("order 42 failed", "order 42 faild"))
        assertEquals(emptyList(), spans("item-15 item-20", "item 10"))
    }

    @Test
    fun `every occurrence, in order`() {
        assertEquals(listOf("ACME", "acme"), spans("ACME and acme", "acme"))
    }

    @Test
    fun `an exact hit wins over a near one`() {
        val text = "tokan token"
        assertEquals(listOf(6 to 11), Fuzzy.Text(text).find("token", typos = true).map { it.start to it.end })
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
        assertEquals(1, Fuzzy.Text(text).find("expird", typos = true).size)
        assertTrue(System.nanoTime() - t0 < 500_000_000, "took ${(System.nanoTime() - t0) / 1_000_000} ms")
    }
}
