package io.yamsergey.dta.protocol.network

import kotlinx.serialization.Serializable

/**
 * Feature 040: the fuzzy match of network search. Letters and digits only, lower case
 * (`orderId` = `order_id` = `order-id` = `order id`); a term also matches with one edit when the
 * edit falls inside one of its words of 5+ letters (no digits): in a phrase too, so `"tokn expired"`
 * stays exact while `"tokan expired"` finds `token expired` (040 FR-003, US1-4). Spans are given in
 * the original text, for highlighting.
 */
object Fuzzy {
    const val EDIT_FROM = 5
    private const val MAX_SPANS = 1000

    @Serializable
    data class Span(val start: Int, val end: Int)

    /** A short piece of text around a match; [start]..[end] is the match inside [text]. */
    @Serializable
    data class Excerpt(val text: String, val start: Int, val end: Int) {
        fun render(mark: (String) -> String): String = text.substring(0, start) + mark(text.substring(start, end)) + text.substring(end)
    }

    fun excerpt(text: String, span: Span, around: Int = 40): Excerpt {
        val from = (span.start - around).coerceAtLeast(0)
        val to = (span.end + around).coerceAtMost(text.length)
        val pre = if (from > 0) "…" else ""
        val post = if (to < text.length) "…" else ""
        val body = text.substring(from, to).replace('\n', ' ').replace('\r', ' ')
        return Excerpt(pre + body + post, pre.length + span.start - from, pre.length + span.end - from)
    }

    /** The term as compared: letters and digits, lower case. */
    fun key(term: String): String = buildString { term.forEach { if (it.isLetterOrDigit()) append(it.lowercaseChar()) } }

    /**
     * The words of [term] that may take one edit, as ranges of its [key]: runs of letters and digits
     * between separators, at least [EDIT_FROM] long, without digits. None: the term matches exactly.
     */
    fun editable(term: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var n = 0
        var start = -1
        var letters = true
        fun close() {
            if (start >= 0 && letters && n - start >= EDIT_FROM) out += start until n
            start = -1
            letters = true
        }
        for (c in term) {
            if (!c.isLetterOrDigit()) { close(); continue }
            if (start < 0) start = n
            if (c.isDigit()) letters = false
            n++
        }
        close()
        return out
    }

    /** [source] normalised once, searched for several terms. */
    class Text(private val source: String) {
        private val norm: String
        /** Original index of each normalised character. */
        private val at: IntArray

        init {
            val sb = StringBuilder(source.length)
            val idx = IntArray(source.length)
            var n = 0
            source.forEachIndexed { i, c -> if (c.isLetterOrDigit()) { sb.append(c.lowercaseChar()); idx[n++] = i } }
            norm = sb.toString()
            at = idx.copyOf(n)
        }

        /** Every match of [term], in order; exact ones only when there is any, else those one edit away. */
        fun find(term: String): List<Span> {
            val k = key(term)
            if (k.isEmpty() || norm.isEmpty()) return emptyList()
            val exact = exact(k)
            // Numbers and ids must match exactly: a typo is allowed only in words of letters.
            val words = if (exact.isEmpty()) editable(term) else emptyList()
            if (words.isEmpty()) return exact.map(::span)
            return near(k, words).map(::span)
        }

        fun contains(term: String): Boolean = find(term).isNotEmpty()

        private fun span(r: IntRange) = Span(at[r.first], at[r.last] + 1)

        /** How many of the range's ends sit on a word edge of the original text (0..2). */
        private fun edges(first: Int, last: Int): Int {
            val a = at[first]
            val b = at[last]
            val startEdge = a == 0 || !source[a - 1].isLetterOrDigit()
            val endEdge = b == source.length - 1 || !source[b + 1].isLetterOrDigit()
            return (if (startEdge) 1 else 0) + (if (endEdge) 1 else 0)
        }

        private fun exact(k: String): List<IntRange> {
            val out = ArrayList<IntRange>()
            var i = norm.indexOf(k)
            while (i >= 0 && out.size < MAX_SPANS) { out += i until i + k.length; i = norm.indexOf(k, i + k.length) }
            return out
        }

        /**
         * Matches one edit away: one half of the term occurs exactly (pigeonhole), the window around
         * it is checked; the edit must fall inside one of the [words] (ranges of [k]).
         */
        private fun near(k: String, words: List<IntRange>): List<IntRange> {
            val m = k.length
            val half = m / 2
            val left = k.substring(0, half)
            val right = k.substring(half)
            val starts = sortedSetOf<Int>()
            fun hits(part: String, offset: Int) {
                var i = norm.indexOf(part)
                while (i >= 0 && starts.size < MAX_SPANS * 4) {
                    for (d in -1..1) (i - offset + d).takeIf { it >= 0 }?.let { starts += it }
                    i = norm.indexOf(part, i + 1)
                }
            }
            hits(left, 0)
            hits(right, half)
            val out = ArrayList<IntRange>()
            var after = -1
            for (s in starts) {
                if (s <= after) continue
                var best: IntRange? = null
                var bestCost = 2
                var bestEdges = -1
                for (len in intArrayOf(m, m - 1, m + 1)) {
                    if (s + len > norm.length) continue
                    val window = norm.substring(s, s + len)
                    val cost = QueryLine.distance(window, k).let { d -> if (d <= 1 && !editIn(window, k, words)) 2 else d }
                    val edges = edges(s, s + len - 1)
                    // Fewer edits first; at a tie the one that starts and ends where words do.
                    if (cost < bestCost || cost == bestCost && edges > bestEdges) { bestCost = cost; bestEdges = edges; best = s until s + len }
                }
                if (best != null && bestCost <= 1) {
                    out += best
                    after = best.last
                    if (out.size >= MAX_SPANS) break
                }
            }
            return out
        }

        /** Whether [window] (one edit from [k]) differs from it only inside one of [words]. */
        private fun editIn(window: String, k: String, words: List<IntRange>): Boolean = words.any { w ->
            val before = w.first
            val after = k.length - (w.last + 1)
            window.length >= before + after &&
                window.regionMatches(0, k, 0, before) &&
                window.regionMatches(window.length - after, k, w.last + 1, after) &&
                QueryLine.distance(window.substring(before, window.length - after), k.substring(w.first, w.last + 1)) <= 1
        }
    }
}
