package io.yamsergey.dta.studio.network

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.textCompletion.TextCompletionUtil
import io.yamsergey.dta.present.network.KindChoice
import io.yamsergey.dta.present.network.Seen
import io.yamsergey.dta.protocol.network.RecentFilter

/** Feature 063: the query line's suggestions served through the IDE's completion. */
class QueryCompletionTest : BasePlatformTestCase() {

    private var input = QueryCompletion.Input(Seen(hosts = setOf("api.example.test", "cdn.example.test")), emptyList(), KindChoice.ALL)
    private val chips = mutableListOf<String>()

    private fun line(text: String) {
        val file = myFixture.configureByText("query.txt", text)
        TextCompletionUtil.installProvider(file, QueryCompletion({ input }, { chips += it }), true)
    }

    private fun text() = myFixture.editor.document.text
    private fun caret() = myFixture.editor.caretModel.offset

    fun `test a single match is inserted, a key gets no space`() {
        line("POST sta<caret>")
        myFixture.completeBasic()
        assertEquals("POST status:", text())
        assertEquals(12, caret())
    }

    fun `test values keep the model's order and a chosen value ends with a space`() {
        line("POST status:<caret>")
        myFixture.completeBasic()
        assertEquals(listOf("status:2xx", "status:3xx", "status:4xx", "status:5xx"), myFixture.lookupElementStrings!!.take(4))
        myFixture.lookup.currentItem = myFixture.lookupElements!!.first { it.lookupString == "status:4xx" }
        myFixture.finishLookup('\n')
        assertEquals("POST status:4xx ", text())
        assertEquals(16, caret())
    }

    fun `test no second space when one already follows`() {
        line("status:4<caret> token")
        myFixture.completeBasic()
        myFixture.lookup?.let { l -> l.currentItem = myFixture.lookupElements!!.first { it.lookupString == "status:4xx" }; myFixture.finishLookup('\n') }
        assertEquals("status:4xx token", text())
    }

    fun `test negation is kept and hosts seen are offered`() {
        line("token -host:a<caret>")
        myFixture.completeBasic()
        assertEquals("token -host:api.example.test ", text())
    }

    fun `test keys carry their hint as type text`() {
        line("s<caret>")
        myFixture.completeBasic()
        val status = myFixture.lookupElements!!.first { it.lookupString == "status:" }
        assertFalse(LookupElementPresentation.renderElement(status).typeText.isNullOrBlank())
    }

    fun `test asked for mid-line lists every key, typing after a space offers nothing`() {
        line("token <caret>")
        myFixture.completeBasic()
        assertTrue(myFixture.lookupElementStrings!!.containsAll(listOf("status:", "host:", "method:")))
        assertTrue(QueryCompletion.suggestions(input, "token ", 6, explicit = false).isEmpty())
    }

    fun `test a recent filter becomes a chip and leaves the line`() {
        input = input.copy(recent = listOf(RecentFilter("host:api.example.test", System.currentTimeMillis() - 60_000), RecentFilter("status:5xx", 0)))
        line("<caret>")
        myFixture.completeBasic()
        assertEquals("host:api.example.test", myFixture.lookupElementStrings!!.first())
        myFixture.finishLookup('\n')
        assertEquals("", text())
        assertEquals(listOf("host:api.example.test"), chips)
    }
}
