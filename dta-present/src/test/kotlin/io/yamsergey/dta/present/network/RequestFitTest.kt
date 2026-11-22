package io.yamsergey.dta.present.network

import kotlin.test.Test
import kotlin.test.assertEquals

/** D-024 (lead review): in a narrow Request cell the host goes first, the path last. */
class RequestFitTest {
    private val w: (String) -> Int = { it.length }

    @Test
    fun `host and path when both fit`() {
        assertEquals("127.0.0.1:8099" to "/echo", RequestFit.fit("127.0.0.1:8099", "/echo", 19, w))
    }

    @Test
    fun `the host goes before the path is cut`() {
        assertEquals("" to "/echo", RequestFit.fit("127.0.0.1:8099", "/echo", 18, w))
        assertEquals("" to "/orders?side=buy", RequestFit.fit("127.0.0.1:8099", "/orders?side=buy", 16, w))
    }

    @Test
    fun `a path too long alone is cut at its end`() {
        assertEquals("" to "/orders?s…", RequestFit.fit("127.0.0.1:8099", "/orders?side=buy", 10, w))
        assertEquals("" to "", RequestFit.fit(null, "/echo", 0, w))
    }
}
