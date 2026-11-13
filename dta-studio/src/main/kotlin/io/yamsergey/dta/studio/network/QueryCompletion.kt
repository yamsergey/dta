package io.yamsergey.dta.studio.network

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.util.textCompletion.TextCompletionProvider
import io.yamsergey.dta.present.network.KindChoice
import io.yamsergey.dta.present.network.QueryLineModel
import io.yamsergey.dta.present.network.Seen
import io.yamsergey.dta.present.network.Suggestion
import io.yamsergey.dta.protocol.network.RecentFilter

/**
 * Feature 063: the query line's suggestions through the IDE's own completion. What is offered and how
 * it is inserted stay in [QueryLineModel] (one source); this only maps them to lookup items.
 * [input] is read on each completion (hosts seen, the app's recent filters, the kind switch);
 * [recentChosen] gets a recent filter, which becomes a chip instead of text.
 */
class QueryCompletion(
    private val input: () -> Input,
    private val recentChosen: (String) -> Unit,
) : TextCompletionProvider, DumbAware {

    data class Input(val seen: Seen = Seen(), val recent: List<RecentFilter> = emptyList(), val kind: KindChoice = KindChoice.ALL)

    override fun getAdvertisement(): String? = null

    override fun getPrefix(text: String, offset: Int): String = QueryLineModel.word(text, offset)

    override fun applyPrefixMatcher(result: CompletionResultSet, prefix: String): CompletionResultSet =
        result.withPrefixMatcher(PlainPrefixMatcher(prefix, true))

    /** Every character but whitespace belongs to the word (`status:4xx`, `-host:a.b`, `body:/x/`). */
    override fun acceptChar(c: Char): CharFilter.Result =
        if (c.isWhitespace()) CharFilter.Result.HIDE_LOOKUP else CharFilter.Result.ADD_TO_PREFIX

    override fun fillCompletionVariants(parameters: CompletionParameters, prefix: String, result: CompletionResultSet) {
        // Auto-popup has invocation count 0; Ctrl+Space or Down ask for every key (051).
        val items = suggestions(input(), parameters.originalFile.text, parameters.offset, explicit = parameters.invocationCount > 0)
        items.forEachIndexed { i, s -> result.addElement(PrioritizedLookupElement.withPriority(element(s), (items.size - i).toDouble())) }
        result.stopHere()
    }

    private fun element(s: Suggestion): LookupElement {
        val base = LookupElementBuilder.create(s.text).withTypeText(s.hint, true)
        return if (s.recent) {
            base.withIcon(AllIcons.Vcs.History).withInsertHandler { ctx, _ ->
                // A recent filter comes back as a chip in one choice (043).
                ctx.document.deleteString(ctx.startOffset, ctx.tailOffset)
                ctx.editor.caretModel.moveToOffset(ctx.startOffset)
                recentChosen(s.text)
            }
        } else {
            base.withInsertHandler { ctx, _ -> insert(ctx, s) }
        }
    }

    private fun insert(ctx: InsertionContext, s: Suggestion) {
        val doc = ctx.document
        val tail = QueryLineModel.tail(s.text, doc.charsSequence.subSequence(ctx.tailOffset, doc.textLength).toString())
        if (tail.isNotEmpty()) doc.insertString(ctx.tailOffset, tail)
        ctx.editor.caretModel.moveToOffset(ctx.tailOffset + tail.length)
        // A key just completed ("status:") offers its values next.
        if (s.text.endsWith(":") && !ApplicationManager.getApplication().isUnitTestMode) {
            AutoPopupController.getInstance(ctx.project).scheduleAutoPopup(ctx.editor)
        }
    }

    companion object {
        fun suggestions(input: Input, text: String, caret: Int, explicit: Boolean): List<Suggestion> =
            QueryLineModel(text).suggestions(caret, input.seen, input.recent, kind = input.kind, explicit = explicit)
    }
}
