package io.yamsergey.dta.present.timeline

import io.yamsergey.dta.protocol.timeline.Basis
import io.yamsergey.dta.protocol.timeline.CauseChain
import io.yamsergey.dta.protocol.timeline.ChainDirection
import io.yamsergey.dta.protocol.timeline.ChainLink
import io.yamsergey.dta.protocol.timeline.CutReason

/**
 * The selected event's chain as the Timeline tab shows it (feature 017, research R4): lines to
 * draw between marks, styled by basis, and the CAUSED BY and LED TO sections. Straight from
 * `timeline.causes`: the tab never decides a cause itself.
 */
data class ChainView(
    val lines: List<Line>,
    val causedBy: List<Row>,
    val ledTo: List<Row>,
    val cuts: List<String>,
    val noKnownCause: String?,
) {
    enum class Style { EXACT, DECLARED, INFERRED }

    data class Line(val from: String, val to: String, val style: Style, val label: String)

    data class Row(val depth: Int, val eventId: String, val text: String, val via: String, val unknown: Boolean)

    companion object {
        val EMPTY = ChainView(emptyList(), emptyList(), emptyList(), emptyList(), null)

        fun of(causes: CauseChain?, effects: CauseChain?): ChainView {
            val lines = (causes?.links.orEmpty() + effects?.links.orEmpty())
                .distinctBy { Triple(it.from, it.to, it.link.rule) }
                .map { Line(it.from, it.to, style(it.link.basis), label(it)) }
            val root = (causes ?: effects)?.root?.id
            val noKnown = causes?.takeIf { root in it.noKnownCause }?.let { c ->
                val n = c.inferredCandidates[root] ?: 0
                when {
                    c.inferredIncluded -> "no known cause (exact or declared); inferred below"
                    n == 1 -> "no known cause (1 inferred candidate; turn on inferred links to see it)"
                    n > 1 -> "no known cause ($n inferred candidates; turn on inferred links to see them)"
                    else -> "no known cause"
                }
            }
            val cuts = (causes?.cuts.orEmpty() + effects?.cuts.orEmpty()).map { cut ->
                val what = cut.detail ?: "?"
                "stops at ${cut.at}: " + when (cut.reason) {
                    CutReason.EVICTED -> "the cause $what is no longer kept"
                    CutReason.UNKNOWN_EVENT -> "the cause $what was never seen"
                    CutReason.PROCESS_BOUNDARY -> "the cause $what is from an earlier run"
                    CutReason.DEPTH -> "more links beyond this depth ($what)"
                }
            }
            return ChainView(lines, rows(causes), rows(effects), cuts, noKnown)
        }

        private fun rows(chain: CauseChain?): List<Row> {
            chain ?: return emptyList()
            val byId = chain.nodes.associateBy { it.id }
            val next = chain.links.groupBy { if (chain.direction == ChainDirection.CAUSES) it.to else it.from }
            val out = ArrayList<Row>()
            val seen = hashSetOf(chain.root.id)
            fun walk(id: String, depth: Int) {
                for (l in next[id].orEmpty()) {
                    val other = if (chain.direction == ChainDirection.CAUSES) l.from else l.to
                    val e = byId[other] ?: continue
                    out += Row(depth, other, "${e.source}  ${TimelineEventText.describe(e)}", label(l), other in chain.noKnownCause)
                    if (seen.add(other)) walk(other, depth + 1)
                }
            }
            walk(chain.root.id, 1)
            return out
        }

        private fun style(b: Basis) = when (b) {
            Basis.EXACT -> Style.EXACT
            Basis.DECLARED -> Style.DECLARED
            Basis.INFERRED -> Style.INFERRED
        }

        private fun label(l: ChainLink) = listOfNotNull(l.link.basis.name.lowercase(), l.link.rule, l.link.detail).joinToString(" · ")
    }
}
