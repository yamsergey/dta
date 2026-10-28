package io.yamsergey.dta.cli.format

/**
 * The design system's terminal realization (surfaces.md): 16-colour ANSI mapping of the
 * semantic tokens, and statement glyphs with their ASCII forms (statements.md).
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

    enum class Statement(val glyph: String, val ascii: String, val token: Token?) {
        FALLBACK("▲", "!", Token.CAVEAT),
        DEGRADED("▲", "!", Token.CAVEAT),
        INCOMPLETE("◐", "~", Token.CAVEAT),
        EVICTED("▲", "!", Token.CAVEAT),
        AUTO("◌", "?", Token.INFERRED),
        SCRIPTED("✎", "*", Token.INTERVENED),
        REDACTED("▒", "#", Token.INK_MUTED),
        DECODED("⇢", ">", Token.INK_MUTED),
        BINARY("⧉", "b", Token.INK_MUTED),
        FAILED("✕", "x", Token.STATUS_SERVER),
        PENDING("…", "..", Token.INK_MUTED),
        PROCESS_ENDED("◼", "[]", Token.INK_MUTED),
        NOT_CAPTURED("○", "o", Token.INK_MUTED),
    }

    fun paint(text: String, token: Token?): String =
        if (!color || token == null || text.isEmpty()) text else "\u001b[${token.ansi}m$text\u001b[0m"

    /** A statement mark: glyph + word, in the statement's colour. */
    fun mark(statement: Statement, word: String): String =
        paint("${if (unicode) statement.glyph else statement.ascii} $word", statement.token)

    companion object {
        /** Colour and Unicode only on a terminal without NO_COLOR (and `--no-color` off). */
        fun detect(noColor: Boolean, env: Map<String, String> = System.getenv(), isTty: Boolean = System.console() != null): Style {
            val color = !noColor && isTty && env["NO_COLOR"].isNullOrEmpty() && env["TERM"] != "dumb"
            return Style(color = color, unicode = color)
        }

        val PLAIN = Style(color = false, unicode = false)
    }
}
