package io.yamsergey.dta.state

import kotlin.test.Test
import kotlin.test.assertEquals

class DtaStateFunctionTest {
    @Test
    fun `a registered function is kept by name and runs only when invoked`() {
        var runs = 0
        DtaState.function("cart.clear", "Empty the cart") { args -> runs++; "cleared ${args["reason"]}" }
        assertEquals(0, runs)
        val f = DtaStateBridge.functions.getValue("cart.clear")
        assertEquals("Empty the cart", f.description)
        assertEquals("cleared test", f.invoke(mapOf("reason" to "test")))
        assertEquals(1, runs)
    }
}
