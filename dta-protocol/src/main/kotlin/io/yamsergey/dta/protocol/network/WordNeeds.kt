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

    /** A search word or phrase: found exactly, or (letters only, [Fuzzy.EDIT_FROM]+) one edit away. */
    fun term(t: Term): Need {
        val k = key(t.text) ?: return Need.Any
        if (k.any { it.isDigit() } || k.length < Fuzzy.EDIT_FROM) return Need.All(setOf(k))
        // One edit leaves one half untouched (as Fuzzy.Text.near looks for it); a half under MIN cannot narrow.
        val half = k.length / 2
        val left = k.substring(0, half)
        val right = k.substring(half)
        if (left.length < MIN) return Need.Any
        return Need.Or(listOf(Need.All(setOf(k)), Need.All(setOf(left)), Need.All(setOf(right))))
    }

    /** `body:` text: a case-insensitive substring of a body (never fuzzy). */
    fun exact(text: String): Need = key(text)?.let { Need.All(setOf(it)) } ?: Need.Any

    /** Feature 053 patterns: the literals every match must contain (research R6); filled in by step B2. */
    fun pattern(@Suppress("UNUSED_PARAMETER") p: String): Need = Need.Any

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
