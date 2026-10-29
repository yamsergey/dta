package io.yamsergey.dta.studio.ui

import com.intellij.ui.JBColor
import io.yamsergey.dta.present.StatementKind
import io.yamsergey.dta.present.Tokens
import java.awt.Color

/** DTA's semantic colours as IDE light/dark pairs (surfaces.md: neutrals stay the IDE's). */
object DtaColors {
    fun of(token: String): Color {
        val t = Tokens[token] ?: return JBColor.foreground()
        return JBColor(Color(t.light.value), Color(t.dark.value))
    }

    /** Text colour of a statement; ink-muted is the IDE's own secondary text. */
    fun text(kind: StatementKind): Color = if (kind.token == "ink-muted") JBColor.GRAY else of(kind.token)

    /** Ground of a banner for [kind]. */
    fun tint(kind: StatementKind): Color = when (kind.token) {
        "caveat" -> of("caveat-tint")
        "inferred" -> of("inferred-tint")
        "intervened" -> of("intervened-tint")
        "status-server" -> of("danger-tint")
        else -> of("inferred-tint")
    }
}
