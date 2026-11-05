package io.yamsergey.dta.present.network

/** What a piece of a content line is, for its colour (the editor scheme's JSON colours). */
enum class SpanKind { KEY, STRING, NUMBER, LITERAL, PUNCT, PLAIN }

data class Span(val text: String, val kind: SpanKind)

/**
 * Feature 042: the first lines of a message as the body viewer formats it — JSON pretty-printed
 * leniently (a preview cut mid-value still formats; nothing is parsed), other text as its own
 * lines. [more] is how many lines are left, null when the text was cut and the rest is unknown.
 */
data class ContentLines(val lines: List<List<Span>>, val more: Int?) {
    companion object {
        private const val INDENT = "  "
        private val NUMBER = Regex("""-?\d+(\.\d+)?([eE][+-]?\d+)?""")
        private val LITERALS = setOf("true", "false", "null")

        fun of(text: String, max: Int, cut: Boolean = false): ContentLines {
            val t = text.trim()
            val all = if (t.startsWith("{") || t.startsWith("[")) json(t) else plain(text)
            val more = if (cut) null else (all.size - max).coerceAtLeast(0)
            return ContentLines(all.take(max), more)
        }

        private fun plain(text: String): List<List<Span>> =
            text.trimEnd { it.isWhitespace() || it == '\u0000' }.lines().map { listOf(Span(it, SpanKind.PLAIN)) }

        private sealed interface Token
        private data class Str(val text: String) : Token
        private data class Word(val text: String) : Token
        private data class Punct(val c: Char) : Token

        private fun tokens(t: String): List<Token> {
            val out = ArrayList<Token>()
            var i = 0
            while (i < t.length) {
                val c = t[i]
                when {
                    c.isWhitespace() -> i++
                    c in "{}[]:," -> { out += Punct(c); i++ }
                    c == '"' -> {
                        var j = i + 1
                        while (j < t.length && t[j] != '"') j += if (t[j] == '\\') 2 else 1
                        val end = minOf(j + 1, t.length)
                        out += Str(t.substring(i, end))
                        i = end
                    }
                    else -> {
                        var j = i
                        while (j < t.length && !t[j].isWhitespace() && t[j] !in "{}[]:,\"") j++
                        out += Word(t.substring(i, j))
                        i = j
                    }
                }
            }
            return out
        }

        private fun json(t: String): List<List<Span>> {
            val toks = tokens(t)
            val lines = ArrayList<MutableList<Span>>()
            var depth = 0
            var line = mutableListOf<Span>()
            fun newLine() { lines += line; line = mutableListOf(); if (depth > 0) line += Span(INDENT.repeat(depth), SpanKind.PLAIN) }
            fun next(i: Int) = toks.getOrNull(i + 1)
            var i = 0
            while (i < toks.size) {
                when (val tok = toks[i]) {
                    is Punct -> when (tok.c) {
                        '{', '[' -> {
                            val close = if (tok.c == '{') '}' else ']'
                            if ((next(i) as? Punct)?.c == close) { line += Span("${tok.c}$close", SpanKind.PUNCT); i++ }
                            else { line += Span("${tok.c}", SpanKind.PUNCT); depth++; newLine() }
                        }
                        '}', ']' -> {
                            depth = (depth - 1).coerceAtLeast(0)
                            if (line.any { it.text.isNotBlank() }) newLine() else line = mutableListOf<Span>().also { if (depth > 0) it += Span(INDENT.repeat(depth), SpanKind.PLAIN) }
                            line += Span("${tok.c}", SpanKind.PUNCT)
                        }
                        ',' -> { line += Span(",", SpanKind.PUNCT); newLine() }
                        else -> { line += Span(":", SpanKind.PUNCT); line += Span(" ", SpanKind.PLAIN) }
                    }
                    is Str -> line += Span(tok.text, if ((next(i) as? Punct)?.c == ':') SpanKind.KEY else SpanKind.STRING)
                    is Word -> line += Span(tok.text, when {
                        tok.text in LITERALS -> SpanKind.LITERAL
                        NUMBER.matches(tok.text) -> SpanKind.NUMBER
                        else -> SpanKind.PLAIN
                    })
                }
                i++
            }
            if (line.any { it.text.isNotBlank() }) lines += line
            return lines
        }
    }
}
