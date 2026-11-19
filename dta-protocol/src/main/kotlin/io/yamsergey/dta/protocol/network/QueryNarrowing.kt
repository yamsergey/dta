package io.yamsergey.dta.protocol.network

/**
 * Feature 067: can a query only narrow another one — every entry [next] keeps is kept by [previous]?
 * True only when that is provable from the two queries; anything unsure is false (a full pass), so
 * a narrowed pass never misses an entry.
 *
 * Provable: every filter of [previous] is in [next] as it was, or tighter (a longer text to contain,
 * fewer kinds, a later start, an earlier end, a smaller size or duration bound); [next] may add filters and
 * exclusions; every search word of [previous] is implied by one of [next] — the same phrase-ness, its
 * letters and digits inside the new word's, both matched exactly (shorter than [Fuzzy.EDIT_FROM]
 * letters, or with a digit: a longer word of letters gains a one-letter typo and can match more).
 * Never with body patterns (a pattern can run out of time on a body), redaction, an archive scope, or
 * a change between listing only the matches and every row.
 */
object QueryNarrowing {
    fun narrows(previous: NetworkQuery, next: NetworkQuery, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (previous.archive != null || next.archive != null) return false
        if (previous.redact || next.redact) return false
        if (previous.allRows != next.allRows || previous.ignoreSavedFilters != next.ignoreSavedFilters) return false
        val (oldFilter, oldTerms) = runCatching { previous.resolved(nowMs) }.getOrNull() ?: return false
        val (newFilter, newTerms) = runCatching { next.resolved(nowMs) }.getOrNull() ?: return false
        val a = oldFilter ?: NetworkFilter()
        val b = newFilter ?: NetworkFilter()
        if (a.hasPattern || b.hasPattern) return false
        // Links are listed only when kinds asks: a query that starts or stops asking changes what is walked.
        if ((a.kinds == null) != (b.kinds == null)) return false
        // Search words with allRows mark hits but keep every row: the words do not narrow then.
        if (next.allRows && oldTerms != newTerms) return false
        return filterNarrows(a, b) && oldTerms.all { old -> newTerms.any { implies(it, old) } }
    }

    /** Every entry [next] keeps is kept by [previous]. */
    fun filterNarrows(previous: NetworkFilter, next: NetworkFilter): Boolean {
        val a = previous
        val b = next
        fun same(x: Any?, y: Any?) = x == null || x == y
        fun contains(x: String?, y: String?) = x == null || (y != null && y.contains(x, ignoreCase = true))
        fun atLeast(x: Long?, y: Long?) = x == null || (y != null && y >= x)
        fun atMost(x: Long?, y: Long?) = x == null || (y != null && y <= x)
        return contains(a.url, b.url) &&
            same(a.method?.lowercase(), b.method?.lowercase()) &&
            same(a.status, b.status) &&
            (a.kinds == null || (b.kinds != null && a.kinds.containsAll(b.kinds))) &&
            atLeast(a.sinceMs, b.sinceMs) && atMost(a.untilMs, b.untilMs) &&
            contains(a.bodyContains, b.bodyContains) &&
            same(a.runs, b.runs) && same(a.intervened, b.intervened) &&
            contains(a.activity, b.activity) && same(a.delivery, b.delivery) &&
            same(a.host?.lowercase(), b.host?.lowercase()) &&
            (a.exclude.orEmpty().all { it in b.exclude.orEmpty() }) &&
            same(a.direction, b.direction) && same(a.frame, b.frame) &&
            contains(a.envelopeType, b.envelopeType) && contains(a.topic, b.topic) &&
            same(a.connection, b.connection) &&
            same(a.headerName?.lowercase(), b.headerName?.lowercase()) &&
            (a.headerValue == null || (a.headerName?.lowercase() == b.headerName?.lowercase() && contains(a.headerValue, b.headerValue))) &&
            contains(a.mime, b.mime) &&
            atLeast(a.minDurationMs, b.minDurationMs) && atMost(a.maxDurationMs, b.maxDurationMs) &&
            atLeast(a.minSize, b.minSize) && atMost(a.maxSize, b.maxSize) &&
            contains(a.requestContains, b.requestContains) && contains(a.responseContains, b.responseContains) &&
            same(a.source, b.source)
    }

    /** Every text holding [term] holds [old] (both exact; see [narrows]). */
    fun implies(term: Term, old: Term): Boolean {
        if (term.phrase != old.phrase) return false
        val k = Fuzzy.key(term.text)
        val o = Fuzzy.key(old.text)
        if (o.isEmpty()) return false
        return exact(k) && exact(o) && k.contains(o)
    }

    private fun exact(key: String) = key.length < Fuzzy.EDIT_FROM || key.any { it.isDigit() }
}
