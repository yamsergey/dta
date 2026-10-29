package io.yamsergey.dta.studio.ui

import com.intellij.ui.components.JBLabel
import io.yamsergey.dta.present.Statement

/** A statement mark: glyph and word in the statement's colour; the sentence and code in the tooltip (statements.md). */
class StatementMark(statement: Statement) : JBLabel("${statement.kind.glyph} ${statement.word}") {
    init {
        foreground = DtaColors.text(statement.kind)
        toolTipText = listOfNotNull(statement.sentence, statement.code?.let { "Code: $it" }).joinToString("<br>", "<html>", "</html>")
    }
}
