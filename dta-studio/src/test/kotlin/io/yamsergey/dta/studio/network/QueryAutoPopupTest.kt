package io.yamsergey.dta.studio.network

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.CompletionAutoPopupTester
import com.intellij.util.textCompletion.TextCompletionUtil
import io.yamsergey.dta.protocol.network.RecentFilter

/** Feature 063: the list opens from typing or when asked, never from text set by code (the reported bug). */
class QueryAutoPopupTest : BasePlatformTestCase() {

    private lateinit var tester: CompletionAutoPopupTester
    private var input = QueryCompletion.Input(recent = listOf(RecentFilter("status:5xx", 0)))

    override fun runInDispatchThread() = false

    override fun setUp() {
        super.setUp()
        tester = CompletionAutoPopupTester(myFixture)
        edt<Throwable> {
            val file = myFixture.configureByText("query.txt", "<caret>")
            TextCompletionUtil.installProvider(file, QueryCompletion({ input }, {}), true)
        }
    }

    private fun lookupStrings() = tester.lookup?.items?.map { it.lookupString }

    fun `test typing opens the list`() = tester.runWithAutoPopupEnabled {
        tester.typeWithPauses("POST sta")
        assertEquals(listOf("status:5xx", "status:"), lookupStrings())
    }

    fun `test text set by code opens nothing`() = tester.runWithAutoPopupEnabled {
        // What Enter does after saving the filters: the line is rewritten from the state.
        edt<Throwable> { WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText("") } }
        tester.joinAutopopup()
        tester.joinCompletion()
        assertNull(tester.lookup)
        edt<Throwable> { WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText("token status:") } }
        tester.joinAutopopup()
        tester.joinCompletion()
        assertNull(tester.lookup)
    }

    fun `test the empty line asked on focus offers recent filters first`() = tester.runWithAutoPopupEnabled {
        edt<Throwable> { QueryCompletion.offer(project, myFixture.editor) }
        tester.joinCompletion()
        assertEquals("status:5xx", lookupStrings()?.first())
    }
}
