package io.yamsergey.dta.cli.format

import io.yamsergey.dta.present.Statement
import io.yamsergey.dta.present.StatementKind

/**
 * The design system's terminal realization (surfaces.md): 16-colour ANSI mapping of the
 * semantic tokens; statement glyphs and ASCII forms come from dta-present (statements.md).
 */
class Style(val color: Boolean, val unicode: Boolean) {
    enum class Token(val ansi: String) {
        INK_MUTED("90"),
        ACCENT("34"),
        KIND_HTTP("36"),
        KIND_WS("35"),
        NODE_VIEW("34"),
        NODE_COMPOSE("32"),
        STATUS_CLIENT("33"),
        STATUS_SERVER("31"),
        CAVEAT("33"),
        INFERRED("90"),
        INTERVENED("95"),
        DANGER("31"),
        STRONG("1"),
    }

    fun paint(text: String, token: Token?): String =
        if (!color || token == null || text.isEmpty()) text else "\u001b[${token.ansi}m$text\u001b[0m"

    /** A statement mark: glyph + word, in the statement's colour. */
    fun mark(kind: StatementKind, word: String): String =
        paint("${if (unicode) kind.glyph else kind.ascii} $word", tokenOf(kind))

    /** A statement as a line: glyph + its detail. */
    fun mark(statement: Statement): String = mark(statement.kind, statement.detail)

    private fun tokenOf(kind: StatementKind): Token? = when (kind.token) {
        "caveat" -> Token.CAVEAT
        "inferred" -> Token.INFERRED
        "intervened" -> Token.INTERVENED
        "status-server" -> Token.STATUS_SERVER
        "ink-muted" -> Token.INK_MUTED
        else -> null
    }

    companion object {
        /** Colour and Unicode only on a terminal without NO_COLOR (and `--no-color` off). */
        fun detect(noColor: Boolean, env: Map<String, String> = System.getenv(), isTty: Boolean = System.console() != null): Style {
            val color = !noColor && isTty && env["NO_COLOR"].isNullOrEmpty() && env["TERM"] != "dumb"
            return Style(color = color, unicode = color)
        }

        val PLAIN = Style(color = false, unicode = false)
    }
}
