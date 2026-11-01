package io.yamsergey.dta.daemon.timeline

import io.yamsergey.dta.daemon.timeline.CausesFixture.dialog
import io.yamsergey.dta.daemon.timeline.CausesFixture.flow
import io.yamsergey.dta.daemon.timeline.CausesFixture.request
import io.yamsergey.dta.daemon.timeline.CausesFixture.response
import io.yamsergey.dta.daemon.timeline.CausesFixture.toast
import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feature 016 (T026): inferred candidates — from timing and matching data, always marked and explained. */
class InferredRulesTest {
    @Test
    fun `a toast shortly after a response ended`() {
        val c = InferredRules(flow).candidates(toast).single { it.rule == CauseRules.AFTER_RESPONSE }
        assertEquals(response.id, c.from)
        assertEquals(Basis.INFERRED, c.basis)
        assertEquals("shown 34 ms after the response ended", c.detail)
    }

    @Test
    fun `nothing after the window`() {
        val late = toast.copy(monoMs = response.monoMs + 2_000)
        assertTrue(InferredRules(flow.map { if (it.id == toast.id) late else it }).candidates(late).none { it.rule == CauseRules.AFTER_RESPONSE })
    }

    @Test
    fun `a dialog open while the request was in flight and closed when it ended`() {
        val c = InferredRules(flow).candidates(dialog).single { it.rule == CauseRules.WHILE_IN_FLIGHT }
        assertEquals(request.id, c.from)
        assertTrue(c.detail!!.startsWith("opened while the request was in flight"), c.detail)
    }

    @Test
    fun `a shown text found in the response body`() {
        val rules = InferredRules(flow, body = { if (it == "n7") """{"message":"Deleted","id":42}""" else null })
        val c = rules.candidates(toast).single { it.rule == CauseRules.TEXT_MATCH }
        assertEquals(response.id, c.from)
        assertEquals("the text \"Deleted\" is in the response of DELETE /items/42", c.detail)
    }

    @Test
    fun `short texts and absent bodies never match`() {
        val rules = InferredRules(flow, body = { null })
        assertTrue(rules.candidates(toast).none { it.rule == CauseRules.TEXT_MATCH })
        val ok = InferredRules(flow, body = { """{"m":"Ok"}""" })
        assertTrue(ok.candidates(toast.copy(ui = toast.ui!!.copy(text = "Ok"))).none { it.rule == CauseRules.TEXT_MATCH })
    }

    @Test
    fun `never across processes`() {
        val other = toast.copy(id = "8:6")
        assertEquals(emptyList(), InferredRules(flow + other).candidates(other))
    }
}
