package io.yamsergey.dta.daemon.store

import io.yamsergey.dta.protocol.network.Need

/**
 * Feature 070 (research R1, R7): a [Need] as an FTS5 MATCH over the trigram index; null when it
 * cannot narrow. Any subset of a string's trigrams is still a superset, so a long string is
 * narrowed by at most [PER_STRING] of them, spread over it.
 */
object WordIndexSql {
    const val PER_STRING = 8

    fun match(need: Need): String? = when (need) {
        Need.Any -> null
        is Need.All -> need.strings.flatMap(::trigrams).distinct().takeIf { it.isNotEmpty() }?.joinToString(" AND ") { "\"$it\"" }
        is Need.And -> need.parts.mapNotNull(::match).takeIf { it.isNotEmpty() }?.joinToString(" AND ") { "($it)" }
        is Need.Or -> need.parts.map(::match).takeIf { parts -> parts.none { it == null } }?.joinToString(" OR ") { "($it)" }
    }

    fun trigrams(s: String): List<String> {
        val all = s.windowed(3).distinct()
        if (all.size <= PER_STRING) return all
        return (0 until PER_STRING).map { i -> all[(i * (all.size - 1)) / (PER_STRING - 1)] }.distinct()
    }
}
