package io.yamsergey.dta.protocol.network

/**
 * Feature 070 (research R2, R6): what an entry's [IndexText] must hold for a query to keep it. A
 * need never drops an entry the query keeps (contracts/narrowing.md C1): it only chooses which
 * entries are read; [NetworkSearch] and [NetworkFilterEval] decide each one, as without an index.
 */
sealed interface Need {
    /** Anything may match: no narrowing. */
    data object Any : Need

    /** Every one of [strings] occurs (normalised, as [IndexText] holds them; each at least [WordNeeds.MIN] long). */
    data class All(val strings: Set<String>) : Need

    data class And(val parts: List<Need>) : Need

    data class Or(val parts: List<Need>) : Need
}

object WordNeeds {
    /** Shortest string an index of trigrams can narrow by. */
    const val MIN = 3

    /**
     * The need of a query's resolved [filter] and [terms]; [words] when the terms leave only the
     * entries holding them (a search, or a list without `allRows`).
     */
    fun of(filter: NetworkFilter?, terms: List<Term>, words: Boolean): Need {
        val parts = buildList {
            if (words) terms.forEach { add(term(it)) }
            filter?.let { f ->
                listOfNotNull(f.bodyContains, f.requestContains, f.responseContains).forEach { add(exact(it)) }
                listOfNotNull(f.bodyPattern, f.requestPattern, f.responsePattern).forEach { add(pattern(it)) }
            }
        }
        return and(parts)
    }

    /**
     * A search word or phrase: found exactly; with [Term.typos], also one edit away when it has a
     * word that may take one ([Fuzzy.editable]).
     */
    fun term(t: Term): Need {
        val k = key(t.text) ?: return Need.Any
        if (!t.typos || Fuzzy.editable(t.text).isEmpty()) return Need.All(setOf(k))
        // One edit leaves one half untouched (as Fuzzy.Text.near looks for it); a half under MIN cannot narrow.
        val half = k.length / 2
        val left = k.substring(0, half)
        val right = k.substring(half)
        if (left.length < MIN) return Need.Any
        return Need.Or(listOf(Need.All(setOf(k)), Need.All(setOf(left)), Need.All(setOf(right))))
    }

    /** `body:` text: a case-insensitive substring of a body (never fuzzy). */
    fun exact(text: String): Need = key(text)?.let { Need.All(setOf(it)) } ?: Need.Any

    /**
     * Feature 053 patterns (research R6): the literal runs every match must contain, read at the
     * pattern's top level. Anything this reading is not sure of gives up a run, never adds one:
     * an alternation at the top, inline flags or quoting give [Need.Any].
     */
    fun pattern(p: String): Need {
        if ("(?" in p || "\\Q" in p) return Need.Any
        val runs = ArrayList<StringBuilder>()
        var run = StringBuilder()
        fun cut() { if (run.isNotEmpty()) runs += run; run = StringBuilder() }
        var depth = 0
        var i = 0
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' && i + 1 < p.length -> {
                    val e = p[i + 1]
                    i += 2
                    when {
                        !e.isLetterOrDigit() -> if (depth == 0) run.append(e)
                        e in CLASS_ESCAPES -> cut()
                        else -> {
                            // \x41, \u0041, \0, \p{L}, \k<n>, back references: what follows is not literal.
                            cut()
                            while (i < p.length && (p[i].isLetterOrDigit() || p[i] in "{}<>")) i++
                        }
                    }
                    continue
                }
                c == '[' -> {
                    cut()
                    i++
                    if (i < p.length && p[i] == '^') i++
                    if (i < p.length && p[i] == ']') i++
                    while (i < p.length && p[i] != ']') { if (p[i] == '\\') i++; i++ }
                }
                c == '(' -> { cut(); depth++ }
                c == ')' -> { cut(); depth-- }
                c == '|' -> if (depth == 0) return Need.Any
                c == '.' || c == '^' || c == '$' -> cut()
                c == '*' || c == '?' || c == '{' -> {
                    // The atom before may be absent (or repeated): it leaves its run.
                    if (depth == 0 && (c != '{' || p.substring(i + 1).trimStart().startsWith("0") || p.substring(i + 1).startsWith(","))) {
                        if (run.isNotEmpty()) run.setLength(run.length - 1)
                    }
                    cut()
                    if (c == '{') while (i < p.length && p[i] != '}') i++
                }
                c == '+' -> cut()
                depth == 0 -> run.append(c)
            }
            i++
        }
        cut()
        val keys = runs.mapNotNull { key(it.toString()) }.toSet()
        return if (keys.isEmpty()) Need.Any else Need.All(keys)
    }

    /** Escapes of one letter that match a class, a boundary or a control character. */
    private const val CLASS_ESCAPES = "dDwWsSbBAzZGRhHvVXtnrfae"

    /**
     * The normalised key of [text], or null when it cannot narrow: under [MIN], or not all ASCII
     * (binary bodies are searched by the text's UTF-8 bytes, whose Latin-1 reading differs).
     */
    fun key(text: String): String? {
        if (text.any { it.code >= 128 }) return null
        return IndexText.normalise(text).takeIf { it.length >= MIN }
    }

    fun and(parts: List<Need>): Need {
        val narrowing = parts.filter { it != Need.Any }
        return when (narrowing.size) {
            0 -> Need.Any
            1 -> narrowing.single()
            else -> Need.And(narrowing)
        }
    }

    /** Whether [text] (an [IndexText]) satisfies [need]. */
    fun holds(need: Need, text: String): Boolean = when (need) {
        Need.Any -> true
        is Need.All -> need.strings.all { it in text }
        is Need.And -> need.parts.all { holds(it, text) }
        is Need.Or -> need.parts.any { holds(it, text) }
    }
}
